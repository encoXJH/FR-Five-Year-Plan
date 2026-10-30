from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import AppError
from app.models import (
    CollegeTask,
    DictIndicatorType,
    DictIndicatorUnit,
    OutputIndicator,
    ProjectMember,
    SysOrgAlias,
    SysOrgUnit,
    SysRole,
    SysUser,
    SysUserRole,
    SystemConfig,
    TodoNotification,
)
from app.schemas import ConfigInput, HandoverInput, ProjectMemberInput, RoleGrantInput, UserDisableInput, UserInput, UserUpdateInput
from app.security import active_role_codes, get_current_user, hash_password, require_roles
from app.services.audit import write_log
from app.services.scope import ADMIN, COLLEGE, LEADER, SCHOOL, get_plan, is_school_owner
from app.utils import check_version, model_dict, utcnow


from app.responses import DecimalJSONResponse

router = APIRouter(tags=["基础管理"], default_response_class=DecimalJSONResponse)
admin_only = require_roles(ADMIN)


@router.get("/organizations")
def list_organizations(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    query = select(SysOrgUnit).order_by(SysOrgUnit.sort_no, SysOrgUnit.id)
    user_roles = active_role_codes(db, user.id)
    if user_roles == {"COLLEGE_OWNER"}:
        query = query.where(SysOrgUnit.id == user.primary_org_id)
    rows = db.execute(query).scalars().all()
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}


@router.post("/organizations", status_code=201)
def create_organization(data: dict[str, Any], user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    required = {"org_code", "org_name", "org_type"}
    if not required.issubset(data):
        raise AppError(422, "ORG_REQUIRED_FIELDS", "组织代码、名称和类型不能为空")
    row = SysOrgUnit(**{key: data[key] for key in ["org_code", "org_name", "org_short_name", "org_type", "parent_id", "sort_no"] if key in data}, created_by=user.id)
    db.add(row)
    db.flush()
    write_log(db, user, object_type="ORG", object_id=row.id, action_code="CREATE", after=model_dict(row))
    db.commit()
    return model_dict(row)


@router.patch("/organizations/{org_id}")
def update_organization(org_id: int, data: dict[str, Any], user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    row = db.get(SysOrgUnit, org_id)
    if not row:
        raise AppError(404, "ORG_NOT_FOUND", "组织不存在")
    check_version(row, data.get("expected_version"))
    before = model_dict(row)
    for field in ["org_name", "org_short_name", "org_type", "parent_id", "sort_no", "status"]:
        if field in data:
            setattr(row, field, data[field])
    row.lock_version += 1
    write_log(db, user, object_type="ORG", object_id=row.id, action_code="UPDATE", before=before, after=model_dict(row))
    db.commit()
    return model_dict(row)


@router.get("/aliases")
def list_aliases(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    rows = db.execute(select(SysOrgAlias).order_by(SysOrgAlias.alias_name, SysOrgAlias.id)).scalars().all()
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}


@router.post("/organizations/{org_id}/aliases", status_code=201)
def create_alias(org_id: int, data: dict[str, Any], user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    if not db.get(SysOrgUnit, org_id) or not data.get("alias_name"):
        raise AppError(422, "INVALID_ALIAS", "标准组织或别名无效")
    row = SysOrgAlias(org_id=org_id, alias_name=data["alias_name"], source_name=data.get("source_name"), confirm_status="PENDING", created_by=user.id)
    db.add(row)
    db.commit()
    return model_dict(row)


@router.post("/aliases/{alias_id}/confirm")
def confirm_alias(alias_id: int, data: dict[str, Any], user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    row = db.get(SysOrgAlias, alias_id)
    if not row:
        raise AppError(404, "ALIAS_NOT_FOUND", "组织别名不存在")
    org_id = data.get("org_id", row.org_id)
    if data.get("confirm_status", "CONFIRMED") == "CONFIRMED" and not db.get(SysOrgUnit, org_id):
        raise AppError(422, "ALIAS_ORG_REQUIRED", "确认别名必须映射到有效标准组织")
    row.org_id = org_id
    row.confirm_status = data.get("confirm_status", "CONFIRMED")
    row.confirmed_by = user.id
    row.confirmed_at = utcnow()
    db.commit()
    return model_dict(row)


@router.get("/users")
def list_users(
    role: str | None = None,
    status: str | None = None,
    keyword: str | None = None,
    page: int = 1,
    page_size: int = 100,
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    user_roles = active_role_codes(db, user.id)
    is_admin = ADMIN in user_roles
    if not is_admin and not user_roles.intersection({LEADER, SCHOOL, COLLEGE}):
        raise AppError(403, "USER_LIST_FORBIDDEN", "当前角色不可获取用户列表")
    if page < 1 or not 1 <= page_size <= 200:
        raise AppError(422, "PAGINATION_INVALID", "page 须大于 0，page_size 须在 1—200 之间")
    query = select(SysUser).order_by(SysUser.id)
    if not is_admin:
        query = query.where(SysUser.account_name != "system")
    if COLLEGE in user_roles and not user_roles.intersection({ADMIN, LEADER, SCHOOL}):
        query = query.where(SysUser.primary_org_id == user.primary_org_id)
    if status:
        if status not in {"ENABLED", "DISABLED"}:
            raise AppError(422, "USER_STATUS_INVALID", "用户状态必须为 ENABLED 或 DISABLED")
        query = query.where(SysUser.status == status)
    if keyword and keyword.strip():
        value = keyword.strip()
        query = query.where(or_(SysUser.user_name.contains(value), SysUser.account_name.contains(value)))
    rows = db.execute(query).scalars().all()
    items = []
    for row in rows:
        row_roles = sorted(active_role_codes(db, row.id))
        if role and role not in row_roles:
            continue
        if is_admin:
            item = model_dict(row, exclude={"password_hash"})
        else:
            item = {
                "id": row.id,
                "account_name": row.account_name,
                "user_name": row.user_name,
                "primary_org_id": row.primary_org_id,
                "status": row.status,
            }
        item["org_name"] = db.get(SysOrgUnit, row.primary_org_id).org_name
        item["roles"] = row_roles
        items.append(item)
    total = len(items)
    start = (page - 1) * page_size
    return {"items": items[start:start + page_size], "total": total, "page": page, "page_size": page_size}


@router.post("/users", status_code=201)
def create_user(data: UserInput, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    if not db.get(SysOrgUnit, data.primary_org_id):
        raise AppError(422, "PRIMARY_ORG_INVALID", "主属组织不存在")
    row = SysUser(
        account_name=data.account_name,
        user_name=data.user_name,
        primary_org_id=data.primary_org_id,
        password_hash=hash_password(data.password) if data.password else None,
        created_by=user.id,
    )
    db.add(row)
    db.flush()
    for role_code in set(data.role_codes):
        if not db.execute(select(SysRole).where(SysRole.role_code == role_code, SysRole.status == "ENABLED")).scalars().first():
            raise AppError(422, "ROLE_INVALID", f"角色 {role_code} 不存在或停用")
        db.add(SysUserRole(user_id=row.id, role_code=role_code, effective_from=utcnow(), created_by=user.id))
    write_log(db, user, object_type="USER", object_id=row.id, action_code="CREATE", after=model_dict(row, exclude={"password_hash"}))
    db.commit()
    return model_dict(row, exclude={"password_hash"})


@router.patch("/users/{user_id}")
def update_user(user_id: int, data: UserUpdateInput, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    target = db.get(SysUser, user_id)
    if not target:
        raise AppError(404, "USER_NOT_FOUND", "用户不存在")
    check_version(target, data.expected_version)
    if data.primary_org_id is not None:
        org = db.get(SysOrgUnit, data.primary_org_id)
        if not org or org.status != "ENABLED":
            raise AppError(422, "PRIMARY_ORG_INVALID", "主属组织不存在或已停用")
    before = model_dict(target, exclude={"password_hash"})
    previous_status = target.status
    values = data.model_dump(exclude_unset=True, exclude={"expected_version"})
    for key, value in values.items():
        setattr(target, key, value)
    target.lock_version += 1
    action = "ENABLE" if previous_status == "DISABLED" and target.status == "ENABLED" else "UPDATE"
    write_log(db, user, object_type="USER", object_id=target.id, action_code=action, before=before, after=model_dict(target, exclude={"password_hash"}))
    db.commit()
    return model_dict(target, exclude={"password_hash"})


@router.post("/users/{user_id}/roles", status_code=201)
def grant_role(user_id: int, data: RoleGrantInput, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    target = db.get(SysUser, user_id)
    role = db.execute(select(SysRole).where(SysRole.role_code == data.role_code, SysRole.status == "ENABLED")).scalars().first()
    if not target or not role:
        raise AppError(404, "USER_OR_ROLE_NOT_FOUND", "用户或角色不存在")
    row = SysUserRole(user_id=user_id, role_code=data.role_code, effective_from=data.effective_from or utcnow(), effective_to=data.effective_to, created_by=user.id)
    db.add(row)
    db.flush()
    write_log(db, user, object_type="USER", object_id=user_id, action_code="ROLE_GRANT", after={"role": data.role_code, "effective_from": row.effective_from})
    db.commit()
    return model_dict(row)


@router.post("/users/{user_id}/roles/{role_code}/revoke")
def revoke_role(user_id: int, role_code: str, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    rows = db.execute(select(SysUserRole).where(SysUserRole.user_id == user_id, SysUserRole.role_code == role_code, SysUserRole.effective_to.is_(None))).scalars().all()
    for row in rows:
        row.effective_to = utcnow()
    write_log(db, user, object_type="USER", object_id=user_id, action_code="ROLE_REVOKE", after={"role": role_code})
    db.commit()
    return {"success": True, "revoked": len(rows)}


@router.post("/users/{user_id}/disable")
def disable_user(user_id: int, data: UserDisableInput, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    target = db.get(SysUser, user_id)
    if not target:
        raise AppError(404, "USER_NOT_FOUND", "用户不存在")
    check_version(target, data.expected_version)
    pending = db.execute(select(TodoNotification).where(TodoNotification.recipient_user_id == user_id, TodoNotification.todo_status.in_(["PENDING", "READ"]))).scalars().all()
    memberships = db.execute(select(ProjectMember).where(ProjectMember.user_id == user_id, ProjectMember.effective_to.is_(None))).scalars().all()
    tasks = db.execute(select(CollegeTask).where(CollegeTask.owner_user_id == user_id, CollegeTask.task_status.in_(["PENDING_RECEIVE", "IN_PROGRESS"]))).scalars().all()
    handover = db.get(SysUser, data.handover_user_id) if data.handover_user_id else None
    if (pending or memberships or tasks) and (not handover or handover.status != "ENABLED"):
        raise AppError(409, "HANDOVER_REQUIRED", "账号仍有负责人职责或待办，停用前必须指定有效交接人")
    now = utcnow()
    if handover:
        handover_roles = active_role_codes(db, handover.id)
        for member in memberships:
            required_role = "SCHOOL_OWNER" if member.member_role == "SCHOOL_OWNER" else "COLLEGE_OWNER" if member.member_role == "COLLEGE_OWNER" else None
            if required_role and required_role not in handover_roles:
                raise AppError(422, "HANDOVER_ROLE_MISMATCH", "交接人不具备原项目责任所需的有效角色")
            member.effective_to = now
            new_member = ProjectMember(plan_id=member.plan_id, user_id=handover.id, org_id=member.org_id, member_role=member.member_role, is_primary=member.is_primary, effective_from=now, handover_from_id=member.id, created_by=user.id)
            db.add(new_member)
            if member.is_primary and member.member_role == "SCHOOL_OWNER":
                from app.models import ConstructionPlan
                plan = db.get(ConstructionPlan, member.plan_id)
                plan.school_owner_id = handover.id
        for task in tasks:
            if handover.primary_org_id != task.college_org_id:
                raise AppError(422, "HANDOVER_ORG_MISMATCH", "学院任务交接人必须属于同一学院")
            task.owner_user_id = handover.id
        for todo in pending:
            todo.recipient_user_id = handover.id
    target.status = "DISABLED"
    target.lock_version += 1
    write_log(db, user, object_type="USER", object_id=user_id, action_code="DISABLE", reason=data.reason, after={"handover_user_id": data.handover_user_id})
    db.commit()
    return model_dict(target, exclude={"password_hash"})


@router.get("/project-members")
def list_project_members(plan_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    from app.models import ConstructionPlan
    if ADMIN in active_role_codes(db, user.id):
        plan = db.get(ConstructionPlan, plan_id)
        if not plan:
            raise AppError(404, "PLAN_NOT_FOUND", "建设计划不存在")
    else:
        plan = get_plan(db, user, plan_id)
    rows = db.execute(select(ProjectMember).where(ProjectMember.plan_id == plan.id)).scalars().all()
    items = []
    for row in rows:
        item = model_dict(row)
        member_user = db.get(SysUser, row.user_id)
        member_org = db.get(SysOrgUnit, row.org_id) if row.org_id else None
        item["user_name"] = member_user.user_name if member_user else None
        item["account_name"] = member_user.account_name if member_user else None
        item["org_name"] = member_org.org_name if member_org else None
        items.append(item)
    return {"items": items, "total": len(items)}


@router.post("/project-members", status_code=201)
def add_project_member(data: ProjectMemberInput, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    from app.models import ConstructionPlan
    plan = db.get(ConstructionPlan, data.plan_id)
    target = db.get(SysUser, data.user_id)
    if not plan or not target or target.status != "ENABLED":
        raise AppError(422, "PROJECT_MEMBER_INVALID", "计划或有效用户不存在")
    if data.member_role == "SCHOOL_OWNER" and data.is_primary:
        raise AppError(409, "USE_HANDOVER", "主要校级负责人变更必须使用交接流程")
    row = ProjectMember(**data.model_dump(), effective_from=utcnow(), created_by=user.id)
    db.add(row)
    db.flush()
    write_log(db, user, object_type="PROJECT_MEMBER", object_id=row.id, action_code="CREATE", plan_id=plan.id, after=model_dict(row))
    db.commit()
    return model_dict(row)


@router.post("/project-members/{member_id}/handover")
def handover_member(member_id: int, data: HandoverInput, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    member = db.get(ProjectMember, member_id)
    target = db.get(SysUser, data.new_user_id)
    if not member or member.effective_to or not target or target.status != "ENABLED":
        raise AppError(422, "HANDOVER_INVALID", "原项目成员已失效或交接人无效")
    check_version(member, data.expected_version)
    required_role = "SCHOOL_OWNER" if member.member_role == "SCHOOL_OWNER" else "COLLEGE_OWNER" if member.member_role == "COLLEGE_OWNER" else None
    if required_role and required_role not in active_role_codes(db, target.id):
        raise AppError(422, "HANDOVER_ROLE_MISMATCH", "交接人不具备原项目责任所需的有效角色")
    now = utcnow()
    member.effective_to = now
    member.lock_version += 1
    new = ProjectMember(plan_id=member.plan_id, user_id=target.id, org_id=member.org_id, member_role=member.member_role, is_primary=member.is_primary, effective_from=now, handover_from_id=member.id, created_by=user.id)
    db.add(new)
    db.flush()
    if member.is_primary and member.member_role == "SCHOOL_OWNER":
        from app.models import ConstructionPlan
        plan = db.get(ConstructionPlan, member.plan_id)
        plan.school_owner_id = target.id
    if member.member_role == "COLLEGE_OWNER":
        if target.primary_org_id != member.org_id:
            raise AppError(422, "HANDOVER_ORG_MISMATCH", "学院负责人交接人必须同学院")
        tasks = db.execute(select(CollegeTask).where(CollegeTask.owner_user_id == member.user_id, CollegeTask.college_org_id == member.org_id, CollegeTask.task_status.in_(["PENDING_RECEIVE", "IN_PROGRESS"]))).scalars().all()
        for task in tasks:
            task.owner_user_id = target.id
    pending_todos = db.execute(select(TodoNotification).where(TodoNotification.recipient_user_id == member.user_id, TodoNotification.plan_id == member.plan_id, TodoNotification.todo_status.in_(["PENDING", "READ"]))).scalars().all()
    for todo in pending_todos:
        todo.recipient_user_id = target.id
    write_log(db, user, object_type="PROJECT_MEMBER", object_id=member.id, action_code="HANDOVER", plan_id=member.plan_id, reason=data.reason, after={"new_member_id": new.id, "new_user_id": target.id})
    db.commit()
    return model_dict(new)


@router.post("/dictionaries/indicator-types", status_code=201)
def create_indicator_type(data: dict[str, Any], user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    if not all(data.get(field) for field in ["type_code", "type_name"]) or data.get("sort_no") is None:
        raise AppError(422, "DICTIONARY_REQUIRED_FIELDS", "类型代码、名称和排序号不能为空")
    row = DictIndicatorType(type_code=data["type_code"], type_name=data["type_name"], sort_no=data["sort_no"], description=data.get("description"), is_builtin=False, created_by=user.id)
    db.add(row)
    db.commit()
    return model_dict(row)


@router.post("/dictionaries/indicator-units", status_code=201)
def create_indicator_unit(data: dict[str, Any], user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    if not all(data.get(field) for field in ["unit_code", "unit_name", "value_kind"]) or data.get("sort_no") is None:
        raise AppError(422, "DICTIONARY_REQUIRED_FIELDS", "单位代码、名称、值类型和排序号不能为空")
    if data["value_kind"] not in {"DECIMAL", "BOOLEAN", "RATIO", "PERCENT", "PERCENT_YEAR", "COMPOUND_DECIMAL", "NONE"}:
        raise AppError(422, "VALUE_KIND_INVALID", "值类型不在允许字典中")
    row = DictIndicatorUnit(unit_code=data["unit_code"], unit_name=data["unit_name"], value_kind=data["value_kind"], display_suffix=data.get("display_suffix"), is_compound=bool(data.get("is_compound", False)), sort_no=data["sort_no"], description=data.get("description"), is_builtin=False, created_by=user.id)
    db.add(row)
    db.commit()
    return model_dict(row)


@router.get("/dictionaries/indicator-types")
def list_types(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    rows = db.execute(select(DictIndicatorType).order_by(DictIndicatorType.sort_no)).scalars().all()
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}


@router.get("/dictionaries/indicator-units")
def list_units(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    rows = db.execute(select(DictIndicatorUnit).order_by(DictIndicatorUnit.sort_no)).scalars().all()
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}


@router.patch("/dictionaries/{kind}/{item_id}")
def update_dictionary(kind: str, item_id: int, data: dict[str, Any], user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    model = DictIndicatorType if kind == "indicator-types" else DictIndicatorUnit if kind == "indicator-units" else None
    if not model:
        raise AppError(404, "DICTIONARY_NOT_FOUND", "字典类型不存在")
    row = db.get(model, item_id)
    if not row:
        raise AppError(404, "DICTIONARY_ITEM_NOT_FOUND", "字典项不存在")
    for field in ["description", "sort_no", "status", "display_suffix"]:
        if field in data and hasattr(row, field):
            setattr(row, field, data[field])
    db.commit()
    return model_dict(row)


@router.get("/configs")
def list_configs(user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    rows = db.execute(select(SystemConfig).order_by(SystemConfig.config_key)).scalars().all()
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}


@router.put("/configs/{config_key}")
def put_config(config_key: str, data: ConfigInput, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    row = db.execute(select(SystemConfig).where(SystemConfig.config_key == config_key)).scalars().first()
    if row:
        check_version(row, data.expected_version)
        row.config_value = data.config_value
        row.description = data.description
        row.lock_version += 1
    else:
        row = SystemConfig(config_key=config_key, config_value=data.config_value, description=data.description, created_by=user.id)
        db.add(row)
    db.flush()
    write_log(db, user, object_type="CONFIG", object_id=row.id, action_code="UPDATE", after={"key": config_key, "value": data.config_value})
    db.commit()
    return model_dict(row)


@router.get("/construction-years")
def construction_years(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.execute(select(SystemConfig).where(SystemConfig.config_key == "construction_years")).scalars().first()
    years = row.config_value if row else [2026, 2027, 2028, 2029, 2030]
    return {"items": years, "total": len(years)}
