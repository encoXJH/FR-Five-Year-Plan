from collections import Counter

from fastapi import APIRouter, Depends, File, Form, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import AppError
from app.models import ConstructionPlan, SourceImportFile, SourceImportRow, SysUser
from app.schemas import ImportConfirmInput
from app.security import get_current_user
from app.services.imports import SOURCE_FIELDS, confirm_import_row, import_excel, refresh_import_status
from app.services.planning import auto_publish_plan
from app.services.scope import get_plan, visible_plan_ids
from app.utils import model_dict


from app.responses import DecimalJSONResponse

router = APIRouter(prefix="/imports", tags=["Excel 导入"], default_response_class=DecimalJSONResponse)


def _batch(db: Session, batch_id: int) -> SourceImportFile:
    row = db.get(SourceImportFile, batch_id)
    if not row:
        raise AppError(404, "IMPORT_NOT_FOUND", "导入批次不存在")
    return row


def _validation_summary(db: Session, batch_id: int) -> dict[str, int]:
    rows = db.execute(select(SourceImportRow.validation_status).where(SourceImportRow.import_file_id == batch_id)).scalars().all()
    return dict(Counter(rows))


def _batch_payload(db: Session, batch: SourceImportFile, include_rows: bool = True) -> dict:
    result = model_dict(batch)
    plan = db.get(ConstructionPlan, batch.plan_id)
    result["plan_name"] = plan.plan_name if plan else None
    result["fields"] = SOURCE_FIELDS
    result["validation_summary"] = _validation_summary(db, batch.id)
    if include_rows:
        rows = db.execute(select(SourceImportRow).where(SourceImportRow.import_file_id == batch.id).order_by(SourceImportRow.sheet_name, SourceImportRow.row_number)).scalars().all()
        result["items"] = [model_dict(row) for row in rows]
        result["sheets"] = sorted({row.sheet_name for row in rows})
        result["total"] = len(rows)
    return result


@router.get("")
def list_imports(
    plan_id: int | None = None,
    import_status: str | None = None,
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    plan_ids = visible_plan_ids(db, user)
    if plan_id is not None:
        get_plan(db, user, plan_id)
    query = select(SourceImportFile).where(SourceImportFile.plan_id.in_(plan_ids or [-1]))
    if plan_id is not None:
        query = query.where(SourceImportFile.plan_id == plan_id)
    if import_status:
        query = query.where(SourceImportFile.import_status == import_status)
    rows = db.execute(query.order_by(SourceImportFile.id.desc())).scalars().all()
    return {"items": [_batch_payload(db, row, include_rows=False) for row in rows], "total": len(rows)}


@router.post("/preview", status_code=201)
async def preview_import(
    plan_id: int = Form(...),
    duplicate_mode: str = Form("SKIP"),
    file: UploadFile = File(...),
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if duplicate_mode not in {"SKIP", "RECREATE"}:
        raise AppError(422, "DUPLICATE_MODE_INVALID", "重复处理方式必须为 SKIP 或 RECREATE")
    plan = get_plan(db, user, plan_id, manage=True)
    content = await file.read()
    batch, duplicate = import_excel(db, plan=plan, user=user, file_name=file.filename or "source.xlsx", content=content, duplicate_mode=duplicate_mode)
    db.commit()
    result = _batch_payload(db, batch)
    result["duplicate"] = duplicate
    result["skipped"] = duplicate
    return result


@router.get("/{batch_id}")
def import_detail(batch_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    batch = _batch(db, batch_id)
    get_plan(db, user, batch.plan_id)
    return _batch_payload(db, batch)


@router.post("/rows/{row_id}/confirm")
def confirm_row(row_id: int, data: ImportConfirmInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(SourceImportRow, row_id)
    if not row:
        raise AppError(404, "IMPORT_ROW_NOT_FOUND", "导入行不存在")
    batch = _batch(db, row.import_file_id)
    plan = get_plan(db, user, batch.plan_id, manage=True)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "请明确确认该源行及异常处理结果")
    indicator = confirm_import_row(db, row=row, plan=plan, user=user, regenerate=data.regenerate, overrides=data.overrides)
    refresh_import_status(db, batch)
    db.commit()
    return model_dict(indicator)


@router.post("/{batch_id}/confirm-all")
def confirm_all(batch_id: int, data: ImportConfirmInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    batch = _batch(db, batch_id)
    plan = get_plan(db, user, batch.plan_id, manage=True)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "请明确确认批量导入")
    rows = db.execute(select(SourceImportRow).where(SourceImportRow.import_file_id == batch.id).order_by(SourceImportRow.id)).scalars().all()
    created = []
    for row in rows:
        if row.indicator_id and not data.regenerate:
            continue
        indicator = confirm_import_row(db, row=row, plan=plan, user=user, regenerate=data.regenerate, overrides=data.overrides)
        created.append(indicator.id)
    refresh_import_status(db, batch)
    published_count = auto_publish_plan(db, plan, user) if data.auto_publish else 0
    db.commit()
    return {
        "batch_id": batch.id,
        "confirmed_count": len(created),
        "indicator_ids": created,
        "import_status": batch.import_status,
        "auto_published": bool(data.auto_publish),
        "published_indicator_count": published_count,
        "plan_status": plan.plan_status,
    }
