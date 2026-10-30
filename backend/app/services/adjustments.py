from datetime import date
from decimal import Decimal
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import AppError
from app.models import (
    AnnualCarryover,
    CollegeTask,
    ConstructionPlan,
    CrossCollegeTransfer,
    OutputIndicator,
    SysOrgUnit,
    SysUser,
    TaskAnnualMilestone,
    TaskSubmission,
    TodoNotification,
    TransferApproval,
)
from app.schemas import CarryoverInput, TransferInput
from app.security import active_role_codes
from app.services.audit import close_todos, create_todo, write_log
from app.services.calc import rebuild_compensation, save_calculation_snapshot
from app.services.scope import is_plan_leader
from app.utils import json_value, model_dict, new_code, utcnow


ZERO = Decimal("0")
OPEN_TRANSFERS = ["PENDING_OUT_CONFIRM", "PENDING_IN_CONFIRM", "PENDING_LEADER_APPROVAL"]


def _milestone_snapshot(milestone: TaskAnnualMilestone) -> dict:
    return model_dict(
        milestone,
        exclude={"current_submission_id", "last_review_id", "created_by", "updated_by", "created_at", "updated_at"},
    )


def create_carryover(
    db: Session,
    data: CarryoverInput,
    task: CollegeTask,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    user: SysUser,
) -> AnnualCarryover:
    if indicator.calc_method != "CUMULATIVE":
        raise AppError(422, "CARRYOVER_CUMULATIVE_ONLY", "仅累计型指标支持年度顺延")
    source = db.get(TaskAnnualMilestone, data.source_milestone_id)
    target = db.get(TaskAnnualMilestone, data.target_milestone_id)
    if not source or not target or source.task_id != task.id or target.task_id != task.id:
        raise AppError(422, "CARRYOVER_TASK_MISMATCH", "缺口与接收年度必须属于同一学院任务")
    if target.target_year <= source.target_year or not plan.start_year <= target.target_year <= plan.end_year:
        raise AppError(422, "INVALID_CARRYOVER_YEAR", "接收年度必须是同计划内更晚年度")
    for milestone in (source, target):
        if milestone.current_submission_id:
            submission = db.get(TaskSubmission, milestone.current_submission_id)
            if submission and submission.submission_status == "UNDER_REVIEW":
                raise AppError(409, "MILESTONE_REVIEW_OCCUPIED", "相关年度存在审核中填报，不能顺延")
    pending = db.execute(
        select(AnnualCarryover).where(
            AnnualCarryover.source_milestone_id == source.id,
            AnnualCarryover.carryover_status == "PENDING_APPROVAL",
        )
    ).scalars().first()
    if pending:
        raise AppError(409, "SHORTFALL_OCCUPIED", "同一缺口已有待审批顺延")
    open_transfer = db.execute(
        select(CrossCollegeTransfer).where(
            CrossCollegeTransfer.source_task_id == task.id,
            CrossCollegeTransfer.transfer_status.in_(OPEN_TRANSFERS),
        )
    ).scalars().first()
    if open_transfer:
        raise AppError(409, "TRANSFER_OCCUPIED", "该任务存在进行中划转，不能同时顺延")
    if target.target_year < utcnow().year:
        raise AppError(422, "CARRYOVER_YEAR_ENDED", "接收年度已结束")
    shortfall = max(
        (source.current_target_value or ZERO) - (source.recognized_value or ZERO) - (source.covered_value or ZERO), ZERO
    )
    if data.carryover_amount > shortfall:
        raise AppError(422, "CARRYOVER_EXCEEDS_SHORTFALL", "顺延量不得超过该年度未弥补缺口")
    row = AnnualCarryover(
        carryover_no=new_code("CO"),
        task_id=task.id,
        source_milestone_id=source.id,
        target_milestone_id=target.id,
        carryover_amount=data.carryover_amount,
        reason=data.reason,
        before_snapshot_json=json_value({"source": _milestone_snapshot(source), "target": _milestone_snapshot(target)}),
        carryover_status="PENDING_APPROVAL",
        initiated_by=user.id,
        initiated_at=utcnow(),
        created_by=user.id,
    )
    db.add(row)
    db.flush()
    create_todo(
        db,
        recipient_user_id=plan.school_owner_id,
        plan_id=plan.id,
        biz_type="CARRYOVER_APPROVAL",
        biz_id=row.id,
        title=f"待审批 {source.target_year}→{target.target_year} 年顺延：{indicator.indicator_name}",
        content_summary=data.reason,
    )
    write_log(
        db,
        user,
        object_type="CARRYOVER",
        object_id=row.id,
        action_code="CARRYOVER_INITIATE",
        plan_id=plan.id,
        college_org_id=task.college_org_id,
        reason=data.reason,
        before=row.before_snapshot_json,
        related_order_type="CARRYOVER",
        related_order_id=row.id,
    )
    return row


def act_carryover(
    db: Session,
    row: AnnualCarryover,
    action: str,
    reason: str | None,
    user: SysUser,
) -> AnnualCarryover:
    if row.carryover_status != "PENDING_APPROVAL":
        raise AppError(409, "CARRYOVER_NOT_PENDING", "顺延单不在待审批状态")
    if action == "WITHDRAW":
        if row.initiated_by != user.id:
            raise AppError(403, "CARRYOVER_WITHDRAW_FORBIDDEN", "仅发起人可撤回顺延")
        row.carryover_status = "WITHDRAWN"
        close_todos(db, "CARRYOVER_APPROVAL", row.id, status="CANCELLED")
    elif action == "RETURN":
        if not reason:
            raise AppError(422, "RETURN_REASON_REQUIRED", "退回调用必须填写意见")
        row.carryover_status = "RETURNED"
        close_todos(db, "CARRYOVER_APPROVAL", row.id)
    elif action == "APPROVE":
        source = db.get(TaskAnnualMilestone, row.source_milestone_id)
        target = db.get(TaskAnnualMilestone, row.target_milestone_id)
        task = db.get(CollegeTask, row.task_id)
        indicator = db.get(OutputIndicator, task.indicator_id)
        plan = db.get(ConstructionPlan, indicator.plan_id)
        shortfall = max(
            (source.current_target_value or ZERO) - (source.recognized_value or ZERO) - (source.covered_value or ZERO), ZERO
        )
        if row.carryover_amount > shortfall:
            raise AppError(409, "SHORTFALL_CHANGED", "缺口已变化，当前顺延量超过可顺延缺口")
        for milestone in (source, target):
            if milestone.current_submission_id:
                submission = db.get(TaskSubmission, milestone.current_submission_id)
                if submission and submission.submission_status == "UNDER_REVIEW":
                    raise AppError(409, "MILESTONE_REVIEW_OCCUPIED", "相关年度已出现审核中填报")
        target.current_target_value = (target.current_target_value or ZERO) + row.carryover_amount
        target.target_version_no += 1
        target.has_adjustment = True
        source.has_adjustment = True
        task.current_target_version_no += 1
        now = utcnow()
        row.carryover_status = "EFFECTIVE"
        row.approved_by = user.id
        row.approved_at = now
        row.effective_at = now
        row.after_snapshot_json = json_value({"source": _milestone_snapshot(source), "target": _milestone_snapshot(target)})
        close_todos(db, "CARRYOVER_APPROVAL", row.id)
        close_todos(db, "UNMET", source.id)
        rebuild_compensation(db, task, indicator, plan, user.id, reverse_existing=True)
    write_log(
        db,
        user,
        object_type="CARRYOVER",
        object_id=row.id,
        action_code=f"CARRYOVER_{action}",
        plan_id=db.get(CollegeTask, row.task_id) and db.get(OutputIndicator, db.get(CollegeTask, row.task_id).indicator_id).plan_id,
        reason=reason,
        before=row.before_snapshot_json,
        after=row.after_snapshot_json,
        related_order_type="CARRYOVER",
        related_order_id=row.id,
    )
    return row


def _open_transfer_usage(db: Session, source_task_id: int, year: int | None, exclude_id: int | None = None) -> Decimal:
    query = select(CrossCollegeTransfer).where(
        CrossCollegeTransfer.source_task_id == source_task_id,
        CrossCollegeTransfer.transfer_status.in_(OPEN_TRANSFERS),
        CrossCollegeTransfer.transfer_year == year,
    )
    if exclude_id:
        query = query.where(CrossCollegeTransfer.id != exclude_id)
    return sum((item.transfer_amount for item in db.execute(query).scalars().all()), ZERO)


def _assert_no_review_occupation(db: Session, task_id: int, year: int | None) -> None:
    query = select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == task_id)
    if year is not None:
        query = query.where(TaskAnnualMilestone.target_year == year)
    for milestone in db.execute(query).scalars().all():
        if milestone.current_submission_id:
            submission = db.get(TaskSubmission, milestone.current_submission_id)
            if submission and submission.submission_status == "UNDER_REVIEW":
                raise AppError(409, "TRANSFER_REVIEW_OCCUPIED", f"{milestone.target_year} 年存在审核中填报占用")


def _available_transfer_amount(
    db: Session, task: CollegeTask, year: int | None, exclude_transfer_id: int | None = None
) -> Decimal:
    _assert_no_review_occupation(db, task.id, year)
    pending_carryover = db.execute(
        select(AnnualCarryover).where(
            AnnualCarryover.task_id == task.id,
            AnnualCarryover.carryover_status == "PENDING_APPROVAL",
        )
    ).scalars().first()
    if pending_carryover:
        raise AppError(409, "CARRYOVER_OCCUPIED", "该任务存在待审批顺延，不能同时划转")
    if year is None:
        milestones = db.execute(select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == task.id)).scalars().all()
        recognized = sum((item.recognized_value or ZERO for item in milestones), ZERO)
        return max((task.five_year_target_value or ZERO) - recognized - _open_transfer_usage(db, task.id, None, exclude_transfer_id), ZERO)
    milestone = db.execute(
        select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == task.id, TaskAnnualMilestone.target_year == year)
    ).scalars().first()
    if not milestone:
        raise AppError(422, "TRANSFER_YEAR_NOT_FOUND", "划转年度不在学院任务周期内")
    return max(
        (milestone.current_target_value or ZERO)
        - (milestone.recognized_value or ZERO)
        - _open_transfer_usage(db, task.id, year, exclude_transfer_id),
        ZERO,
    )


def create_transfer(
    db: Session,
    data: TransferInput,
    source: CollegeTask,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    user: SysUser,
) -> CrossCollegeTransfer:
    if source.indicator_id != indicator.id:
        raise AppError(422, "TRANSFER_INDICATOR_MISMATCH", "转出任务不属于当前指标")
    if source.college_org_id == data.target_college_org_id:
        raise AppError(422, "TRANSFER_SAME_COLLEGE", "转入与转出学院不能相同")
    if indicator.calc_method in {"BOOLEAN", "RATIO", "EXTERNAL"}:
        raise AppError(422, "TRANSFER_VALUE_KIND_UNSUPPORTED", "是否、比率或外部数据型不支持数值划转")
    if data.transfer_year is not None and not plan.start_year <= data.transfer_year <= plan.end_year:
        raise AppError(422, "TRANSFER_YEAR_INVALID", "划转年度超出计划周期")
    org = db.get(SysOrgUnit, data.target_college_org_id)
    if not org or org.org_type != "COLLEGE" or org.status != "ENABLED":
        raise AppError(422, "INVALID_TARGET_COLLEGE", "转入学院无效")
    target = db.get(CollegeTask, data.target_task_id) if data.target_task_id else None
    if data.target_task_id and not target:
        raise AppError(422, "TARGET_TASK_NOT_FOUND", "转入任务不存在")
    if target and (target.indicator_id != indicator.id or target.college_org_id != data.target_college_org_id):
        raise AppError(422, "TRANSFER_TASK_MISMATCH", "转入任务必须属于同一指标和指定学院")
    owner_id = target.owner_user_id if target else data.target_owner_user_id
    if not owner_id:
        raise AppError(422, "TARGET_OWNER_REQUIRED", "转入新学院任务时必须指定学院负责人")
    owner = db.get(SysUser, owner_id)
    if not owner or owner.status != "ENABLED" or owner.primary_org_id != data.target_college_org_id or "COLLEGE_OWNER" not in active_role_codes(db, owner.id):
        raise AppError(422, "INVALID_TARGET_OWNER", "转入负责人必须是该学院有效学院项目干系人")
    available = _available_transfer_amount(db, source, data.transfer_year)
    if data.transfer_amount > available:
        raise AppError(422, "TRANSFER_EXCEEDS_AVAILABLE", "划转量超过扣除已认定、审核中及进行中调整后的剩余可调整量", {"available": available})
    row = CrossCollegeTransfer(
        transfer_no=new_code("TR"),
        indicator_id=indicator.id,
        source_task_id=source.id,
        target_college_org_id=data.target_college_org_id,
        target_task_id=target.id if target else None,
        target_owner_user_id=owner_id,
        transfer_year=data.transfer_year,
        transfer_amount=data.transfer_amount,
        reason=data.reason,
        attachment_json=data.attachment_json,
        before_snapshot_json=json_value(
            {
                "source_task": model_dict(source, exclude={"assigned_snapshot_json"}),
                "target_task": model_dict(target, exclude={"assigned_snapshot_json"}) if target else None,
                "available": available,
            }
        ),
        transfer_status="PENDING_OUT_CONFIRM",
        initiated_by=user.id,
        initiated_at=utcnow(),
        created_by=user.id,
    )
    db.add(row)
    db.flush()
    approvals = [
        TransferApproval(transfer_id=row.id, step_code="OUT_COLLEGE", approver_org_id=source.college_org_id, notified_at=utcnow(), created_by=user.id),
        TransferApproval(transfer_id=row.id, step_code="IN_COLLEGE", approver_org_id=data.target_college_org_id, created_by=user.id),
        TransferApproval(transfer_id=row.id, step_code="SCHOOL_LEADER", created_by=user.id),
    ]
    for approval in approvals:
        db.add(approval)
    create_todo(
        db,
        recipient_user_id=source.owner_user_id,
        plan_id=plan.id,
        biz_type="TRANSFER_CONFIRM",
        biz_id=row.id,
        title=f"请确认转出划转：{indicator.indicator_name}",
        content_summary=data.reason,
    )
    write_log(
        db,
        user,
        object_type="TRANSFER",
        object_id=row.id,
        action_code="TRANSFER_INITIATE",
        plan_id=plan.id,
        college_org_id=source.college_org_id,
        reason=data.reason,
        before=row.before_snapshot_json,
        related_order_type="TRANSFER",
        related_order_id=row.id,
    )
    return row


def _step_user_id(db: Session, row: CrossCollegeTransfer, step_code: str) -> int | None:
    if step_code == "OUT_COLLEGE":
        return db.get(CollegeTask, row.source_task_id).owner_user_id
    if step_code == "IN_COLLEGE":
        return db.get(CollegeTask, row.target_task_id).owner_user_id if row.target_task_id else row.target_owner_user_id
    return None


def _next_todo(db: Session, row: CrossCollegeTransfer, step_code: str, plan: ConstructionPlan, indicator: OutputIndicator) -> None:
    if step_code == "SCHOOL_LEADER":
        from app.models import ConstructionDirectory
        directory = db.get(ConstructionDirectory, plan.directory_id)
        recipient = directory.leader_user_id
        biz_type = "TRANSFER_APPROVAL"
    else:
        recipient = _step_user_id(db, row, step_code)
        biz_type = "TRANSFER_CONFIRM"
    if recipient:
        create_todo(
            db,
            recipient_user_id=recipient,
            plan_id=plan.id,
            biz_type=biz_type,
            biz_id=row.id,
            title=f"划转待处理（{step_code}）：{indicator.indicator_name}",
            content_summary=row.reason,
        )


def _apply_transfer(db: Session, row: CrossCollegeTransfer, user: SysUser) -> None:
    source = db.get(CollegeTask, row.source_task_id)
    indicator = db.get(OutputIndicator, row.indicator_id)
    plan = db.get(ConstructionPlan, indicator.plan_id)
    available = _available_transfer_amount(db, source, row.transfer_year, row.id)
    if row.transfer_amount > available:
        raise AppError(409, "TRANSFER_AVAILABLE_CHANGED", "终审时剩余可调整量已变化，划转不能生效")
    target = db.get(CollegeTask, row.target_task_id) if row.target_task_id else None
    if row.transfer_year is None:
        source.five_year_target_value = (source.five_year_target_value or ZERO) - row.transfer_amount
    else:
        source_milestone = db.execute(
            select(TaskAnnualMilestone).where(
                TaskAnnualMilestone.task_id == source.id,
                TaskAnnualMilestone.target_year == row.transfer_year,
            )
        ).scalars().one()
        source_milestone.current_target_value = (source_milestone.current_target_value or ZERO) - row.transfer_amount
        source_milestone.target_version_no += 1
        source_milestone.has_adjustment = True
    source.current_target_version_no += 1
    if not target:
        target = CollegeTask(
            task_code=new_code("TASK"),
            indicator_id=indicator.id,
            college_org_id=row.target_college_org_id,
            owner_user_id=row.target_owner_user_id,
            execution_scope=f"跨院划转 {row.transfer_no}",
            five_year_target_value=None,
            task_status="PENDING_RECEIVE",
            assigned_by=row.initiated_by,
            assigned_at=utcnow(),
            created_by=user.id,
        )
        db.add(target)
        db.flush()
        for year in range(plan.start_year, plan.end_year + 1):
            amount = ZERO if row.transfer_year is not None else None
            db.add(
                TaskAnnualMilestone(
                    task_id=target.id,
                    target_year=year,
                    original_target_value=ZERO if row.transfer_year is not None else None,
                    current_target_value=amount,
                    milestone_status="PENDING_REPORT",
                    due_date=date(year, 12, 31),
                    has_adjustment=row.transfer_year == year,
                    created_by=user.id,
                )
            )
        row.target_task_id = target.id
        db.flush()
        target.assigned_snapshot_json = json_value({"transfer_no": row.transfer_no, "task": model_dict(target, exclude={"assigned_snapshot_json"})})
        create_todo(
            db,
            recipient_user_id=target.owner_user_id,
            plan_id=plan.id,
            biz_type="TASK_ASSIGN",
            biz_id=target.id,
            title=f"请接收跨院划转任务：{indicator.indicator_name}",
        )
    if row.transfer_year is None:
        target.five_year_target_value = (target.five_year_target_value or ZERO) + row.transfer_amount
    else:
        target_milestone = db.execute(
            select(TaskAnnualMilestone).where(
                TaskAnnualMilestone.task_id == target.id,
                TaskAnnualMilestone.target_year == row.transfer_year,
            )
        ).scalars().one()
        target_milestone.current_target_value = (target_milestone.current_target_value or ZERO) + row.transfer_amount
        target_milestone.target_version_no += 1
        target_milestone.has_adjustment = True
    target.current_target_version_no += 1
    now = utcnow()
    row.transfer_status = "EFFECTIVE"
    row.effective_at = now
    row.after_snapshot_json = json_value(
        {
            "source_task": model_dict(source, exclude={"assigned_snapshot_json"}),
            "target_task": model_dict(target, exclude={"assigned_snapshot_json"}),
        }
    )
    for item in (source, target):
        rebuild_compensation(db, item, indicator, plan, user.id, reverse_existing=True)


def act_transfer(
    db: Session,
    row: CrossCollegeTransfer,
    step_code: str,
    action: str,
    comment: str | None,
    user: SysUser,
) -> CrossCollegeTransfer:
    expected = {
        "OUT_COLLEGE": "PENDING_OUT_CONFIRM",
        "IN_COLLEGE": "PENDING_IN_CONFIRM",
        "SCHOOL_LEADER": "PENDING_LEADER_APPROVAL",
    }
    if row.transfer_status != expected[step_code]:
        raise AppError(409, "TRANSFER_STEP_OUT_OF_ORDER", "必须严格按转出学院、转入学院、校领导顺序处理")
    source = db.get(CollegeTask, row.source_task_id)
    indicator = db.get(OutputIndicator, row.indicator_id)
    plan = db.get(ConstructionPlan, indicator.plan_id)
    if step_code == "SCHOOL_LEADER":
        if not is_plan_leader(db, user, plan):
            raise AppError(403, "TRANSFER_LEADER_FORBIDDEN", "仅负责目录的校领导可终审")
    elif _step_user_id(db, row, step_code) != user.id:
        raise AppError(403, "TRANSFER_STEP_FORBIDDEN", "仅当前环节负责人可处理")
    approval = db.execute(
        select(TransferApproval).where(TransferApproval.transfer_id == row.id, TransferApproval.step_code == step_code)
    ).scalars().one()
    now = utcnow()
    approval.approver_user_id = user.id
    approval.acted_at = now
    approval.action_comment = comment
    close_todos(db, "TRANSFER_APPROVAL" if step_code == "SCHOOL_LEADER" else "TRANSFER_CONFIRM", row.id)
    if action == "RETURN":
        if not comment:
            raise AppError(422, "RETURN_COMMENT_REQUIRED", "退回转划必须填写意见")
        approval.step_status = "RETURNED"
        row.transfer_status = "RETURNED"
        row.terminated_reason = comment
        others = db.execute(
            select(TransferApproval).where(TransferApproval.transfer_id == row.id, TransferApproval.step_status == "PENDING")
        ).scalars().all()
        for other in others:
            other.step_status = "SKIPPED"
    else:
        approval.step_status = "APPROVED" if step_code == "SCHOOL_LEADER" else "CONFIRMED"
        if step_code == "OUT_COLLEGE":
            row.transfer_status = "PENDING_IN_CONFIRM"
            _next_todo(db, row, "IN_COLLEGE", plan, indicator)
        elif step_code == "IN_COLLEGE":
            row.transfer_status = "PENDING_LEADER_APPROVAL"
            _next_todo(db, row, "SCHOOL_LEADER", plan, indicator)
        else:
            row.approved_by = user.id
            row.approved_at = now
            _apply_transfer(db, row, user)
    write_log(
        db,
        user,
        object_type="TRANSFER",
        object_id=row.id,
        action_code=f"TRANSFER_{step_code}_{action}",
        plan_id=plan.id,
        reason=comment,
        after={"status": row.transfer_status},
        related_order_type="TRANSFER",
        related_order_id=row.id,
    )
    return row
