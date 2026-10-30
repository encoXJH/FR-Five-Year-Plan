from fastapi import APIRouter, Depends, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.models import SysUser
from app.schemas import LoginRequest
from app.security import active_role_codes, create_access_token, get_current_user, verify_password
from app.services.audit import write_log
from app.utils import model_dict, utcnow


from app.responses import DecimalJSONResponse

router = APIRouter(prefix="/auth", tags=["认证"], default_response_class=DecimalJSONResponse)


def user_payload(db: Session, user: SysUser) -> dict:
    data = model_dict(user, exclude={"password_hash"})
    data["roles"] = sorted(active_role_codes(db, user.id))
    return data


@router.post("/login")
def login(data: LoginRequest, response: Response, db: Session = Depends(get_db)):
    user = db.execute(select(SysUser).where(SysUser.account_name == data.account_name)).scalars().first()
    if not user or user.status != "ENABLED" or not verify_password(data.password, user.password_hash):
        from app.errors import AppError
        raise AppError(401, "INVALID_CREDENTIALS", "账号或密码错误，或账号已停用")
    token = create_access_token(user)
    settings = get_settings()
    response.set_cookie(
        settings.cookie_name,
        token,
        max_age=settings.jwt_expire_minutes * 60,
        httponly=True,
        secure=settings.cookie_secure,
        samesite="lax",
        path="/",
    )
    user.last_login_at = utcnow()
    write_log(db, user, object_type="USER", object_id=user.id, action_code="LOGIN")
    db.commit()
    return user_payload(db, user)


@router.post("/logout")
def logout(response: Response):
    response.delete_cookie(get_settings().cookie_name, path="/")
    return {"success": True}


@router.get("/me")
def me(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    return user_payload(db, user)
