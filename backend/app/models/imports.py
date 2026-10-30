from datetime import datetime

from sqlalchemy import JSON, BigInteger, DateTime, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.base import AuditMixin, IdMixin


class SourceImportFile(IdMixin, AuditMixin, Base):
    __tablename__ = "source_import_file"
    __table_args__ = (Index("ix_import_file_plan_hash", "plan_id", "file_hash"),)

    plan_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("construction_plan.id"), nullable=False)
    file_name: Mapped[str] = mapped_column(String(255), nullable=False)
    object_key: Mapped[str] = mapped_column(String(500), nullable=False)
    file_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    sheet_count: Mapped[int] = mapped_column(Integer, nullable=False)
    data_row_count: Mapped[int] = mapped_column(Integer, nullable=False)
    import_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PREVIEW")
    imported_by: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    imported_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)


class SourceImportRow(IdMixin, AuditMixin, Base):
    __tablename__ = "source_import_row"
    __table_args__ = (
        UniqueConstraint("import_file_id", "sheet_name", "row_number", name="uq_import_row_location"),
        Index("ix_import_row_file_status", "import_file_id", "parse_status"),
    )

    import_file_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("source_import_file.id"), nullable=False)
    sheet_name: Mapped[str] = mapped_column(String(100), nullable=False)
    row_number: Mapped[int] = mapped_column(Integer, nullable=False)
    source_seq_raw: Mapped[str | None] = mapped_column(String(50))
    direction_raw: Mapped[str | None] = mapped_column(Text)
    indicator_raw: Mapped[str | None] = mapped_column(Text)
    indicator_type_raw: Mapped[str | None] = mapped_column(String(50))
    indicator_unit_raw: Mapped[str | None] = mapped_column(String(50))
    professional_five_year_raw: Mapped[str | None] = mapped_column(Text)
    professional_2026_raw: Mapped[str | None] = mapped_column(Text)
    professional_five_year_allocation_raw: Mapped[str | None] = mapped_column(Text)
    professional_2026_allocation_raw: Mapped[str | None] = mapped_column(Text)
    school_five_year_raw: Mapped[str | None] = mapped_column(Text)
    school_2026_raw: Mapped[str | None] = mapped_column(Text)
    school_2026_division_raw: Mapped[str | None] = mapped_column(Text)
    school_2027_division_raw: Mapped[str | None] = mapped_column(Text)
    school_2028_division_raw: Mapped[str | None] = mapped_column(Text)
    school_2029_division_raw: Mapped[str | None] = mapped_column(Text)
    school_2030_division_raw: Mapped[str | None] = mapped_column(Text)
    parse_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    error_message: Mapped[str | None] = mapped_column(Text)
    confirmed_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime)
    indicator_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("output_indicator.id"))
    validation_status: Mapped[str] = mapped_column(String(20), nullable=False, default="NEED_CONFIRM")
    validation_json: Mapped[list | dict] = mapped_column(JSON, nullable=False, default=list)
    allocation_candidates_json: Mapped[list | dict] = mapped_column(JSON, nullable=False, default=list)
