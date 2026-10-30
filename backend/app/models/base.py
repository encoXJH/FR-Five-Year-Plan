from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer
from sqlalchemy.orm import Mapped, declared_attr, mapped_column

from app.utils import utcnow


class IdMixin:
    id: Mapped[int] = mapped_column(BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True)


class CreateMixin:
    @declared_attr
    def created_by(cls) -> Mapped[int]:
        return mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=False, default=1)

    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=utcnow)


class AuditMixin(CreateMixin):
    @declared_attr
    def updated_by(cls) -> Mapped[int | None]:
        return mapped_column(BigInteger, ForeignKey("sys_user.id"), nullable=True)

    updated_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True, onupdate=utcnow)


class LockMixin:
    lock_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)

    @declared_attr
    def __mapper_args__(cls):
        return {"version_id_col": cls.lock_version, "version_id_generator": False}
