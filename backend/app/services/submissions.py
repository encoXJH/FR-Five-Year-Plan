from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.errors import AppError
from app.models import (
    CollegeTask,
    ConstructionPlan,
    EvidenceMaterial,
    OutputIndicator,
    ReviewRecord,
    TaskAnnualMilestone,
    TaskObjection,
    TaskSubmission,
    SysUser,
)
from app.schemas import ReviewInput, SubmissionInput
from app.services.audit import close_todos, create_todo, write_log
from app.services.calc import latest_effective_review, rebuild_compensation, recalculate_milestone
from app.utils import model_dict, utcnow


def _copy_materials(db: Session, source_id: int, target_id: int, user_id: int) -> None:
    materials = db.execute(
        select(EvidenceMaterial).where(EvidenceMaterial.submission_id == source_id).order_by(EvidenceMaterial.sort_no)
    ).scalars().all()
    for old in materials:
        db.add(
            EvidenceMaterial(
                submission_id=target_id,
                material_name=old.material_name,
                original_file_name=old.original_file_name,
                object_key=old.object_key,
                file_extension=old.file_extension,
                mime_type=old.mime_type,
                file_size_bytes=old.file_size_bytes,
                material_desc=old.material_desc,
                preview_status=old.preview_status,
                preview_error=old.preview_error,
                preview_object_key=old.preview_object_key,
                uploaded_by=user_id,
                uploaded_at=utcnow(),
                sort_no=old.sort_no,
                created_by=user_id,
            )
        )


def create_draft(
    db: Session,
    milestone: TaskAnnualMilestone,
    task: CollegeTask,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    data: SubmissionInput,
    user: SysUser,
) -> TaskSubmission:
    if task.task_status != "IN_PROGRESS":
        raise AppError(409, "TASK_NOT_IN_PROGRESS", "任务接收后才能填报")
    if indicator.calc_method == "EXTERNAL":
        raise AppError(409, "EXTERNAL_NO_SUBMISSION", "外部数据型不生成学院填报")
    current = db.get(TaskSubmission, milestone.current_submission_id) if milestone.current_submission_id else None
    if current and current.submission_status in {"DRAFT", "UNDER_REVIEW", "APPROVED"}:
        raise AppError(409, "CURRENT_SUBMISSION_EXISTS", "当前已有草稿、审核中或已认定版本，不可新建草稿")
    next_version = (db.execute(select(func.max(TaskSubmission.version_no)).where(TaskSubmission.milestone_id == milestone.id)).scalar() or 0) + 1
    submission = TaskSubmission(
        milestone_id=milestone.id,
        version_no=next_version,
        is_current=True,
        reported_value=data.reported_value if data.reported_value is not None else current.reported_value if current else None,
        reported_bool=data.reported_bool if data.reported_bool is not None else current.reported_bool if current else None,
        reported_text=data.reported_text if data.reported_text is not None else current.reported_text if current else None,
        reported_numerator=data.reported_numerator if data.reported_numerator is not None else current.reported_numerator if current else None,
        reported_denominator=data.reported_denominator if data.reported_denominator is not None else current.reported_denominator if current else None,
        completion_desc=data.completion_desc,
        achievement_name=data.achievement_name if data.achievement_name is not None else current.achievement_name if current else None,
        completion_date=data.completion_date,
        created_by=user.id,
    )
    if current:
        current.is_current = False
        db.flush()
    db.add(submission)
    db.flush()
    if current:
        _copy_materials(db, current.id, submission.id, user.id)
    milestone.current_submission_id = submission.id
    milestone.milestone_status = "DRAFT"
    close_todos(db, "REVIEW_RETURNED", milestone.id)
    write_log(
        db,
        user,
        object_type="SUBMISSION",
        object_id=submission.id,
        action_code="DRAFT_CREATE",
        plan_id=plan.id,
        college_org_id=task.college_org_id,
        after={"version_no": submission.version_no},
    )
    return submission


def submit(
    db: Session,
    submission: TaskSubmission,
    milestone: TaskAnnualMilestone,
    task: CollegeTask,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    user: SysUser,
) -> None:
    if submission.submission_status != "DRAFT" or not submission.is_current:
        raise AppError(409, "SUBMISSION_NOT_DRAFT", "仅当前草稿可提交审核")
    if submission.completion_date > datetime.now().date():
        raise AppError(422, "INVALID_COMPLETION_DATE", "完成日期不得晚于提交日期")
    materials = db.execute(select(EvidenceMaterial).where(EvidenceMaterial.submission_id == submission.id)).scalars().all()
    if not materials or not any(item.preview_status == "READY" for item in materials):
        raise AppError(422, "PREVIEWABLE_MATERIAL_REQUIRED", "至少需要一项具备在线预览条件的有效佐证")
    if any(item.preview_status == "PROCESSING" for item in materials):
        raise AppError(422, "MATERIAL_STILL_PROCESSING", "仍有材料正在生成预览，请稍后重试")
    method = indicator.calc_method
    if method == "BOOLEAN" and submission.reported_bool is None:
        raise AppError(422, "REPORTED_VALUE_REQUIRED", "是否型必须填报是或否")
    if method != "BOOLEAN" and submission.reported_value is None and method != "RATIO":
        raise AppError(422, "REPORTED_VALUE_REQUIRED", "必须填写完成数值")
    if method == "RATIO" and (submission.reported_numerator is None or submission.reported_denominator is None):
        raise AppError(422, "RATIO_PARTS_REQUIRED", "比率型必须填写分子和分母")
    if submission.reported_denominator == 0:
        raise AppError(422, "ZERO_DENOMINATOR", "分母为 0 时不能自动核算，请先补充口径")
    now = utcnow()
    submission.submission_status = "UNDER_REVIEW"
    submission.submitted_by = user.id
    submission.submitted_at = now
    milestone.milestone_status = "UNDER_REVIEW"
    create_todo(
        db,
        recipient_user_id=plan.school_owner_id,
        plan_id=plan.id,
        biz_type="REVIEW",
        biz_id=submission.id,
        title=f"待审核 {milestone.target_year} 年填报：{indicator.indicator_name}",
        content_summary=submission.completion_desc,
    )
    write_log(
        db,
        user,
        object_type="SUBMISSION",
        object_id=submission.id,
        action_code="SUBMIT",
        plan_id=plan.id,
        college_org_id=task.college_org_id,
        before={"submission_status": "DRAFT"},
        after={"submission_status": "UNDER_REVIEW", "version_no": submission.version_no},
    )


def withdraw(
    db: Session,
    submission: TaskSubmission,
    milestone: TaskAnnualMilestone,
    task: CollegeTask,
    plan: ConstructionPlan,
    reason: str,
    user: SysUser,
) -> TaskSubmission:
    if submission.submission_status != "UNDER_REVIEW" or not submission.is_current:
        raise AppError(409, "SUBMISSION_NOT_WITHDRAWABLE", "仅尚未形成审核结论的审核中版本可撤回")
    concluded = db.execute(select(ReviewRecord.id).where(ReviewRecord.submission_id == submission.id)).first()
    if concluded:
        raise AppError(409, "REVIEW_ALREADY_CONCLUDED", "审核结论已形成，不能撤回")
    now = utcnow()
    submission.submission_status = "WITHDRAWN"
    submission.withdrawn_by = user.id
    submission.withdrawn_at = now
    submission.withdraw_reason = reason
    submission.is_current = False
    db.flush()
    next_version = (db.execute(select(func.max(TaskSubmission.version_no)).where(TaskSubmission.milestone_id == milestone.id)).scalar() or 0) + 1
    draft = TaskSubmission(
        milestone_id=milestone.id,
        version_no=next_version,
        is_current=True,
        reported_value=submission.reported_value,
        reported_bool=submission.reported_bool,
        reported_text=submission.reported_text,
        reported_numerator=submission.reported_numerator,
        reported_denominator=submission.reported_denominator,
        completion_desc=submission.completion_desc,
        achievement_name=submission.achievement_name,
        completion_date=submission.completion_date,
        created_by=user.id,
    )
    db.add(draft)
    db.flush()
    _copy_materials(db, submission.id, draft.id, user.id)
    milestone.current_submission_id = draft.id
    milestone.milestone_status = "DRAFT"
    close_todos(db, "REVIEW", submission.id, status="CANCELLED")
    write_log(
        db,
        user,
        object_type="SUBMISSION",
        object_id=submission.id,
        action_code="WITHDRAW",
        plan_id=plan.id,
        college_org_id=task.college_org_id,
        reason=reason,
        after={"new_draft_id": draft.id},
    )
    return draft


def _recognized_values(indicator: OutputIndicator, submission: TaskSubmission, data: ReviewInput) -> dict[str, Any]:
    recognized = {
        "value": data.recognized_value if data.recognized_value is not None else submission.reported_value,
        "bool": data.recognized_bool if data.recognized_bool is not None else submission.reported_bool,
        "text": data.recognized_text if data.recognized_text is not None else submission.reported_text,
        "numerator": data.recognized_numerator if data.recognized_numerator is not None else submission.reported_numerator,
        "denominator": data.recognized_denominator if data.recognized_denominator is not None else submission.reported_denominator,
    }
    if indicator.calc_method == "RATIO" and recognized["numerator"] is not None and recognized["denominator"] not in {None, Decimal("0")}:
        recognized["value"] = recognized["numerator"] / recognized["denominator"] * Decimal("100")
    return recognized


def review(
    db: Session,
    submission: TaskSubmission,
    milestone: TaskAnnualMilestone,
    task: CollegeTask,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    data: ReviewInput,
    user: SysUser,
) -> ReviewRecord:
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "审核提交需要二次确认")
    if submission.submission_status != "UNDER_REVIEW" or not submission.is_current:
        raise AppError(409, "SUBMISSION_NOT_UNDER_REVIEW", "该填报版本不处于审核中")
    if db.execute(select(ReviewRecord.id).where(ReviewRecord.submission_id == submission.id)).first():
        raise AppError(409, "REVIEW_ALREADY_CONCLUDED", "该版本已形成审核结论")
    now = utcnow()
    record = ReviewRecord(
        submission_id=submission.id,
        review_result=data.review_result,
        review_comment=data.review_comment,
        reviewer_id=user.id,
        reviewed_at=now,
        created_by=user.id,
    )
    if data.review_result == "RETURNED":
        submission.submission_status = "RETURNED"
        milestone.milestone_status = "RETURNED"
        create_todo(
            db,
            recipient_user_id=task.owner_user_id,
            plan_id=plan.id,
            biz_type="REVIEW_RETURNED",
            biz_id=milestone.id,
            title=f"{milestone.target_year} 年填报已退回：{indicator.indicator_name}",
            content_summary=data.review_comment,
        )
    else:
        recognized = _recognized_values(indicator, submission, data)
        differs = any(
            [
                recognized["value"] != submission.reported_value and indicator.calc_method != "RATIO",
                recognized["bool"] != submission.reported_bool,
                recognized["text"] != submission.reported_text,
                recognized["numerator"] != submission.reported_numerator,
                recognized["denominator"] != submission.reported_denominator,
            ]
        )
        if differs and not data.recognition_basis:
            raise AppError(422, "RECOGNITION_BASIS_REQUIRED", "认定值与学院填报值不同时必须填写认定依据")
        record.recognized_value = recognized["value"]
        record.recognized_bool = recognized["bool"]
        record.recognized_text = recognized["text"]
        record.recognized_numerator = recognized["numerator"]
        record.recognized_denominator = recognized["denominator"]
        record.recognition_basis = data.recognition_basis
        submission.submission_status = "APPROVED"
    db.add(record)
    db.flush()
    milestone.last_review_id = record.id
    close_todos(db, "REVIEW", submission.id)
    if record.review_result == "APPROVED":
        recalculate_milestone(db, milestone, task, indicator, plan, user.id, record)
        initial_result = milestone.milestone_status
        rebuild_compensation(db, task, indicator, plan, user.id, reverse_existing=True)
        record.milestone_result = initial_result if initial_result in {"COMPLETED", "RECOGNIZED_UNMET"} else None
        if initial_result == "RECOGNIZED_UNMET":
            create_todo(
                db,
                recipient_user_id=task.owner_user_id,
                plan_id=plan.id,
                biz_type="UNMET",
                biz_id=milestone.id,
                title=f"{milestone.target_year} 年已认定未达标：{indicator.indicator_name}",
                content_summary=f"缺口：{milestone.shortfall_value}",
            )
            if plan.school_owner_id != task.owner_user_id:
                create_todo(
                    db,
                    recipient_user_id=plan.school_owner_id,
                    plan_id=plan.id,
                    biz_type="UNMET",
                    biz_id=milestone.id,
                    title=f"{milestone.target_year} 年已认定未达标：{indicator.indicator_name}",
                    content_summary=f"缺口：{milestone.shortfall_value}",
                )
    write_log(
        db,
        user,
        object_type="SUBMISSION",
        object_id=submission.id,
        action_code="REVIEW",
        plan_id=plan.id,
        college_org_id=task.college_org_id,
        reason=data.review_comment,
        after=model_dict(record),
        related_order_type="REVIEW",
        related_order_id=record.id,
    )
    _refresh_task_completion(db, task)
    return record


def _refresh_task_completion(db: Session, task: CollegeTask) -> None:
    milestones = db.execute(select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == task.id)).scalars().all()
    if milestones and all(item.milestone_status == "COMPLETED" for item in milestones):
        task.task_status = "COMPLETED"
        task.completed_at = utcnow()
    elif task.task_status == "COMPLETED":
        task.task_status = "IN_PROGRESS"
        task.completed_at = None
