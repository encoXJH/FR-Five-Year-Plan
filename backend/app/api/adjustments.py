from fastapi import APIRouter, Depends, File, Form, Header, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import AppError
from app.models import (
    AnnualCarryover,
    AnnualCompensation,
    CollegeTask,
    ConstructionPlan,
    CrossCollegeTransfer,
    OutputIndicator,
    SysOrgUnit,
    SysUser,
    TransferApproval,
)
from app.schemas import CarryoverAction, CarryoverInput, TransferAction, TransferCopyInput, TransferInput, TransferWithdraw
from app.security import active_role_codes, get_current_user
from app.services.adjustments import OPEN_TRANSFERS, act_carryover, act_transfer, create_carryover, create_transfer
from app.services.audit import close_todos, idempotent, write_log
from app.services.scope import COLLEGE, LEADER, SCHOOL, get_plan, get_task, is_plan_leader, is_school_owner, visible_plan_ids
from app.services.storage import protected_storage_path, save_transfer_attachment
from app.utils import check_version, model_dict, utcnow


from app.responses import DecimalJSONResponse

router = APIRouter(tags=["顺延补位与划转"], default_response_class=DecimalJSONResponse)


def _carryover_payload(db: Session, row: AnnualCarryover, detail: bool = False) -> dict:
    result = model_dict(row)
    if detail:
        from app.models import TaskAnnualMilestone
        result["source_milestone"] = model_dict(db.get(TaskAnnualMilestone, row.source_milestone_id))
        result["target_milestone"] = model_dict(db.get(TaskAnnualMilestone, row.target_milestone_id))
    return result


@router.post("/tasks/{task_id}/carryovers", status_code=201)
def initiate_carryover(task_id: int, data: CarryoverInput, idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"), user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    task, indicator, plan = get_task(db, user, task_id, manage_school=True)

    def operation():
        check_version(task, data.expected_version)
        row = create_carryover(db, data, task, indicator, plan, user)
        return model_dict(row), row.id

    result = idempotent(db, user, idempotency_key, "CARRYOVER_CREATE", {"task_id": task_id, "data": data.model_dump(mode="json")}, operation)
    db.commit()
    return result


@router.post("/carryovers/{carryover_id}/action")
def process_carryover(carryover_id: int, data: CarryoverAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(AnnualCarryover, carryover_id)
    if not row:
        raise AppError(404, "CARRYOVER_NOT_FOUND", "顺延单不存在")
    task, indicator, plan = get_task(db, user, row.task_id, manage_school=True)
    check_version(row, data.expected_version)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "顺延审批、退回或撤回需要明确确认")
    row = act_carryover(db, row, data.action, data.reason, user)
    row.lock_version += 1
    db.commit()
    return _carryover_payload(db, row, detail=True)


@router.get("/carryovers")
def list_carryovers(task_id: int | None = None, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    plan_ids = visible_plan_ids(db, user)
    query = select(AnnualCarryover).join(CollegeTask, CollegeTask.id == AnnualCarryover.task_id).join(OutputIndicator, OutputIndicator.id == CollegeTask.indicator_id).where(OutputIndicator.plan_id.in_(plan_ids or [-1]))
    if task_id:
        query = query.where(AnnualCarryover.task_id == task_id)
    if COLLEGE in active_role_codes(db, user.id):
        query = query.where(CollegeTask.college_org_id == user.primary_org_id)
    rows = db.execute(query.order_by(AnnualCarryover.id.desc())).scalars().all()
    return {"items": [_carryover_payload(db, row) for row in rows], "total": len(rows)}


@router.get("/carryovers/{carryover_id}")
def carryover_detail(carryover_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(AnnualCarryover, carryover_id)
    if not row:
        raise AppError(404, "CARRYOVER_NOT_FOUND", "顺延单不存在")
    get_task(db, user, row.task_id)
    return _carryover_payload(db, row, detail=True)


@router.get("/tasks/{task_id}/compensations")
def list_compensations(task_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    get_task(db, user, task_id)
    rows = db.execute(select(AnnualCompensation).where(AnnualCompensation.task_id == task_id).order_by(AnnualCompensation.id)).scalars().all()
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}


def _transfer_scope(db: Session, user: SysUser, row: CrossCollegeTransfer) -> tuple[CollegeTask, OutputIndicator, ConstructionPlan]:
    source = db.get(CollegeTask, row.source_task_id)
    indicator = db.get(OutputIndicator, row.indicator_id)
    plan = db.get(ConstructionPlan, indicator.plan_id)
    user_roles = active_role_codes(db, user.id)
    if is_school_owner(db, user, plan) or is_plan_leader(db, user, plan):
        return source, indicator, plan
    if COLLEGE in user_roles and user.primary_org_id in {source.college_org_id, row.target_college_org_id}:
        return source, indicator, plan
    raise AppError(403, "TRANSFER_FORBIDDEN", "无权查看该划转单")


def _transfer_payload(db: Session, row: CrossCollegeTransfer, user: SysUser, detail: bool = False) -> dict:
    result = model_dict(row)
    source = db.get(CollegeTask, row.source_task_id)
    indicator = db.get(OutputIndicator, row.indicator_id)
    plan = db.get(ConstructionPlan, indicator.plan_id)
    source_org = db.get(SysOrgUnit, source.college_org_id)
    target_org = db.get(SysOrgUnit, row.target_college_org_id)
    result["indicator_name"] = indicator.indicator_name
    result["plan_id"] = plan.id
    result["source_task_code"] = source.task_code
    result["source_college_org_id"] = source.college_org_id
    result["source_college_org_name"] = source_org.org_name if source_org else None
    result["target_college_org_name"] = target_org.org_name if target_org else None
    if COLLEGE in active_role_codes(db, user.id) and not is_school_owner(db, user, plan) and not is_plan_leader(db, user, plan):
        result["before_snapshot_json"] = None
        result["after_snapshot_json"] = None
    if detail:
        approvals = db.execute(select(TransferApproval).where(TransferApproval.transfer_id == row.id).order_by(TransferApproval.id)).scalars().all()
        result["approvals"] = [model_dict(item) for item in approvals]
        result["source_task"] = model_dict(source, exclude={"assigned_snapshot_json"}) if source.college_org_id == user.primary_org_id or is_school_owner(db, user, plan) or is_plan_leader(db, user, plan) else None
        target = db.get(CollegeTask, row.target_task_id) if row.target_task_id else None
        result["target_task"] = model_dict(target, exclude={"assigned_snapshot_json"}) if target and (target.college_org_id == user.primary_org_id or is_school_owner(db, user, plan) or is_plan_leader(db, user, plan)) else None
    return result


@router.post("/transfers/attachments", status_code=201)
async def upload_transfer_attachment(
    file: UploadFile = File(...),
    plan_id: int | None = Form(default=None),
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if SCHOOL not in active_role_codes(db, user.id):
        raise AppError(403, "TRANSFER_ATTACHMENT_FORBIDDEN", "仅校级项目干系人可上传划转说明材料")
    if plan_id is not None:
        get_plan(db, user, plan_id, manage=True)
    result = save_transfer_attachment(db, file, user, plan_id)
    db.commit()
    return result


@router.get("/transfers/{transfer_id}/attachments/{object_key:path}")
def preview_transfer_attachment(
    transfer_id: int,
    object_key: str,
    download: bool = False,
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    row = db.get(CrossCollegeTransfer, transfer_id)
    if not row:
        raise AppError(404, "TRANSFER_NOT_FOUND", "划转单不存在")
    _transfer_scope(db, user, row)
    attachments = row.attachment_json if isinstance(row.attachment_json, list) else []
    attachment = next((item for item in attachments if isinstance(item, dict) and item.get("object_key") == object_key), None)
    if not attachment:
        raise AppError(404, "TRANSFER_ATTACHMENT_NOT_FOUND", "说明材料未登记在该划转单中")
    path = protected_storage_path(object_key)
    if not path.is_file():
        raise AppError(404, "TRANSFER_ATTACHMENT_FILE_MISSING", "说明材料文件不存在")
    from pathlib import Path
    filename = attachment.get("name") or Path(object_key).name
    media_type = attachment.get("mime_type") or "application/octet-stream"
    return FileResponse(path, media_type=media_type, filename=filename, content_disposition_type="attachment" if download else "inline")


@router.post("/indicators/{indicator_id}/transfers", status_code=201)
def initiate_transfer(indicator_id: int, data: TransferInput, idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"), user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    indicator = db.get(OutputIndicator, indicator_id)
    if not indicator:
        raise AppError(404, "INDICATOR_NOT_FOUND", "关键产出指标不存在")
    plan = get_plan(db, user, indicator.plan_id, manage=True)
    source, _indicator, _plan = get_task(db, user, data.source_task_id, manage_school=True)

    def operation():
        check_version(source, data.expected_version)
        row = create_transfer(db, data, source, indicator, plan, user)
        return model_dict(row), row.id

    result = idempotent(db, user, idempotency_key, "TRANSFER_CREATE", {"indicator_id": indicator_id, "data": data.model_dump(mode="json")}, operation)
    db.commit()
    return result


@router.post("/transfers/{transfer_id}/action")
def process_transfer(transfer_id: int, data: TransferAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(CrossCollegeTransfer, transfer_id)
    if not row:
        raise AppError(404, "TRANSFER_NOT_FOUND", "划转单不存在")
    check_version(row, data.expected_version)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "划转确认或终审需要明确确认")
    row = act_transfer(db, row, data.step_code, data.action, data.action_comment, user)
    row.lock_version += 1
    db.commit()
    return _transfer_payload(db, row, user, detail=True)


@router.post("/transfers/{transfer_id}/withdraw")
def withdraw_transfer(transfer_id: int, data: TransferWithdraw, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(CrossCollegeTransfer, transfer_id)
    if not row:
        raise AppError(404, "TRANSFER_NOT_FOUND", "划转单不存在")
    source, indicator, plan = _transfer_scope(db, user, row)
    if not is_school_owner(db, user, plan) or row.initiated_by != user.id:
        raise AppError(403, "TRANSFER_WITHDRAW_FORBIDDEN", "仅划转发起人可撤回")
    check_version(row, data.expected_version)
    if row.transfer_status not in OPEN_TRANSFERS:
        raise AppError(409, "TRANSFER_NOT_WITHDRAWABLE", "已生效或已终止划转不可撤回")
    row.transfer_status = "WITHDRAWN"
    row.withdrawn_by = user.id
    row.withdrawn_at = utcnow()
    row.terminated_reason = data.reason
    row.lock_version += 1
    close_todos(db, "TRANSFER_CONFIRM", row.id, status="CANCELLED")
    close_todos(db, "TRANSFER_APPROVAL", row.id, status="CANCELLED")
    write_log(db, user, object_type="TRANSFER", object_id=row.id, action_code="TRANSFER_WITHDRAW", plan_id=plan.id, reason=data.reason, after={"status": row.transfer_status})
    db.commit()
    return model_dict(row)


@router.post("/transfers/{transfer_id}/copy", status_code=201)
def copy_transfer(transfer_id: int, data: TransferCopyInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    old = db.get(CrossCollegeTransfer, transfer_id)
    if not old:
        raise AppError(404, "TRANSFER_NOT_FOUND", "划转单不存在")
    if old.transfer_status not in {"RETURNED", "WITHDRAWN"}:
        raise AppError(409, "TRANSFER_NOT_COPYABLE", "仅已退回或撤回的单据可复制重发")
    source, indicator, plan = _transfer_scope(db, user, old)
    if not is_school_owner(db, user, plan):
        raise AppError(403, "TRANSFER_COPY_FORBIDDEN", "仅本项目校级负责人可复制重发")
    check_version(old, data.expected_version)
    payload = TransferInput(
        source_task_id=old.source_task_id,
        target_college_org_id=data.target_college_org_id or old.target_college_org_id,
        target_task_id=data.target_task_id if data.target_task_id is not None else old.target_task_id,
        target_owner_user_id=data.target_owner_user_id or old.target_owner_user_id,
        transfer_year=data.transfer_year if data.transfer_year is not None else old.transfer_year,
        transfer_amount=data.transfer_amount or old.transfer_amount,
        reason=data.reason,
        attachment_json=old.attachment_json if isinstance(old.attachment_json, list) else None,
        expected_version=source.lock_version,
    )
    row = create_transfer(db, payload, source, indicator, plan, user)
    row.copied_from_transfer_id = old.id
    db.commit()
    return model_dict(row)


@router.get("/transfers")
def list_transfers(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    plan_ids = visible_plan_ids(db, user)
    query = select(CrossCollegeTransfer).join(OutputIndicator, OutputIndicator.id == CrossCollegeTransfer.indicator_id).where(OutputIndicator.plan_id.in_(plan_ids or [-1]))
    if COLLEGE in active_role_codes(db, user.id):
        query = query.join(CollegeTask, CollegeTask.id == CrossCollegeTransfer.source_task_id).where(or_(CollegeTask.college_org_id == user.primary_org_id, CrossCollegeTransfer.target_college_org_id == user.primary_org_id))
    rows = db.execute(query.order_by(CrossCollegeTransfer.id.desc())).scalars().all()
    return {"items": [_transfer_payload(db, row, user) for row in rows], "total": len(rows)}


@router.get("/transfers/{transfer_id}")
def transfer_detail(transfer_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(CrossCollegeTransfer, transfer_id)
    if not row:
        raise AppError(404, "TRANSFER_NOT_FOUND", "划转单不存在")
    _transfer_scope(db, user, row)
    return _transfer_payload(db, row, user, detail=True)
