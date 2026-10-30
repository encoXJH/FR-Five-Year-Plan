from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import JSON, BigInteger, Boolean, CheckConstraint, Date, DateTime, ForeignKey, Index, Integer, Numeric, SmallInteger, String, Text, UniqueConstraint, text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.base import AuditMixin, CreateMixin, IdMixin, LockMixin


NUMBER = Numeric(18, 4)


class CollegeTask(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "college_task"
    __table_args__ = (
        Index("ix_task_indicator_college", "indicator_id", "college_org_id"),
        Index("ix_task_owner_status", "owner_user_id", "task_status"),
    )

    task_code: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    indicator_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("output_indicator.id"), nullable=False)
    college_org_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"), nullable=False)
    owner_user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    execution_scope: Mapped[str | None] = mapped_column(Text)
    five_year_target_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    five_year_target_bool: Mapped[bool | None] = mapped_column(Boolean)
    five_year_target_text: Mapped[str | None] = mapped_column(String(1000))
    professional_allocation_five_year_raw: Mapped[str | None] = mapped_column(Text)
    source_import_row_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("source_import_row.id"))
    task_status: Mapped[str] = mapped_column(String(24), nullable=False, default="DRAFT")
    assigned_snapshot_json: Mapped[dict | None] = mapped_column(JSON)
    assigned_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    assigned_at: Mapped[datetime | None] = mapped_column(DateTime)
    received_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    received_at: Mapped[datetime | None] = mapped_column(DateTime)
    current_target_version_no: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    revoked_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime)
    revoke_reason: Mapped[str | None] = mapped_column(Text)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime)


class TaskAnnualMilestone(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "task_annual_milestone"
    __table_args__ = (
        UniqueConstraint("task_id", "target_year", name="uq_task_milestone_year"),
        Index("ix_milestone_status_year", "milestone_status", "target_year"),
    )

    task_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("college_task.id"), nullable=False)
    target_year: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    original_target_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    original_target_bool: Mapped[bool | None] = mapped_column(Boolean)
    original_target_text: Mapped[str | None] = mapped_column(String(1000))
    current_target_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    current_target_bool: Mapped[bool | None] = mapped_column(Boolean)
    current_target_text: Mapped[str | None] = mapped_column(String(1000))
    professional_allocation_year_raw: Mapped[str | None] = mapped_column(Text)
    recognized_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    recognized_bool: Mapped[bool | None] = mapped_column(Boolean)
    recognized_text: Mapped[str | None] = mapped_column(String(1000))
    shortfall_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    covered_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    milestone_status: Mapped[str] = mapped_column(String(32), nullable=False, default="NOT_STARTED")
    due_date: Mapped[date | None] = mapped_column(Date)
    current_submission_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("task_submission.id", use_alter=True, name="fk_milestone_current_submission")
    )
    last_review_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("review_record.id", use_alter=True, name="fk_milestone_last_review")
    )
    target_version_no: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    recognized_at: Mapped[datetime | None] = mapped_column(DateTime)
    has_adjustment: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)


class TaskSubmission(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "task_submission"
    __table_args__ = (
        UniqueConstraint("milestone_id", "version_no", name="uq_submission_milestone_version"),
        Index(
            "uq_submission_one_current",
            "milestone_id",
            unique=True,
            sqlite_where=text("is_current = 1"),
            postgresql_where=text("is_current = true"),
        ),
        Index("ix_submission_status", "submission_status"),
    )

    milestone_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("task_annual_milestone.id"), nullable=False)
    version_no: Mapped[int] = mapped_column(Integer, nullable=False)
    is_current: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    reported_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    reported_bool: Mapped[bool | None] = mapped_column(Boolean)
    reported_text: Mapped[str | None] = mapped_column(String(1000))
    reported_numerator: Mapped[Decimal | None] = mapped_column(NUMBER)
    reported_denominator: Mapped[Decimal | None] = mapped_column(NUMBER)
    completion_desc: Mapped[str] = mapped_column(Text, nullable=False)
    achievement_name: Mapped[str | None] = mapped_column(String(500))
    completion_date: Mapped[date] = mapped_column(Date, nullable=False)
    submission_status: Mapped[str] = mapped_column(String(20), nullable=False, default="DRAFT")
    submitted_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    submitted_at: Mapped[datetime | None] = mapped_column(DateTime)
    withdrawn_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    withdrawn_at: Mapped[datetime | None] = mapped_column(DateTime)
    withdraw_reason: Mapped[str | None] = mapped_column(Text)


class EvidenceMaterial(IdMixin, AuditMixin, Base):
    __tablename__ = "evidence_material"
    __table_args__ = (
        Index("ix_material_submission", "submission_id"),
        CheckConstraint("file_size_bytes > 0", name="ck_material_size_positive"),
    )

    submission_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("task_submission.id"), nullable=False)
    material_name: Mapped[str] = mapped_column(String(255), nullable=False)
    original_file_name: Mapped[str] = mapped_column(String(255), nullable=False)
    object_key: Mapped[str] = mapped_column(String(500), nullable=False)
    file_extension: Mapped[str] = mapped_column(String(20), nullable=False)
    mime_type: Mapped[str] = mapped_column(String(100), nullable=False)
    file_size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    material_desc: Mapped[str | None] = mapped_column(String(1000))
    preview_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PROCESSING")
    preview_error: Mapped[str | None] = mapped_column(Text)
    preview_object_key: Mapped[str | None] = mapped_column(String(500))
    uploaded_by: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    uploaded_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    sort_no: Mapped[int] = mapped_column(Integer, nullable=False, default=0)


class ReviewRecord(IdMixin, CreateMixin, Base):
    __tablename__ = "review_record"
    __table_args__ = (
        Index("ix_review_submission_time", "submission_id", "reviewed_at"),
        Index(
            "uq_review_one_initial_conclusion",
            "submission_id",
            unique=True,
            sqlite_where=text("corrects_review_id IS NULL"),
            postgresql_where=text("corrects_review_id IS NULL"),
        ),
        UniqueConstraint("corrects_review_id", name="uq_review_correction_parent"),
    )

    submission_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("task_submission.id"), nullable=False)
    review_result: Mapped[str] = mapped_column(String(20), nullable=False)
    review_comment: Mapped[str] = mapped_column(Text, nullable=False)
    recognized_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    recognized_bool: Mapped[bool | None] = mapped_column(Boolean)
    recognized_text: Mapped[str | None] = mapped_column(String(1000))
    recognized_numerator: Mapped[Decimal | None] = mapped_column(NUMBER)
    recognized_denominator: Mapped[Decimal | None] = mapped_column(NUMBER)
    recognition_basis: Mapped[str | None] = mapped_column(Text)
    milestone_result: Mapped[str | None] = mapped_column(String(24))
    reviewer_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    reviewed_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    corrects_review_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("review_record.id"))
    correction_reason: Mapped[str | None] = mapped_column(Text)


class TaskObjection(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "task_objection"

    task_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("college_task.id"), nullable=False)
    objection_text: Mapped[str] = mapped_column(Text, nullable=False)
    objection_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    reply_text: Mapped[str | None] = mapped_column(Text)
    replied_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    replied_at: Mapped[datetime | None] = mapped_column(DateTime)


class ExternalRecognition(IdMixin, CreateMixin, Base):
    __tablename__ = "external_recognition"
    __table_args__ = (Index("ix_external_recognition_indicator_year", "indicator_id", "target_year", "id"),)

    indicator_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("output_indicator.id"), nullable=False)
    target_year: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    recognized_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    recognized_bool: Mapped[bool | None] = mapped_column(Boolean)
    recognized_text: Mapped[str | None] = mapped_column(String(1000))
    external_source: Mapped[str] = mapped_column(String(500), nullable=False)
    recognition_note: Mapped[str | None] = mapped_column(Text)
    recognized_by: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    recognized_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class CalculationSnapshot(IdMixin, CreateMixin, Base):
    __tablename__ = "calculation_snapshot"
    __table_args__ = (Index("ix_calc_snapshot_scope", "indicator_id", "task_id", "milestone_id", "computed_at"),)

    plan_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("construction_plan.id"), nullable=False)
    indicator_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("output_indicator.id"), nullable=False)
    task_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("college_task.id"))
    milestone_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("task_annual_milestone.id"))
    target_year: Mapped[int | None] = mapped_column(SmallInteger)
    scope_type: Mapped[str] = mapped_column(String(20), nullable=False)
    target_version_no: Mapped[int | None] = mapped_column(Integer)
    original_target_json: Mapped[dict | None] = mapped_column(JSON)
    adjusted_target_json: Mapped[dict | None] = mapped_column(JSON)
    recognized_json: Mapped[dict | None] = mapped_column(JSON)
    formula_text: Mapped[str] = mapped_column(Text, nullable=False)
    calculation_status: Mapped[str] = mapped_column(String(24), nullable=False)
    result_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    computed_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
