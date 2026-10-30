from datetime import datetime

from sqlalchemy import JSON, BigInteger, DateTime, ForeignKey, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.base import AuditMixin, CreateMixin, IdMixin, LockMixin


class OperationLog(IdMixin, CreateMixin, Base):
    __tablename__ = "operation_log"
    __table_args__ = (
        Index("ix_operation_plan_time", "plan_id", "operated_at"),
        Index("ix_operation_object", "object_type", "object_id"),
        Index("ix_operation_operator_time", "operator_user_id", "operated_at"),
        Index("ix_operation_college_time", "college_org_id", "operated_at"),
    )

    log_no: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    operator_user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    operator_role_code: Mapped[str] = mapped_column(String(40), nullable=False)
    plan_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("construction_plan.id"))
    college_org_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"))
    object_type: Mapped[str] = mapped_column(String(40), nullable=False)
    object_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    object_name: Mapped[str | None] = mapped_column(String(1000))
    action_code: Mapped[str] = mapped_column(String(40), nullable=False)
    operation_reason: Mapped[str | None] = mapped_column(Text)
    before_snapshot_json: Mapped[dict | list | None] = mapped_column(JSON)
    after_snapshot_json: Mapped[dict | list | None] = mapped_column(JSON)
    related_order_type: Mapped[str | None] = mapped_column(String(40))
    related_order_id: Mapped[int | None] = mapped_column(BigInteger)
    operated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    export_batch_no: Mapped[str | None] = mapped_column(String(64))


class TodoNotification(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "todo_notification"
    __table_args__ = (
        Index("ix_todo_recipient_status", "recipient_user_id", "todo_status"),
        Index("ix_todo_biz", "biz_type", "biz_id", "todo_status"),
    )

    recipient_user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    plan_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("construction_plan.id"), nullable=False)
    biz_type: Mapped[str] = mapped_column(String(40), nullable=False)
    biz_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    content_summary: Mapped[str | None] = mapped_column(String(1000))
    todo_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    read_at: Mapped[datetime | None] = mapped_column(DateTime)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime)
    cancelled_at: Mapped[datetime | None] = mapped_column(DateTime)
