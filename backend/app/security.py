from datetime import timedelta

import jwt
from fastapi import Depends, Request
from pwdlib import PasswordHash
from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.errors import AppError
from app.models import SysRole, SysUser, SysUserRole
from app.utils import utcnow


password_hash = PasswordHash.recommended()


def hash_password(password: str) -> str:
    return password_hash.hash(password)


def verify_password(password: str, encoded: str | None) -> bool:
    return bool(encoded) and password_hash.verify(password, encoded)


def create_access_token(user: SysUser) -> str:
    settings = get_settings()
    now = utcnow()
    payload = {
        "sub": str(user.id),
        "account": user.account_name,
        "iat": now,
        "exp": now + timedelta(minutes=settings.jwt_expire_minutes),
        "type": "access",
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def get_current_user(request: Request, db: Session = Depends(get_db)) -> SysUser:
    settings = get_settings()
    token = request.cookies.get(settings.cookie_name)
    authorization = request.headers.get("Authorization")
    if not token and authorization and authorization.startswith("Bearer "):
        token = authorization[7:]
    if not token:
        raise AppError(401, "UNAUTHENTICATED", "请先登录")
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
        user_id = int(payload["sub"])
    except (jwt.PyJWTError, KeyError, ValueError) as exc:
        raise AppError(401, "INVALID_SESSION", "登录状态无效或已过期") from exc
    user = db.get(SysUser, user_id)
    if not user or user.status != "ENABLED":
        raise AppError(401, "ACCOUNT_DISABLED", "账号不存在或已停用")
    return user


def active_role_codes(db: Session, user_id: int) -> set[str]:
    now = utcnow()
    rows = db.execute(
        select(SysUserRole.role_code)
        .join(SysRole, SysRole.role_code == SysUserRole.role_code)
        .where(
            SysUserRole.user_id == user_id,
            SysUserRole.effective_from <= now,
            or_(SysUserRole.effective_to.is_(None), SysUserRole.effective_to > now),
            SysRole.status == "ENABLED",
        )
    ).scalars().all()
    return set(rows)


def require_roles(*role_codes: str):
    def dependency(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)) -> SysUser:
        roles = active_role_codes(db, user.id)
        # 平台管理员为超级管理员，可访问全部业务功能。
        if "PLATFORM_ADMIN" not in roles and not roles.intersection(role_codes):
            raise AppError(403, "FORBIDDEN", "当前角色无权执行此操作")
        return user

    return dependency
