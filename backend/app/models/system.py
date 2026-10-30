from datetime import datetime
from decimal import Decimal

from sqlalchemy import JSON, BigInteger, Boolean, DateTime, ForeignKey, Index, Integer, Numeric, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.base import AuditMixin, CreateMixin, IdMixin, LockMixin


class DictIndicatorType(IdMixin, AuditMixin, Base):
    __tablename__ = "dict_indicator_type"

    type_code: Mapped[str] = mapped_column(String(32), unique=True, nullable=False)
    type_name: Mapped[str] = mapped_column(String(32), nullable=False)
    sort_no: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="ENABLED")
    is_builtin: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    description: Mapped[str | None] = mapped_column(String(255))


class DictIndicatorUnit(IdMixin, AuditMixin, Base):
    __tablename__ = "dict_indicator_unit"

    unit_code: Mapped[str] = mapped_column(String(40), unique=True, nullable=False)
    unit_name: Mapped[str] = mapped_column(String(40), nullable=False)
    value_kind: Mapped[str] = mapped_column(String(32), nullable=False)
    display_suffix: Mapped[str | None] = mapped_column(String(20))
    is_compound: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    sort_no: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="ENABLED")
    is_builtin: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    description: Mapped[str | None] = mapped_column(String(500))


class SysOrgUnit(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "sys_org_unit"

    # 仅允许引导组织在系统账号创建前短暂为空，种子完成时立即回填系统账号。
    created_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=True, default=None)

    org_code: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    org_name: Mapped[str] = mapped_column(String(100), nullable=False)
    org_short_name: Mapped[str | None] = mapped_column(String(50))
    org_type: Mapped[str] = mapped_column(String(32), nullable=False)
    parent_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"))
    sort_no: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="ENABLED")


class SysOrgAlias(IdMixin, AuditMixin, Base):
    __tablename__ = "sys_org_alias"
    __table_args__ = (UniqueConstraint("alias_name", "source_name", name="uq_org_alias_source"),)

    org_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"))
    alias_name: Mapped[str] = mapped_column(String(100), nullable=False)
    source_name: Mapped[str | None] = mapped_column(String(100))
    confirm_status: Mapped[str] = mapped_column(String(20), nullable=False, default="PENDING")
    confirmed_by: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_user.id"))
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime)


class SysUser(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "sys_user"

    account_name: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    user_name: Mapped[str] = mapped_column(String(50), nullable=False)
    primary_org_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="ENABLED")
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime)
    auth_subject: Mapped[str | None] = mapped_column(String(128))
    password_hash: Mapped[str | None] = mapped_column(String(255))


class SysRole(IdMixin, AuditMixin, Base):
    __tablename__ = "sys_role"

    role_code: Mapped[str] = mapped_column(String(40), unique=True, nullable=False)
    role_name: Mapped[str] = mapped_column(String(50), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="ENABLED")
    description: Mapped[str | None] = mapped_column(String(255))


class SysUserRole(IdMixin, CreateMixin, Base):
    __tablename__ = "sys_user_role"
    __table_args__ = (
        UniqueConstraint("user_id", "role_code", "effective_from", name="uq_user_role_effective"),
        Index("ix_user_role_user_effective", "user_id", "effective_from", "effective_to"),
    )

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    role_code: Mapped[str] = mapped_column(String(40), ForeignKey("sys_role.role_code"), nullable=False)
    effective_from: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    effective_to: Mapped[datetime | None] = mapped_column(DateTime)


class ProjectMember(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "project_member"
    __table_args__ = (
        Index("ix_project_member_plan_role", "plan_id", "member_role", "effective_to"),
        Index("ix_project_member_user_org", "user_id", "org_id"),
    )

    plan_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("construction_plan.id"), nullable=False)
    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    org_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("sys_org_unit.id"))
    member_role: Mapped[str] = mapped_column(String(40), nullable=False)
    is_primary: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    effective_from: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    effective_to: Mapped[datetime | None] = mapped_column(DateTime)
    handover_from_id: Mapped[int | None] = mapped_column(BigInteger, ForeignKey("project_member.id"))


class SystemConfig(IdMixin, AuditMixin, LockMixin, Base):
    __tablename__ = "system_config"

    config_key: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    config_value: Mapped[dict | list | str | int | float | bool] = mapped_column(JSON, nullable=False)
    description: Mapped[str | None] = mapped_column(String(500))


class IdempotencyRecord(IdMixin, CreateMixin, Base):
    __tablename__ = "idempotency_record"
    __table_args__ = (UniqueConstraint("user_id", "idempotency_key", name="uq_idempotency_user_key"),)

    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(128), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    response_json: Mapped[dict | list] = mapped_column(JSON, nullable=False)
    resource_type: Mapped[str] = mapped_column(String(40), nullable=False)
    resource_id: Mapped[int | None] = mapped_column(BigInteger)
