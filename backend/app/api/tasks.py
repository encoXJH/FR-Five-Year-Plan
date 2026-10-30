from decimal import Decimal

from fastapi import APIRouter, Depends, Header
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import AppError
from app.models import (
    CollegeTask,
    ConstructionPlan,
    DictIndicatorUnit,
    OutputIndicator,
    SysUser,
    TaskAnnualMilestone,
    TaskObjection,
)
from app.schemas import AssignmentInput, ObjectionInput, ObjectionReplyInput, TaskAction
from app.security import active_role_codes, get_current_user
from app.services.audit import close_todos, idempotent, write_log
from app.services.scope import COLLEGE, ensure_college_operator, get_plan, get_task, is_school_owner, visible_plan_ids
from app.services.tasks import assign_tasks, assignment_preview
from app.utils import check_version, json_value, model_dict, utcnow


from app.responses import DecimalJSONResponse

router = APIRouter(tags=["学院任务"], default_response_class=DecimalJSONResponse)


NUMERIC_METHODS = {"CUMULATIVE", "ANNUAL_QUOTA", "FINAL"}


def _task_progress(milestones: list[TaskAnnualMilestone], calc_method: str | None) -> dict:
    total = len(milestones)
    completed = sum(1 for item in milestones if item.milestone_status == "COMPLETED")
    numeric = [item for item in milestones if item.current_target_value is not None]
    percent = 0.0
    if calc_method in NUMERIC_METHODS and numeric:
        target = sum((item.current_target_value for item in numeric), Decimal("0"))
        actual = sum((item.recognized_value or Decimal("0") for item in numeric), Decimal("0"))
        if target > 0:
            percent = float(actual / target * Decimal("100"))
    elif total:
        percent = completed / total * 100
    return {
        "progress_percent": round(min(max(percent, 0.0), 100.0), 2),
        "milestone_count": total,
        "completed_milestone_count": completed,
    }


def _task_payload(
    db: Session,
    task: CollegeTask,
    detail: bool = False,
    milestones: list[TaskAnnualMilestone] | None = None,
    indicator: OutputIndicator | None = None,
    unit: DictIndicatorUnit | None = None,
) -> dict:
    result = model_dict(task)
    indicator = indicator or db.get(OutputIndicator, task.indicator_id)
    result["indicator_name"] = indicator.indicator_name
    result["plan_id"] = indicator.plan_id
    result["calc_method"] = indicator.calc_method
    result["unit_code"] = indicator.unit_code
    unit = unit or db.execute(select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code == indicator.unit_code)).scalars().first()
    if unit:
        result["value_kind"] = unit.value_kind
        result["unit_name"] = unit.unit_name
    if milestones is None:
        milestones = db.execute(
            select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == task.id).order_by(TaskAnnualMilestone.target_year)
        ).scalars().all()
    result.update(_task_progress(list(milestones), indicator.calc_method))
    if detail:
        result["milestones"] = [model_dict(item) for item in milestones]
        result["indicator"] = model_dict(indicator)
        objections = db.execute(select(TaskObjection).where(TaskObjection.task_id == task.id).order_by(TaskObjection.id)).scalars().all()
        result["objections"] = [model_dict(item) for item in objections]
    return result


@router.post("/indicators/{indicator_id}/assignment-preview")
def preview_assignment(indicator_id: int, data: AssignmentInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    indicator = db.get(OutputIndicator, indicator_id)
    if not indicator:
        raise AppError(404, "INDICATOR_NOT_FOUND", "关键产出指标不存在")
    plan = get_plan(db, user, indicator.plan_id, manage=True)
    return json_value(assignment_preview(db, indicator, plan, data))


@router.post("/indicators/{indicator_id}/assign", status_code=201)
def assign(indicator_id: int, data: AssignmentInput, idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"), user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    indicator = db.get(OutputIndicator, indicator_id)
    if not indicator:
        raise AppError(404, "INDICATOR_NOT_FOUND", "关键产出指标不存在")
    plan = get_plan(db, user, indicator.plan_id, manage=True)

    def operation():
        check_version(indicator, data.expected_version)
        tasks = assign_tasks(db, indicator, plan, data, user)
        indicator.lock_version += 1
        return {"items": [_task_payload(db, task) for task in tasks], "total": len(tasks)}, indicator.id

    result = idempotent(db, user, idempotency_key, "TASK_ASSIGN", {"indicator_id": indicator_id, "data": data.model_dump(mode="json")}, operation)
    db.commit()
    return result


@router.get("/tasks")
def list_tasks(plan_id: int | None = None, indicator_id: int | None = None, task_status: str | None = None, college_org_id: int | None = None, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    plan_ids = visible_plan_ids(db, user)
    query = select(CollegeTask).join(OutputIndicator, OutputIndicator.id == CollegeTask.indicator_id).where(OutputIndicator.plan_id.in_(plan_ids or [-1]))
    if plan_id:
        query = query.where(OutputIndicator.plan_id == plan_id)
    if indicator_id:
        query = query.where(CollegeTask.indicator_id == indicator_id)
    if task_status:
        query = query.where(CollegeTask.task_status == task_status)
    if COLLEGE in active_role_codes(db, user.id):
        query = query.where(CollegeTask.college_org_id == user.primary_org_id, CollegeTask.task_status != "DRAFT")
    if college_org_id:
        query = query.where(CollegeTask.college_org_id == college_org_id)
    rows = db.execute(query.order_by(CollegeTask.id.desc())).scalars().all()
    indicators: dict[int, OutputIndicator] = {}
    units: dict[str, DictIndicatorUnit] = {}
    milestones: dict[int, list[TaskAnnualMilestone]] = {}
    if rows:
        indicator_rows = db.execute(
            select(OutputIndicator).where(OutputIndicator.id.in_({row.indicator_id for row in rows}))
        ).scalars().all()
        indicators = {item.id: item for item in indicator_rows}
        unit_rows = db.execute(
            select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code.in_({item.unit_code for item in indicator_rows}))
        ).scalars().all()
        units = {item.unit_code: item for item in unit_rows}
        milestone_rows = db.execute(
            select(TaskAnnualMilestone)
            .where(TaskAnnualMilestone.task_id.in_([row.id for row in rows]))
            .order_by(TaskAnnualMilestone.target_year)
        ).scalars().all()
        for item in milestone_rows:
            milestones.setdefault(item.task_id, []).append(item)
    return {
        "items": [
            _task_payload(
                db,
                row,
                milestones=milestones.get(row.id, []),
                indicator=indicators.get(row.indicator_id),
                unit=units.get(indicators[row.indicator_id].unit_code) if row.indicator_id in indicators else None,
            )
            for row in rows
        ],
        "total": len(rows),
    }


@router.get("/tasks/{task_id}")
def task_detail(task_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    task, _indicator, _plan = get_task(db, user, task_id)
    if task.task_status == "DRAFT" and not is_school_owner(db, user, _plan):
        raise AppError(403, "TASK_FORBIDDEN", "草稿任务仅校级负责人可见")
    return _task_payload(db, task, detail=True)


@router.post("/tasks/{task_id}/receive")
def receive_task(task_id: int, data: TaskAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    task, indicator, plan = get_task(db, user, task_id)
    ensure_college_operator(user, task, db)
    check_version(task, data.expected_version)
    if task.task_status != "PENDING_RECEIVE":
        raise AppError(409, "TASK_NOT_PENDING_RECEIVE", "仅待接收任务可执行接收")
    task.task_status = "IN_PROGRESS"
    task.received_by = user.id
    task.received_at = utcnow()
    task.lock_version += 1
    close_todos(db, "TASK_ASSIGN", task.id)
    write_log(db, user, object_type="TASK", object_id=task.id, action_code="RECEIVE", plan_id=plan.id, college_org_id=task.college_org_id, after={"task_status": task.task_status})
    db.commit()
    return _task_payload(db, task, detail=True)


@router.post("/tasks/{task_id}/revoke")
def revoke_task(task_id: int, data: TaskAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    task, indicator, plan = get_task(db, user, task_id, manage_school=True)
    check_version(task, data.expected_version)
    if not data.reason:
        raise AppError(422, "REVOKE_REASON_REQUIRED", "撤销下发必须填写原因")
    if task.task_status != "PENDING_RECEIVE" or task.received_at:
        raise AppError(409, "TASK_NOT_REVOKABLE", "任务已接收或已有填报，不可直接撤销")
    task.task_status = "DRAFT"
    task.revoked_by = user.id
    task.revoked_at = utcnow()
    task.revoke_reason = data.reason
    task.lock_version += 1
    close_todos(db, "TASK_ASSIGN", task.id, status="CANCELLED")
    write_log(db, user, object_type="TASK", object_id=task.id, action_code="REVOKE", plan_id=plan.id, college_org_id=task.college_org_id, reason=data.reason, before={"task_status": "PENDING_RECEIVE"}, after={"task_status": "DRAFT"})
    db.commit()
    return _task_payload(db, task, detail=True)


@router.post("/tasks/{task_id}/objections", status_code=201)
def create_objection(task_id: int, data: ObjectionInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    task, indicator, plan = get_task(db, user, task_id)
    ensure_college_operator(user, task, db)
    row = TaskObjection(task_id=task.id, objection_text=data.objection_text, created_by=user.id)
    db.add(row)
    db.flush()
    write_log(db, user, object_type="TASK_OBJECTION", object_id=row.id, action_code="OBJECTION_CREATE", plan_id=plan.id, college_org_id=task.college_org_id, reason=data.objection_text)
    db.commit()
    return model_dict(row)


@router.post("/objections/{objection_id}/reply")
def reply_objection(objection_id: int, data: ObjectionReplyInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(TaskObjection, objection_id)
    if not row:
        raise AppError(404, "OBJECTION_NOT_FOUND", "任务异议不存在")
    task, indicator, plan = get_task(db, user, row.task_id, manage_school=True)
    check_version(row, data.expected_version)
    if row.objection_status != "PENDING":
        raise AppError(409, "OBJECTION_ALREADY_REPLIED", "该异议已处理")
    row.reply_text = data.reply_text
    row.objection_status = data.objection_status
    row.replied_by = user.id
    row.replied_at = utcnow()
    row.lock_version += 1
    write_log(db, user, object_type="TASK_OBJECTION", object_id=row.id, action_code="OBJECTION_REPLY", plan_id=plan.id, college_org_id=task.college_org_id, reason=data.reply_text)
    db.commit()
    return model_dict(row)
