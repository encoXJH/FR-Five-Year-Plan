import hashlib
from collections.abc import Callable
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import AppError
from app.models import IdempotencyRecord, OperationLog, SysUser, TodoNotification
from app.security import active_role_codes
from app.utils import dumps, new_code, utcnow


ROLE_PRIORITY = ["PLATFORM_ADMIN", "SCHOOL_OWNER", "SCHOOL_LEADER", "COLLEGE_OWNER"]


def write_log(
    db: Session,
    user: SysUser,
    *,
    object_type: str,
    object_id: int,
    action_code: str,
    plan_id: int | None = None,
    college_org_id: int | None = None,
    object_name: str | None = None,
    reason: str | None = None,
    before: Any = None,
    after: Any = None,
    related_order_type: str | None = None,
    related_order_id: int | None = None,
    export_batch_no: str | None = None,
) -> OperationLog:
    user_roles = active_role_codes(db, user.id)
    role = next((item for item in ROLE_PRIORITY if item in user_roles), "SYSTEM")
    log = OperationLog(
        log_no=new_code("LOG"),
        operator_user_id=user.id,
        operator_role_code=role,
        plan_id=plan_id,
        college_org_id=college_org_id,
        object_type=object_type,
        object_id=object_id,
        object_name=object_name,
        action_code=action_code,
        operation_reason=reason,
        before_snapshot_json=before,
        after_snapshot_json=after,
        related_order_type=related_order_type,
        related_order_id=related_order_id,
        operated_at=utcnow(),
        export_batch_no=export_batch_no,
        created_by=user.id,
    )
    db.add(log)
    return log


def create_todo(
    db: Session,
    *,
    recipient_user_id: int,
    plan_id: int,
    biz_type: str,
    biz_id: int,
    title: str,
    content_summary: str | None = None,
) -> TodoNotification:
    existing = db.execute(
        select(TodoNotification).where(
            TodoNotification.recipient_user_id == recipient_user_id,
            TodoNotification.biz_type == biz_type,
            TodoNotification.biz_id == biz_id,
            TodoNotification.todo_status.in_(["PENDING", "READ"]),
        )
    ).scalars().first()
    if existing:
        return existing
    todo = TodoNotification(
        recipient_user_id=recipient_user_id,
        plan_id=plan_id,
        biz_type=biz_type,
        biz_id=biz_id,
        title=title,
        content_summary=content_summary,
        created_by=recipient_user_id,
    )
    db.add(todo)
    return todo


def close_todos(
    db: Session,
    biz_type: str,
    biz_id: int,
    *,
    status: str = "COMPLETED",
    recipient_user_id: int | None = None,
) -> None:
    query = select(TodoNotification).where(
        TodoNotification.biz_type == biz_type,
        TodoNotification.biz_id == biz_id,
        TodoNotification.todo_status.in_(["PENDING", "READ"]),
    )
    if recipient_user_id is not None:
        query = query.where(TodoNotification.recipient_user_id == recipient_user_id)
    now = utcnow()
    for todo in db.execute(query).scalars().all():
        todo.todo_status = status
        todo.updated_at = now
        if status == "CANCELLED":
            todo.cancelled_at = now
        else:
            todo.completed_at = now


def idempotent(
    db: Session,
    user: SysUser,
    key: str | None,
    resource_type: str,
    payload: Any,
    operation: Callable[[], tuple[dict, int | None]],
) -> dict:
    if not key:
        result, _resource_id = operation()
        return result
    request_hash = hashlib.sha256(dumps({"resource": resource_type, "payload": payload}).encode()).hexdigest()
    record = db.execute(
        select(IdempotencyRecord).where(
            IdempotencyRecord.user_id == user.id,
            IdempotencyRecord.idempotency_key == key,
        )
    ).scalars().first()
    if record:
        if record.request_hash != request_hash or record.resource_type != resource_type:
            raise AppError(409, "IDEMPOTENCY_KEY_REUSED", "幂等键已用于其他请求")
        return record.response_json  # type: ignore[return-value]
    result, resource_id = operation()
    db.add(
        IdempotencyRecord(
            user_id=user.id,
            idempotency_key=key,
            request_hash=request_hash,
            response_json=result,
            resource_type=resource_type,
            resource_id=resource_id,
            created_by=user.id,
        )
    )
    return result
