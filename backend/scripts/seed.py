import argparse
import json

from sqlalchemy import select

from app.config import get_settings
from app.database import SessionLocal
from app.models import (
    ConstructionDirectory,
    ConstructionPlan,
    DictIndicatorType,
    DictIndicatorUnit,
    ProjectMember,
    SysOrgAlias,
    SysOrgUnit,
    SysRole,
    SysUser,
    SysUserRole,
    SystemConfig,
)
from app.security import hash_password
from app.services.storage import DEFAULT_MATERIAL_CONFIG
from app.utils import json_value, utcnow


ROLES = [
    ("PLATFORM_ADMIN", "平台管理员", "维护组织、账号、字典和运行配置"),
    ("SCHOOL_LEADER", "校领导", "目录负责、进度查看和跨院划转终审"),
    ("SCHOOL_OWNER", "校级项目干系人", "项目量化、下发、审核和日常调整"),
    ("COLLEGE_OWNER", "学院项目干系人", "学院任务接收、填报和划转确认"),
]
COLLEGES = [
    "智能产业学院",
    "游戏产业学院",
    "数字金融产业学院",
    "智能建造产业学院",
    "数字影视传媒产业学院",
    "现代通信产业学院",
    "数据产业学院",
    "未来技术产业学院",
    "马克思主义学院",
    "国际学院",
]
UNITS = [
    ("YES_NO", "是/否", "BOOLEAN", None, 0),
    ("RATIO", "比值", "RATIO", None, 0),
    ("COURSE_OR_ITEM", "门/个", "COMPOUND_DECIMAL", None, 1),
    ("ITEM", "项", "DECIMAL", "项", 0),
    ("ITEM_COUNT", "个", "DECIMAL", "个", 0),
    ("NOT_APPLICABLE", "—", "NONE", None, 0),
    ("PERCENT_PER_YEAR", "%/每年", "PERCENT_YEAR", "%", 0),
    ("PERSON", "人", "DECIMAL", "人", 0),
    ("COURSE", "门", "DECIMAL", "门", 0),
    ("SHEET", "张", "DECIMAL", "张", 0),
    ("BOOK_OR_VOLUME", "本/册", "COMPOUND_DECIMAL", None, 1),
    ("BOOK", "本", "DECIMAL", "本", 0),
    ("PERCENT", "%", "PERCENT", "%", 0),
    ("PERSON_TIME", "人次", "DECIMAL", "人次", 0),
    ("ITEM_OR_PERSON", "个或人", "COMPOUND_DECIMAL", None, 1),
    ("COPY", "份", "DECIMAL", "份", 0),
]


def _get_or_create_user(db, account: str, name: str, org_id: int, password: str | None, user_id: int | None = None) -> SysUser:
    user = db.execute(select(SysUser).where(SysUser.account_name == account)).scalars().first()
    if user:
        return user
    values = {"account_name": account, "user_name": name, "primary_org_id": org_id, "created_by": user_id or 1}
    if user_id is not None:
        values["id"] = user_id
    if password:
        values["password_hash"] = hash_password(password)
    else:
        values["status"] = "DISABLED"
    user = SysUser(**values)
    db.add(user)
    db.flush()
    return user


def seed_all(db, with_college2: bool = False, plan_definitions: list[dict] | None = None) -> dict:
    settings = get_settings()
    if settings.environment != "local":
        raise RuntimeError("演示账号种子仅允许 local 环境执行")
    school_dept = db.execute(select(SysOrgUnit).where(SysOrgUnit.org_code == "SCHOOL")).scalars().first()
    if not school_dept:
        school_dept = SysOrgUnit(org_code="SCHOOL", org_name="校级管理部门", org_type="SCHOOL_DEPT", sort_no=0, created_by=None)
        db.add(school_dept)
        db.flush()
    system = db.get(SysUser, 1)
    if not system:
        system = _get_or_create_user(db, "system", "系统导入账号", school_dept.id, None, user_id=1)
    if school_dept.created_by is None:
        school_dept.created_by = system.id
    for sort_no, name in enumerate(COLLEGES, 1):
        code = f"COLLEGE-{sort_no:02d}"
        org = db.execute(select(SysOrgUnit).where(SysOrgUnit.org_code == code)).scalars().first()
        if not org:
            db.add(SysOrgUnit(org_code=code, org_name=name, org_short_name="马院" if name == "马克思主义学院" else None, org_type="COLLEGE", parent_id=school_dept.id, sort_no=sort_no, created_by=system.id))
    db.flush()
    for code, name, description in ROLES:
        role = db.execute(select(SysRole).where(SysRole.role_code == code)).scalars().first()
        if not role:
            db.add(SysRole(role_code=code, role_name=name, description=description, created_by=system.id))
    for sort_no, (name, count) in enumerate([("约束性", 28), ("发展性", 59), ("特色", 10)], 1):
        code = {"约束性": "CONSTRAINED", "发展性": "DEVELOPMENT", "特色": "FEATURED"}[name]
        item = db.execute(select(DictIndicatorType).where(DictIndicatorType.type_code == code)).scalars().first()
        if not item:
            db.add(DictIndicatorType(type_code=code, type_name=name, sort_no=sort_no, is_builtin=True, description=f"首批源表 {count} 项", created_by=system.id))
    for sort_no, (code, name, kind, suffix, compound) in enumerate(UNITS, 1):
        item = db.execute(select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code == code)).scalars().first()
        if not item:
            db.add(DictIndicatorUnit(unit_code=code, unit_name=name, value_kind=kind, display_suffix=suffix, is_compound=bool(compound), sort_no=sort_no, is_builtin=True, created_by=system.id))
    accounts = [
        ("admin", "平台管理员", school_dept.id, "PLATFORM_ADMIN"),
        ("leader", "校领导", school_dept.id, "SCHOOL_LEADER"),
        ("school", "校级项目负责人", school_dept.id, "SCHOOL_OWNER"),
    ]
    college_org = db.execute(select(SysOrgUnit).where(SysOrgUnit.org_name == "智能产业学院")).scalars().one()
    accounts.append(("college", "智能产业学院负责人", college_org.id, "COLLEGE_OWNER"))
    if with_college2:
        college2_org = db.execute(select(SysOrgUnit).where(SysOrgUnit.org_name == "游戏产业学院")).scalars().one()
        accounts.append(("college2", "游戏产业学院负责人", college2_org.id, "COLLEGE_OWNER"))
    users = {"system": system}
    for account, name, org_id, role_code in accounts:
        account_user = db.execute(select(SysUser).where(SysUser.account_name == account)).scalars().first()
        if not account_user:
            account_user = _get_or_create_user(db, account, name, org_id, "Dev@2026")
        users[account] = account_user
        active_role = db.execute(select(SysUserRole).where(SysUserRole.user_id == account_user.id, SysUserRole.role_code == role_code, SysUserRole.effective_to.is_(None))).scalars().first()
        if not active_role:
            db.add(SysUserRole(user_id=account_user.id, role_code=role_code, effective_from=utcnow(), created_by=system.id))
    db.flush()
    aliases = [
        ("马院", "马克思主义学院"),
        ("数据学院", "数据产业学院"),
        ("现代通信学院", "现代通信产业学院"),
        ("数传学院", "数字影视传媒产业学院"),
        ("大数据学院", None),
        ("大数据产业学院", None),
    ]
    for alias_name, org_name in aliases:
        existing = db.execute(select(SysOrgAlias).where(SysOrgAlias.alias_name == alias_name, SysOrgAlias.source_name == "首批 Excel")).scalars().first()
        if not existing:
            org_id = db.execute(select(SysOrgUnit.id).where(SysOrgUnit.org_name == org_name)).scalar_one_or_none() if org_name else None
            db.add(SysOrgAlias(alias_name=alias_name, org_id=org_id, source_name="首批 Excel", confirm_status="PENDING", created_by=system.id))
    directory = db.execute(select(ConstructionDirectory).where(ConstructionDirectory.directory_code == "DIR-SMART-MFG")).scalars().first()
    if not directory:
        directory = ConstructionDirectory(directory_code="DIR-SMART-MFG", directory_name="智能生产装备与技术专业群建设目录", description="首个五年建设任务目录", leader_user_id=users["leader"].id, status="ENABLED", sort_no=1, created_by=system.id)
        db.add(directory)
        db.flush()
    definitions = plan_definitions or [{
        "plan_code": "PLAN-SMART-2026-2030",
        "plan_name": "智能生产装备与技术专业群建设计划",
        "construction_scope": "智能生产装备与技术专业群",
    }]
    plans = {}
    for definition in definitions:
        plan = db.execute(select(ConstructionPlan).where(ConstructionPlan.plan_code == definition["plan_code"])).scalars().first()
        if not plan:
            plan = ConstructionPlan(
                plan_code=definition["plan_code"],
                directory_id=directory.id,
                plan_name=definition["plan_name"],
                construction_scope=definition["construction_scope"],
                start_year=2026,
                end_year=2030,
                school_owner_id=users["school"].id,
                created_by=system.id,
            )
            db.add(plan)
            db.flush()
        plans[definition["plan_code"]] = plan
        member_specs = [(users["school"], "SCHOOL_OWNER", None, True), (users["college"], "COLLEGE_OWNER", college_org.id, False)]
        if with_college2:
            college2_org = db.get(SysOrgUnit, users["college2"].primary_org_id)
            member_specs.append((users["college2"], "COLLEGE_OWNER", college2_org.id, False))
        for member_user, member_role, org_id, primary in member_specs:
            existing = db.execute(select(ProjectMember).where(ProjectMember.plan_id == plan.id, ProjectMember.user_id == member_user.id, ProjectMember.member_role == member_role, ProjectMember.effective_to.is_(None))).scalars().first()
            if not existing:
                db.add(ProjectMember(plan_id=plan.id, user_id=member_user.id, org_id=org_id, member_role=member_role, is_primary=primary, effective_from=utcnow(), created_by=system.id))
    configs = {
        "material_policy": (DEFAULT_MATERIAL_CONFIG, "材料格式、大小和数量限制"),
        "construction_years": ([2026, 2027, 2028, 2029, 2030], "首个建设周期年度字典"),
        "libreoffice": ({"path": settings.libreoffice_path}, "Office 预览转换程序路径，为空时尝试系统 PATH"),
    }
    for key, (value, description) in configs.items():
        existing = db.execute(select(SystemConfig).where(SystemConfig.config_key == key)).scalars().first()
        if not existing:
            db.add(SystemConfig(config_key=key, config_value=value, description=description, created_by=system.id))
    db.flush()
    first_plan = next(iter(plans.values()))
    return {"users": {key: value.id for key, value in users.items()}, "directory_id": directory.id, "plan_id": first_plan.id, "plan_ids": {code: plan.id for code, plan in plans.items()}, "college_count": len(COLLEGES), "unit_count": len(UNITS)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--with-college2", action="store_true")
    args = parser.parse_args()
    db = SessionLocal()
    try:
        result = seed_all(db, with_college2=args.with_college2)
        db.commit()
        print(json.dumps(json_value(result), ensure_ascii=False, indent=2))
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


if __name__ == "__main__":
    main()
