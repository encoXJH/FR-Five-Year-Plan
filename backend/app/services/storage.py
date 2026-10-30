import json
import mimetypes
import shutil
import subprocess
import uuid
from pathlib import Path

from fastapi import UploadFile
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.errors import AppError
from app.models import EvidenceMaterial, SystemConfig, SysUser, TaskSubmission
from app.services.audit import write_log
from app.utils import utcnow


DEFAULT_MATERIAL_CONFIG = {
    "document_extensions": ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt"],
    "image_extensions": ["png", "jpg", "jpeg", "gif", "webp"],
    "video_extensions": ["mp4", "webm", "mov"],
    "document_image_max_bytes": 50 * 1024 * 1024,
    "video_max_bytes": 500 * 1024 * 1024,
    "max_files_per_submission": 10,
    "max_video_duration_seconds": 1800,
}
OFFICE_EXTENSIONS = {"doc", "docx", "xls", "xlsx", "ppt", "pptx"}
NATIVE_EXTENSIONS = {"pdf", "png", "jpg", "jpeg", "gif", "webp", "mp4", "webm", "mov", "txt"}


def material_config(db: Session) -> dict:
    row = db.execute(select(SystemConfig).where(SystemConfig.config_key == "material_policy")).scalars().first()
    if not row:
        return DEFAULT_MATERIAL_CONFIG.copy()
    return {**DEFAULT_MATERIAL_CONFIG, **dict(row.config_value)}


def _storage_path(object_key: str) -> Path:
    settings = get_settings()
    root = settings.storage_dir.resolve()
    path = (root / object_key).resolve()
    if root not in path.parents:
        raise AppError(400, "INVALID_OBJECT_KEY", "文件标识无效")
    return path


def protected_storage_path(object_key: str) -> Path:
    return _storage_path(object_key)


def save_transfer_attachment(db: Session, upload: UploadFile, user: SysUser, plan_id: int | None) -> dict:
    config = material_config(db)
    original_name = Path(upload.filename or "").name
    extension = Path(original_name).suffix.lower().lstrip(".")
    allowed = set(config["document_extensions"] + config["image_extensions"] + config["video_extensions"])
    if not extension or extension not in allowed:
        raise AppError(422, "ATTACHMENT_FORMAT_DISABLED", "划转说明材料格式不支持或已被管理员停用")
    max_size = config["video_max_bytes"] if extension in config["video_extensions"] else config["document_image_max_bytes"]
    object_key = f"transfer_attachments/{uuid.uuid4().hex}.{extension}"
    path = _storage_path(object_key)
    size = 0
    try:
        with path.open("wb") as target:
            while chunk := upload.file.read(1024 * 1024):
                size += len(chunk)
                if size > max_size:
                    raise AppError(422, "ATTACHMENT_SIZE_LIMIT", "划转说明材料大小超过管理员配置的上限")
                target.write(chunk)
        if size == 0:
            raise AppError(422, "EMPTY_ATTACHMENT", "不能上传空文件")
        result = {
            "name": original_name,
            "object_key": object_key,
            "file_extension": extension,
            "mime_type": upload.content_type or mimetypes.guess_type(original_name)[0] or "application/octet-stream",
            "file_size_bytes": size,
        }
        write_log(
            db,
            user,
            object_type="TRANSFER_ATTACHMENT",
            object_id=0,
            object_name=original_name,
            action_code="TRANSFER_ATTACHMENT_UPLOAD",
            plan_id=plan_id,
            after={"file_extension": extension, "file_size_bytes": size},
        )
        return result
    except Exception:
        if path.exists():
            path.unlink()
        raise


def preview_file_path(material: EvidenceMaterial) -> Path:
    key = material.preview_object_key or material.object_key
    path = _storage_path(key)
    if not path.is_file():
        raise AppError(404, "PREVIEW_FILE_MISSING", "预览文件不存在，请重新上传材料")
    return path


def _convert_office_to_pdf(db: Session, material: EvidenceMaterial) -> None:
    settings = get_settings()
    config_row = db.execute(select(SystemConfig).where(SystemConfig.config_key == "libreoffice")).scalars().first()
    configured = config_row.config_value.get("path") if config_row and isinstance(config_row.config_value, dict) else None
    executable = configured or settings.libreoffice_path or shutil.which("soffice") or shutil.which("libreoffice")
    if not executable:
        material.preview_status = "FAILED"
        material.preview_error = "未配置 LibreOffice，无法生成 Office 在线预览；请配置后重试或改传 PDF"
        return
    source = _storage_path(material.object_key)
    output_name = f"{uuid.uuid4().hex}.pdf"
    output_key = f"previews/{output_name}"
    output = _storage_path(output_key)
    try:
        result = subprocess.run(
            [str(executable), "--headless", "--convert-to", "pdf", "--outdir", str(output.parent), str(source)],
            capture_output=True,
            timeout=settings.libreoffice_timeout_seconds,
            check=False,
        )
        generated = output.parent / f"{source.stem}.pdf"
        if result.returncode != 0 or not generated.is_file():
            material.preview_status = "FAILED"
            material.preview_error = "LibreOffice 转换失败，无法生成 Office 在线预览；请检查文件后重试或改传 PDF"
            return
        generated.replace(output)
        material.preview_object_key = output_key
        material.preview_status = "READY"
        material.preview_error = None
        if material.file_extension in {"doc", "docx", "xls", "xlsx", "ppt", "pptx"}:
            material.mime_type = material.mime_type
    except (OSError, subprocess.TimeoutExpired):
        material.preview_status = "FAILED"
        material.preview_error = "LibreOffice 不可用或转换超时，无法生成 Office 在线预览；请配置后重试或改传 PDF"


def generate_preview(db: Session, material: EvidenceMaterial) -> None:
    if material.file_extension in OFFICE_EXTENSIONS:
        _convert_office_to_pdf(db, material)
    elif material.file_extension in NATIVE_EXTENSIONS:
        material.preview_status = "READY"
        material.preview_error = None
    else:
        material.preview_status = "FAILED"
        material.preview_error = "该格式不支持在线预览"


def save_material(
    db: Session,
    submission: TaskSubmission,
    upload: UploadFile,
    user: SysUser,
    material_desc: str | None,
    plan_id: int,
    college_org_id: int,
) -> EvidenceMaterial:
    if submission.submission_status != "DRAFT" or not submission.is_current:
        raise AppError(409, "MATERIAL_VERSION_READONLY", "仅当前草稿可上传材料，已提交版本及其材料只读")
    config = material_config(db)
    original_name = Path(upload.filename or "").name
    extension = Path(original_name).suffix.lower().lstrip(".")
    allowed = set(config["document_extensions"] + config["image_extensions"] + config["video_extensions"])
    if not extension or extension not in allowed:
        raise AppError(422, "MATERIAL_FORMAT_DISABLED", "材料格式不支持或已被管理员停用")
    count = db.execute(
        select(func.count(EvidenceMaterial.id)).where(EvidenceMaterial.submission_id == submission.id)
    ).scalar() or 0
    if count >= config["max_files_per_submission"]:
        raise AppError(422, "MATERIAL_COUNT_LIMIT", "单次填报材料数量已达上限")
    max_size = config["video_max_bytes"] if extension in config["video_extensions"] else config["document_image_max_bytes"]
    object_key = f"evidence/{uuid.uuid4().hex}.{extension}"
    path = _storage_path(object_key)
    size = 0
    try:
        with path.open("wb") as target:
            while chunk := upload.file.read(1024 * 1024):
                size += len(chunk)
                if size > max_size:
                    raise AppError(422, "MATERIAL_SIZE_LIMIT", "材料大小超过管理员配置的上限")
                target.write(chunk)
        if size == 0:
            raise AppError(422, "EMPTY_MATERIAL", "不能上传空文件")
        mime_type = upload.content_type or mimetypes.guess_type(original_name)[0] or "application/octet-stream"
        material = EvidenceMaterial(
            submission_id=submission.id,
            material_name=original_name,
            original_file_name=original_name,
            object_key=object_key,
            file_extension=extension,
            mime_type=mime_type,
            file_size_bytes=size,
            material_desc=material_desc,
            preview_status="PROCESSING",
            uploaded_by=user.id,
            uploaded_at=utcnow(),
            sort_no=count + 1,
            created_by=user.id,
        )
        db.add(material)
        db.flush()
        generate_preview(db, material)
        write_log(
            db,
            user,
            object_type="MATERIAL",
            object_id=material.id,
            object_name=original_name,
            action_code="UPLOAD",
            plan_id=plan_id,
            college_org_id=college_org_id,
            after={"preview_status": material.preview_status, "size": size},
        )
        return material
    except Exception:
        if path.exists():
            path.unlink()
        raise


def delete_material_if_orphaned(db: Session, material: EvidenceMaterial) -> None:
    references = db.execute(
        select(func.count(EvidenceMaterial.id)).where(EvidenceMaterial.object_key == material.object_key)
    ).scalar() or 0
    if references == 0:
        path = _storage_path(material.object_key)
        if path.exists():
            path.unlink()
    if material.preview_object_key:
        preview_references = db.execute(
            select(func.count(EvidenceMaterial.id)).where(EvidenceMaterial.preview_object_key == material.preview_object_key)
        ).scalar() or 0
        if preview_references == 0:
            preview = _storage_path(material.preview_object_key)
            if preview.exists():
                preview.unlink()
