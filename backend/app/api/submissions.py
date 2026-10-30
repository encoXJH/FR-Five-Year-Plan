import hashlib
from decimal import Decimal

from fastapi import APIRouter, Depends, File, Form, Header, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import AppError
from app.models import (
    CollegeTask,
    EvidenceMaterial,
    ExternalRecognition,
    IndicatorAnnualTarget,
    OutputIndicator,
    ReviewRecord,
    SysUser,
    TaskAnnualMilestone,
    TaskSubmission,
)
from app.schemas import CorrectionInput, ExternalRecognitionInput, ReviewInput, SubmissionAction, SubmissionInput, SubmissionUpdate
from app.security import active_role_codes, get_current_user
from app.services.audit import close_todos, idempotent, write_log
from app.services.calc import calculate_external, latest_effective_review, rebuild_compensation, recalculate_milestone
from app.services.scope import COLLEGE, LEADER, SCHOOL, ensure_college_operator, get_milestone, get_plan, get_task, visible_plan_ids
from app.services.storage import delete_material_if_orphaned, generate_preview, preview_file_path, save_material
from app.services.submissions import _refresh_task_completion, create_draft, review, submit, withdraw
from app.utils import check_version, json_value, model_dict, utcnow


from app.responses import DecimalJSONResponse

router = APIRouter(tags=["填报材料与审核"], default_response_class=DecimalJSONResponse)


def _material_payload(material: EvidenceMaterial) -> dict:
    result = model_dict(material, exclude={"object_key", "preview_object_key"})
    result["preview_url"] = f"/api/v1/materials/{material.id}/preview"
    return result


def _submission_payload(db: Session, submission: TaskSubmission, include_history: bool = False) -> dict:
    result = model_dict(submission)
    materials = db.execute(select(EvidenceMaterial).where(EvidenceMaterial.submission_id == submission.id).order_by(EvidenceMaterial.sort_no)).scalars().all()
    result["materials"] = [_material_payload(item) for item in materials]
    reviews = db.execute(select(ReviewRecord).where(ReviewRecord.submission_id == submission.id).order_by(ReviewRecord.id)).scalars().all()
    result["reviews"] = [model_dict(item) for item in reviews]
    return result


def _get_submission_scope(db: Session, user: SysUser, submission_id: int):
    submission = db.get(TaskSubmission, submission_id)
    if not submission:
        raise AppError(404, "SUBMISSION_NOT_FOUND", "填报版本不存在")
    milestone, task, indicator, plan = get_milestone(db, user, submission.milestone_id)
    return submission, milestone, task, indicator, plan


@router.post("/milestones/{milestone_id}/draft", status_code=201)
def new_draft(milestone_id: int, data: SubmissionInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    milestone, task, indicator, plan = get_milestone(db, user, milestone_id)
    ensure_college_operator(user, task, db)
    check_version(milestone, data.expected_version)
    submission = create_draft(db, milestone, task, indicator, plan, data, user)
    milestone.lock_version += 1
    db.commit()
    return _submission_payload(db, submission)


@router.get("/milestones/{milestone_id}/submissions")
def submission_history(milestone_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    milestone, task, indicator, plan = get_milestone(db, user, milestone_id)
    rows = db.execute(select(TaskSubmission).where(TaskSubmission.milestone_id == milestone.id).order_by(TaskSubmission.version_no)).scalars().all()
    return {"items": [_submission_payload(db, row) for row in rows], "total": len(rows)}


@router.get("/submissions/{submission_id}")
def submission_detail(submission_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, submission_id)
    result = _submission_payload(db, submission)
    result["milestone"] = model_dict(milestone)
    result["task"] = model_dict(task)
    result["indicator"] = model_dict(indicator)
    history = db.execute(select(TaskSubmission).where(TaskSubmission.milestone_id == milestone.id).order_by(TaskSubmission.version_no)).scalars().all()
    result["history"] = [_submission_payload(db, row) for row in history]
    return result


@router.patch("/submissions/{submission_id}")
def update_draft(submission_id: int, data: SubmissionUpdate, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, submission_id)
    ensure_college_operator(user, task, db)
    check_version(submission, data.expected_version)
    if submission.submission_status != "DRAFT" or not submission.is_current:
        raise AppError(409, "SUBMISSION_READONLY", "已提交或历史填报版本只读")
    for key, value in data.model_dump(exclude_unset=True, exclude={"expected_version"}).items():
        setattr(submission, key, value)
    submission.lock_version += 1
    db.commit()
    return _submission_payload(db, submission)


@router.post("/submissions/{submission_id}/submit")
def submit_draft(submission_id: int, data: SubmissionAction, idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"), user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, submission_id)
    ensure_college_operator(user, task, db)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "提交审核需要明确确认")

    def operation():
        check_version(submission, data.expected_version)
        submit(db, submission, milestone, task, indicator, plan, user)
        submission.lock_version += 1
        milestone.lock_version += 1
        return _submission_payload(db, submission), submission.id

    result = idempotent(db, user, idempotency_key, "SUBMISSION_SUBMIT", {"submission_id": submission_id, "expected_version": data.expected_version}, operation)
    db.commit()
    return result


@router.post("/submissions/{submission_id}/withdraw")
def withdraw_submission(submission_id: int, data: SubmissionAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, submission_id)
    ensure_college_operator(user, task, db)
    check_version(submission, data.expected_version)
    if not data.reason:
        raise AppError(422, "WITHDRAW_REASON_REQUIRED", "撤回必须填写原因")
    draft = withdraw(db, submission, milestone, task, plan, data.reason, user)
    submission.lock_version += 1
    milestone.lock_version += 1
    db.commit()
    return _submission_payload(db, draft)


@router.post("/submissions/{submission_id}/materials", status_code=201)
async def upload_material(submission_id: int, file: UploadFile = File(...), material_desc: str | None = Form(default=None), idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"), user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, submission_id)
    ensure_college_operator(user, task, db)
    content = await file.read()
    file.file.seek(0)
    file_hash = hashlib.sha256(content).hexdigest()

    def operation():
        material = save_material(db, submission, file, user, material_desc, plan.id, task.college_org_id)
        return _material_payload(material), material.id

    result = idempotent(db, user, idempotency_key, "MATERIAL_UPLOAD", {"submission_id": submission_id, "file_hash": file_hash, "material_desc": material_desc}, operation)
    db.commit()
    return result


@router.delete("/materials/{material_id}")
def delete_material(material_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    material = db.get(EvidenceMaterial, material_id)
    if not material:
        raise AppError(404, "MATERIAL_NOT_FOUND", "材料不存在")
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, material.submission_id)
    ensure_college_operator(user, task, db)
    if submission.submission_status != "DRAFT" or not submission.is_current:
        raise AppError(409, "MATERIAL_VERSION_READONLY", "审核中或历史版本材料不可删除")
    db.delete(material)
    db.flush()
    delete_material_if_orphaned(db, material)
    write_log(db, user, object_type="MATERIAL", object_id=material_id, action_code="DELETE_DRAFT", plan_id=plan.id, college_org_id=task.college_org_id)
    db.commit()
    return {"success": True}


@router.post("/materials/{material_id}/preview-retry")
def retry_preview(material_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    material = db.get(EvidenceMaterial, material_id)
    if not material:
        raise AppError(404, "MATERIAL_NOT_FOUND", "材料不存在")
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, material.submission_id)
    ensure_college_operator(user, task, db)
    if submission.submission_status != "DRAFT":
        raise AppError(409, "MATERIAL_VERSION_READONLY", "历史版本不可重试生成预览")
    material.preview_status = "PROCESSING"
    generate_preview(db, material)
    db.commit()
    return _material_payload(material)


@router.get("/materials/{material_id}/preview")
def preview_material(material_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    material = db.get(EvidenceMaterial, material_id)
    if not material:
        raise AppError(404, "MATERIAL_NOT_FOUND", "材料不存在")
    _get_submission_scope(db, user, material.submission_id)
    if material.preview_status != "READY":
        raise AppError(409, "PREVIEW_NOT_READY", material.preview_error or "材料预览尚未就绪")
    path = preview_file_path(material)
    converted = bool(material.preview_object_key)
    media_type = "application/pdf" if converted else material.mime_type
    filename = f"{material.original_file_name}.pdf" if converted else material.original_file_name
    return FileResponse(path, media_type=media_type, filename=filename, content_disposition_type="inline")


@router.post("/submissions/{submission_id}/review", status_code=201)
def review_submission(submission_id: int, data: ReviewInput, idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"), user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, submission_id)
    get_task(db, user, task.id, manage_school=True)

    def operation():
        check_version(submission, data.expected_version)
        record = review(db, submission, milestone, task, indicator, plan, data, user)
        submission.lock_version += 1
        milestone.lock_version += 1
        return model_dict(record), record.id

    result = idempotent(db, user, idempotency_key, "REVIEW", {"submission_id": submission_id, "data": data.model_dump(mode="json")}, operation)
    db.commit()
    return result


@router.get("/reviews")
def list_pending_reviews(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    plan_ids = visible_plan_ids(db, user)
    query = (
        select(TaskSubmission)
        .join(TaskAnnualMilestone, TaskAnnualMilestone.id == TaskSubmission.milestone_id)
        .join(CollegeTask, CollegeTask.id == TaskAnnualMilestone.task_id)
        .join(OutputIndicator, OutputIndicator.id == CollegeTask.indicator_id)
        .where(TaskSubmission.submission_status == "UNDER_REVIEW", OutputIndicator.plan_id.in_(plan_ids or [-1]))
    )
    user_roles = active_role_codes(db, user.id)
    if COLLEGE in user_roles and not user_roles.intersection({SCHOOL, LEADER}):
        query = query.where(CollegeTask.college_org_id == user.primary_org_id)
    rows = db.execute(query.order_by(TaskSubmission.submitted_at, TaskSubmission.id)).scalars().all()
    items = []
    for submission in rows:
        milestone = db.get(TaskAnnualMilestone, submission.milestone_id)
        task_row = db.get(CollegeTask, milestone.task_id)
        indicator = db.get(OutputIndicator, task_row.indicator_id)
        items.append({**_submission_payload(db, submission), "milestone": model_dict(milestone), "task": model_dict(task_row), "indicator": model_dict(indicator)})
    return {"items": items, "total": len(items)}


@router.post("/reviews/{review_id}/correct", status_code=201)
def correct_review(review_id: int, data: CorrectionInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    old = db.get(ReviewRecord, review_id)
    if not old or old.review_result != "APPROVED":
        raise AppError(404, "APPROVED_REVIEW_NOT_FOUND", "已通过审核记录不存在")
    submission, milestone, task, indicator, plan = _get_submission_scope(db, user, old.submission_id)
    get_task(db, user, task.id, manage_school=True)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "审核更正需要二次确认")
    check_version(submission, data.expected_version)
    latest = latest_effective_review(db, submission.id)
    if not latest or latest.id != old.id:
        raise AppError(409, "REVIEW_NOT_LATEST", "仅最新有效审核结论可更正")
    values = {
        "value": data.recognized_value if data.recognized_value is not None else old.recognized_value,
        "bool": data.recognized_bool if data.recognized_bool is not None else old.recognized_bool,
        "text": data.recognized_text if data.recognized_text is not None else old.recognized_text,
        "numerator": data.recognized_numerator if data.recognized_numerator is not None else old.recognized_numerator,
        "denominator": data.recognized_denominator if data.recognized_denominator is not None else old.recognized_denominator,
    }
    differs = any(values[key] != getattr(old, f"recognized_{key}" if key not in {"numerator", "denominator"} else f"recognized_{key}") for key in values)
    if differs and not data.recognition_basis:
        raise AppError(422, "RECOGNITION_BASIS_REQUIRED", "更正认定值必须填写认定依据")
    if indicator.calc_method == "RATIO" and values["numerator"] is not None and values["denominator"] not in {None, Decimal("0")}:
        values["value"] = values["numerator"] / values["denominator"] * Decimal("100")
    record = ReviewRecord(
        submission_id=submission.id,
        review_result="APPROVED",
        review_comment=data.review_comment,
        recognized_value=values["value"],
        recognized_bool=values["bool"],
        recognized_text=values["text"],
        recognized_numerator=values["numerator"],
        recognized_denominator=values["denominator"],
        recognition_basis=data.recognition_basis,
        reviewer_id=user.id,
        reviewed_at=utcnow(),
        corrects_review_id=old.id,
        correction_reason=data.correction_reason,
        created_by=user.id,
    )
    db.add(record)
    db.flush()
    recalculate_milestone(db, milestone, task, indicator, plan, user.id, record)
    rebuild_compensation(db, task, indicator, plan, user.id, reverse_existing=True)
    record.milestone_result = milestone.milestone_status
    _refresh_task_completion(db, task)
    submission.lock_version += 1
    milestone.lock_version += 1
    write_log(db, user, object_type="REVIEW", object_id=record.id, action_code="REVIEW_CORRECTION", plan_id=plan.id, college_org_id=task.college_org_id, reason=data.correction_reason, before=model_dict(old), after=model_dict(record), related_order_type="REVIEW", related_order_id=record.id)
    db.commit()
    return model_dict(record)


@router.post("/indicators/{indicator_id}/external-recognitions", status_code=201)
def create_external_recognition(indicator_id: int, data: ExternalRecognitionInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    indicator = db.get(OutputIndicator, indicator_id)
    if not indicator:
        raise AppError(404, "INDICATOR_NOT_FOUND", "关键产出指标不存在")
    plan = get_plan(db, user, indicator.plan_id, manage=True)
    if indicator.calc_method != "EXTERNAL":
        raise AppError(409, "NOT_EXTERNAL_INDICATOR", "仅外部数据型指标可由校级录入外部认定")
    if not plan.start_year <= data.target_year <= plan.end_year:
        raise AppError(422, "TARGET_YEAR_INVALID", "认定年度超出计划周期")
    row = ExternalRecognition(indicator_id=indicator.id, **data.model_dump(), recognized_by=user.id, recognized_at=utcnow(), created_by=user.id)
    db.add(row)
    db.flush()
    result = calculate_external(db, indicator, plan, row, user.id)
    write_log(db, user, object_type="EXTERNAL_RECOGNITION", object_id=row.id, action_code="EXTERNAL_RECOGNIZE", plan_id=plan.id, after=model_dict(row))
    db.commit()
    payload = model_dict(row)
    payload["calculation"] = result
    return json_value(payload)


@router.get("/indicators/{indicator_id}/external-recognitions")
def list_external_recognitions(indicator_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    indicator = db.get(OutputIndicator, indicator_id)
    if not indicator:
        raise AppError(404, "INDICATOR_NOT_FOUND", "关键产出指标不存在")
    get_plan(db, user, indicator.plan_id)
    rows = db.execute(select(ExternalRecognition).where(ExternalRecognition.indicator_id == indicator.id).order_by(ExternalRecognition.target_year, ExternalRecognition.id)).scalars().all()
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}
