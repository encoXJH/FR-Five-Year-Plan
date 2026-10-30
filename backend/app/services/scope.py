from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from app.errors import AppError
from app.models import (
    CollegeTask,
    ConstructionDirectory,
    ConstructionPlan,
    OutputIndicator,
    ProjectMember,
    SysUser,
    TaskAnnualMilestone,
)
from app.security import active_role_codes
from app.utils import utcnow


ADMIN = "PLATFORM_ADMIN"
LEADER = "SCHOOL_LEADER"
SCHOOL = "SCHOOL_OWNER"
COLLEGE = "COLLEGE_OWNER"


def roles(db: Session, user: SysUser) -> set[str]:
    return active_role_codes(db, user.id)


def is_admin(db: Session, user: SysUser) -> bool:
    return ADMIN in roles(db, user)


def is_school_owner(db: Session, user: SysUser, plan: ConstructionPlan) -> bool:
    if plan.school_owner_id != user.id or SCHOOL not in roles(db, user):
        return False
    now = utcnow()
    membership = db.execute(
        select(ProjectMember.id).where(
            ProjectMember.plan_id == plan.id,
            ProjectMember.user_id == user.id,
            ProjectMember.member_role == SCHOOL,
            ProjectMember.is_primary.is_(True),
            ProjectMember.effective_from <= now,
            or_(ProjectMember.effective_to.is_(None), ProjectMember.effective_to > now),
        )
    ).first()
    return membership is not None


def is_plan_leader(db: Session, user: SysUser, plan: ConstructionPlan) -> bool:
    if LEADER not in roles(db, user):
        return False
    directory = db.get(ConstructionDirectory, plan.directory_id)
    return bool(directory and directory.leader_user_id == user.id)


def can_view_plan(db: Session, user: SysUser, plan: ConstructionPlan) -> bool:
    if is_school_owner(db, user, plan) or is_plan_leader(db, user, plan):
        return True
    if COLLEGE in roles(db, user):
        now = utcnow()
        member = db.execute(
            select(ProjectMember.id).where(
                ProjectMember.plan_id == plan.id,
                ProjectMember.user_id == user.id,
                ProjectMember.member_role == COLLEGE,
                ProjectMember.effective_from <= now,
                or_(ProjectMember.effective_to.is_(None), ProjectMember.effective_to > now),
            )
        ).first()
        return member is not None
    return False


def get_plan(db: Session, user: SysUser, plan_id: int, manage: bool = False) -> ConstructionPlan:
    plan = db.get(ConstructionPlan, plan_id)
    if not plan:
        raise AppError(404, "PLAN_NOT_FOUND", "建设计划不存在")
    allowed = is_school_owner(db, user, plan) if manage else can_view_plan(db, user, plan)
    if not allowed:
        raise AppError(403, "PLAN_FORBIDDEN", "无权访问该建设计划")
    return plan


def can_view_task(db: Session, user: SysUser, task: CollegeTask, plan: ConstructionPlan) -> bool:
    if is_school_owner(db, user, plan) or is_plan_leader(db, user, plan):
        return True
    if COLLEGE not in roles(db, user) or task.college_org_id != user.primary_org_id:
        return False
    if task.owner_user_id == user.id:
        return True
    now = utcnow()
    member = db.execute(
        select(ProjectMember.id).where(
            ProjectMember.plan_id == plan.id,
            ProjectMember.user_id == user.id,
            ProjectMember.org_id == task.college_org_id,
            ProjectMember.member_role == COLLEGE,
            ProjectMember.effective_from <= now,
            or_(ProjectMember.effective_to.is_(None), ProjectMember.effective_to > now),
        )
    ).first()
    return member is not None


def get_task(db: Session, user: SysUser, task_id: int, manage_school: bool = False) -> tuple[CollegeTask, OutputIndicator, ConstructionPlan]:
    task = db.get(CollegeTask, task_id)
    if not task:
        raise AppError(404, "TASK_NOT_FOUND", "学院任务不存在")
    indicator = db.get(OutputIndicator, task.indicator_id)
    plan = db.get(ConstructionPlan, indicator.plan_id)
    if manage_school:
        if not is_school_owner(db, user, plan):
            raise AppError(403, "TASK_FORBIDDEN", "仅本项目校级负责人可执行此操作")
    elif not can_view_task(db, user, task, plan):
        raise AppError(403, "TASK_FORBIDDEN", "无权访问该学院任务")
    return task, indicator, plan


def get_milestone(db: Session, user: SysUser, milestone_id: int) -> tuple[TaskAnnualMilestone, CollegeTask, OutputIndicator, ConstructionPlan]:
    milestone = db.get(TaskAnnualMilestone, milestone_id)
    if not milestone:
        raise AppError(404, "MILESTONE_NOT_FOUND", "年度里程碑不存在")
    task, indicator, plan = get_task(db, user, milestone.task_id)
    return milestone, task, indicator, plan


def ensure_college_operator(user: SysUser, task: CollegeTask) -> None:
    if task.owner_user_id != user.id or task.college_org_id != user.primary_org_id:
        raise AppError(403, "COLLEGE_OPERATION_FORBIDDEN", "仅本任务当前学院负责人可执行此操作")


def visible_plan_ids(db: Session, user: SysUser) -> list[int]:
    user_roles = roles(db, user)
    ids: set[int] = set()
    if SCHOOL in user_roles:
        ids.update(db.execute(select(ConstructionPlan.id).where(ConstructionPlan.school_owner_id == user.id)).scalars().all())
    if LEADER in user_roles:
        ids.update(
            db.execute(
                select(ConstructionPlan.id)
                .join(ConstructionDirectory, ConstructionDirectory.id == ConstructionPlan.directory_id)
                .where(ConstructionDirectory.leader_user_id == user.id)
            ).scalars().all()
        )
    if COLLEGE in user_roles:
        now = utcnow()
        ids.update(
            db.execute(
                select(ProjectMember.plan_id).where(
                    ProjectMember.user_id == user.id,
                    ProjectMember.member_role == COLLEGE,
                    ProjectMember.effective_from <= now,
                    or_(ProjectMember.effective_to.is_(None), ProjectMember.effective_to > now),
                )
            ).scalars().all()
        )
    return list(ids)
