from datetime import datetime
from decimal import Decimal

from sqlalchemy import JSON, BigInteger, CheckConstraint, DateTime, ForeignKey, Index, Integer, Numeric, SmallInteger, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.base import AuditMixin, CreateMixin, IdMixin, LockMixin


NUMBER = Numeric(18, 4)


class AnnualCarryover(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "annual_carryover"
    __table_args__ = (
        CheckConstraint("carryover_amount > 0", name="ck_carryover_amount_positive"),
        Index("ix_carryover_task_status", "task_id", "carryover_status"),
        Index("ix_carryover_source_status", "source_milestone_id", "carryover_status"),
    )

    carryover_no: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    task_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("college_task.id"), nullable=False)
    source_milestone_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("task_annual_milestone.id"), nullable=False)
    target_milestone_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("task_annual_milestone.id"), nullable=False)
    carryover_amount: Mapped[Decimal] = mapped_column(NUMBER, nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    before_snapshot_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    after_snapshot_json: Mapped[dict | None] = mapped_column(JSON)
    carryover_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING_APPROVAL")
    initiated_by: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    initiated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    approved_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    approved_at: Mapped[datetime | None] = mapped_column(DateTime)
    effective_at: Mapped[datetime | None] = mapped_column(DateTime)


class AnnualCompensation(IdMixin, CreateMixin, Base):
    __tablename__ = "annual_compensation"
    __table_args__ = (
        CheckConstraint("compensation_amount > 0", name="ck_compensation_amount_positive"),
        Index("ix_compensation_task_status", "task_id", "compensation_status"),
        Index("ix_compensation_deficit", "deficit_milestone_id", "compensation_status"),
        Index("ix_compensation_surplus", "surplus_milestone_id", "compensation_status"),
    )

    task_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("college_task.id"), nullable=False)
    deficit_milestone_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("task_annual_milestone.id"), nullable=False)
    surplus_milestone_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("task_annual_milestone.id"), nullable=False)
    source_review_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("review_record.id"), nullable=False)
    compensation_amount: Mapped[Decimal] = mapped_column(NUMBER, nullable=False)
    compensation_status: Mapped[str] = mapped_column(String(20), nullable=False, default="EFFECTIVE")
    reversed_reason: Mapped[str | None] = mapped_column(Text)
    reversed_at: Mapped[datetime | None] = mapped_column(DateTime)


class CrossCollegeTransfer(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "cross_college_transfer"
    __table_args__ = (
        CheckConstraint("transfer_amount > 0", name="ck_transfer_amount_positive"),
        Index("ix_transfer_source_status", "source_task_id", "transfer_status"),
        Index("ix_transfer_indicator_status", "indicator_id", "transfer_status"),
    )

    transfer_no: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    indicator_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("output_indicator.id"), nullable=False)
    source_task_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("college_task.id"), nullable=False)
    target_college_org_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"), nullable=False)
    target_task_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("college_task.id"))
    transfer_year: Mapped[int | None] = mapped_column(SmallInteger)
    transfer_amount: Mapped[Decimal] = mapped_column(NUMBER, nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    attachment_json: Mapped[list | dict | None] = mapped_column(JSON)
    before_snapshot_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    after_snapshot_json: Mapped[dict | None] = mapped_column(JSON)
    transfer_status: Mapped[str] = mapped_column(String(32), nullable=False, default="PENDING_OUT_CONFIRM")
    initiated_by: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    initiated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    target_owner_user_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    withdrawn_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    withdrawn_at: Mapped[datetime | None] = mapped_column(DateTime)
    terminated_reason: Mapped[str | None] = mapped_column(Text)
    approved_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    approved_at: Mapped[datetime | None] = mapped_column(DateTime)
    effective_at: Mapped[datetime | None] = mapped_column(DateTime)
    copied_from_transfer_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("cross_college_transfer.id"))


class TransferApproval(IdMixin, AuditMixin, Base):
    __tablename__ = "transfer_approval"
    __table_args__ = (Index("ix_transfer_approval_step", "transfer_id", "step_code", "step_status"),)

    transfer_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("cross_college_transfer.id"), nullable=False)
    step_code: Mapped[str] = mapped_column(String(32), nullable=False)
    step_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    approver_org_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"))
    approver_user_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    action_comment: Mapped[str | None] = mapped_column(Text)
    acted_at: Mapped[datetime | None] = mapped_column(DateTime)
    notified_at: Mapped[datetime | None] = mapped_column(DateTime)
