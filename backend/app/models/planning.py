from datetime import datetime
from decimal import Decimal

from sqlalchemy import BigInteger, Boolean, CheckConstraint, DateTime, ForeignKey, Index, Integer, Numeric, SmallInteger, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.base import AuditMixin, IdMixin, LockMixin


NUMBER = Numeric(18, 4)


class ConstructionDirectory(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "construction_directory"

    directory_code: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    directory_name: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    leader_user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="DRAFT")
    sort_no: Mapped[int] = mapped_column(Integer, nullable=False, default=0)


class ConstructionPlan(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "construction_plan"
    __table_args__ = (
        CheckConstraint("end_year >= start_year", name="ck_plan_year_range"),
        Index("ix_plan_directory_status", "directory_id", "plan_status"),
    )

    plan_code: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    directory_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("construction_directory.id"), nullable=False)
    plan_name: Mapped[str] = mapped_column(String(200), nullable=False)
    construction_scope: Mapped[str] = mapped_column(Text, nullable=False)
    start_year: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    end_year: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    school_owner_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    template_source_plan_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("construction_plan.id"))
    copy_mode: Mapped[str | None] = mapped_column(String(32))
    plan_status: Mapped[str] = mapped_column(String(20), nullable=False, default="DRAFT")
    published_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    published_at: Mapped[datetime | None] = mapped_column(DateTime)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime)
    current_version_no: Mapped[int] = mapped_column(Integer, nullable=False, default=1)


class ConstructionDirection(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "construction_direction"
    __table_args__ = (
        UniqueConstraint("plan_id", "direction_code", name="uq_direction_plan_code"),
        Index("ix_direction_plan_sort", "plan_id", "sort_no"),
    )

    plan_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("construction_plan.id"), nullable=False)
    direction_code: Mapped[str] = mapped_column(String(64), nullable=False)
    direction_name: Mapped[str] = mapped_column(String(500), nullable=False)
    source_seq: Mapped[int | None] = mapped_column(Integer)
    description: Mapped[str | None] = mapped_column(Text)
    sort_no: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="ENABLED")


class OutputIndicator(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "output_indicator"
    __table_args__ = (
        UniqueConstraint("plan_id", "indicator_code", name="uq_indicator_plan_code"),
        Index("ix_indicator_direction", "direction_id"),
        Index("ix_indicator_type", "indicator_type_code"),
        Index("ix_indicator_unit", "unit_code"),
        Index("ix_indicator_parse_status", "parse_status"),
    )

    plan_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("construction_plan.id"), nullable=False)
    direction_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("construction_direction.id"), nullable=False)
    indicator_code: Mapped[str] = mapped_column(String(64), nullable=False)
    indicator_name: Mapped[str] = mapped_column(String(1000), nullable=False)
    indicator_desc: Mapped[str] = mapped_column(Text, nullable=False)
    indicator_type_code: Mapped[str] = mapped_column(String(32), ForeignKey("dict_indicator_type.type_code"), nullable=False)
    unit_code: Mapped[str] = mapped_column(String(40), ForeignKey("dict_indicator_unit.unit_code"), nullable=False)
    standard_unit_code: Mapped[str | None] = mapped_column(String(40), ForeignKey("dict_indicator_unit.unit_code"))
    unit_raw: Mapped[str] = mapped_column(String(40), nullable=False)
    target_direction: Mapped[str | None] = mapped_column(String(10))
    calc_method: Mapped[str | None] = mapped_column(String(32))
    statistical_rule: Mapped[str | None] = mapped_column(Text)
    professional_five_year_target_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    professional_five_year_target_bool: Mapped[bool | None] = mapped_column(Boolean)
    professional_five_year_target_text: Mapped[str | None] = mapped_column(String(1000))
    professional_five_year_target_raw: Mapped[str | None] = mapped_column(Text)
    school_five_year_target_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    school_five_year_target_bool: Mapped[bool | None] = mapped_column(Boolean)
    school_five_year_target_text: Mapped[str | None] = mapped_column(String(1000))
    school_five_year_target_raw: Mapped[str | None] = mapped_column(Text)
    baseline_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    baseline_year: Mapped[int | None] = mapped_column(SmallInteger)
    numerator_rule: Mapped[str | None] = mapped_column(Text)
    denominator_rule: Mapped[str | None] = mapped_column(Text)
    decimal_precision: Mapped[int | None] = mapped_column(Integer)
    external_data_source: Mapped[str | None] = mapped_column(String(500))
    source_import_row_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("source_import_row.id"))
    parse_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    confirmed_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime)
    indicator_status: Mapped[str] = mapped_column(String(20), nullable=False, default="DRAFT")
    target_version_no: Mapped[int] = mapped_column(Integer, nullable=False, default=1)


class IndicatorDepartmentRel(IdMixin, AuditMixin, Base):
    __tablename__ = "indicator_department_rel"
    __table_args__ = (UniqueConstraint("indicator_id", "org_id", "relation_type", name="uq_indicator_department_relation"),)

    indicator_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("output_indicator.id"), nullable=False)
    org_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"), nullable=False)
    relation_type: Mapped[str] = mapped_column(String(20), nullable=False)
    responsibility_desc: Mapped[str | None] = mapped_column(Text)


class IndicatorAnnualTarget(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "indicator_annual_target"
    __table_args__ = (
        UniqueConstraint("indicator_id", "target_year", name="uq_indicator_target_year"),
        Index("ix_indicator_target_year", "target_year"),
    )

    indicator_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("output_indicator.id"), nullable=False)
    target_year: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    professional_target_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    professional_target_bool: Mapped[bool | None] = mapped_column(Boolean)
    professional_target_text: Mapped[str | None] = mapped_column(String(1000))
    professional_target_raw: Mapped[str | None] = mapped_column(Text)
    school_target_value: Mapped[Decimal | None] = mapped_column(NUMBER)
    school_target_bool: Mapped[bool | None] = mapped_column(Boolean)
    school_target_text: Mapped[str | None] = mapped_column(String(1000))
    school_target_raw: Mapped[str | None] = mapped_column(Text)
    school_division_raw: Mapped[str | None] = mapped_column(Text)
    target_version_no: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    confirm_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    confirmed_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime)
