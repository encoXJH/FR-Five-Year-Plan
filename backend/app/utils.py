import json
import uuid
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Any

from sqlalchemy.orm import Session

from app.errors import AppError


def utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def new_code(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:20].upper()}"


def json_value(value: Any) -> Any:
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_value(item) for item in value]
    return value


def dumps(value: Any) -> str:
    return json.dumps(json_value(value), ensure_ascii=False, separators=(",", ":"))


def model_dict(obj: Any, exclude: set[str] | None = None) -> dict[str, Any]:
    exclude = exclude or set()
    return {
        column.key: json_value(getattr(obj, column.key))
        for column in obj.__mapper__.columns
        if column.key not in exclude
    }


def check_version(obj: Any, expected_version: int | None) -> None:
    if expected_version is not None and getattr(obj, "lock_version", None) != expected_version:
        raise AppError(409, "OPTIMISTIC_LOCK_CONFLICT", "数据已被其他操作修改，请刷新后重试")


def bump_version(obj: Any) -> None:
    if hasattr(obj, "lock_version"):
        obj.lock_version += 1


def commit(db: Session) -> None:
    db.commit()


def decimal_or_none(value: Any) -> Decimal | None:
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value))
    except Exception as exc:
        raise AppError(422, "INVALID_DECIMAL", "数值格式不正确") from exc
