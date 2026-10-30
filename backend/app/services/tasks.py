from datetime import date
from decimal import Decimal
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import AppError
from app.models import (
    CollegeTask,
    ConstructionPlan,
    DictIndicatorUnit,
    IndicatorAnnualTarget,
    OutputIndicator,
    ProjectMember,
    SysOrgUnit,
    SysUser,
    TaskAnnualMilestone,
)
from app.schemas import AssignmentInput, AssignmentTaskInput
from app.security import active_role_codes
from app.services.audit import create_todo, write_log
from app.services.planning import assert_indicator_publishable
from app.services.values import validate_single_value_kind
from app.utils import json_value, model_dict, new_code, utcnow


ADDITIVE_METHODS = {"CUMULATIVE", "ANNUAL_QUOTA", "FINAL"}


def _validate_owner(db: Session, task: AssignmentTaskInput) -> None:
    org = db.get(SysOrgUnit, task.college_org_id)
    owner = db.get(SysUser, task.owner_user_id)
    if not org or org.org_type != "COLLEGE" or org.status != "ENABLED":
        raise AppError(422, "INVALID_COLLEGE", "执行学院不存在、已停用或不是学院")
    if not owner or owner.status != "ENABLED" or owner.primary_org_id != org.id:
        raise AppError(422, "INVALID_TASK_OWNER", "学院负责人必须属于该学院且处于启用状态")
    if "COLLEGE_OWNER" not in active_role_codes(db, owner.id):
        raise AppError(422, "INVALID_TASK_OWNER_ROLE", "学院负责人不具备有效的学院项目干系人角色")


def assignment_preview(
    db: Session,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    data: AssignmentInput,
) -> dict[str, Any]:
    if indicator.calc_method == "EXTERNAL":
        raise AppError(422, "EXTERNAL_NO_COLLEGE_TASK", "外部数据型由校级负责人认定，不生成学院填报任务")
    unit = db.execute(select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code == indicator.unit_code)).scalars().one()
    annual_targets = db.execute(
        select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == indicator.id)
    ).scalars().all()
    target_by_year = {item.target_year: item for item in annual_targets}
    seen: set[tuple[int, str | None]] = set()
    totals = {year: Decimal("0") for year in range(plan.start_year, plan.end_year + 1)}
    task_previews = []
    errors: list[str] = []
    for item in data.tasks:
        _validate_owner(db, item)
        key = (item.college_org_id, item.execution_scope)
        if key in seen:
            errors.append("同一指标同学院多项任务必须填写不同执行范围")
        seen.add(key)
        validate_single_value_kind(
            unit, item.five_year_target_value, item.five_year_target_bool, item.five_year_target_text, "学院五年任务目标"
        )
        years = [target.target_year for target in item.annual_targets]
        if len(years) != len(set(years)):
            errors.append(f"学院 {item.college_org_id} 存在重复年度目标")
        if set(years) != set(range(plan.start_year, plan.end_year + 1)):
            errors.append(f"学院 {item.college_org_id} 必须完整填写计划周期内各年度目标记录")
        for target in item.annual_targets:
            if not plan.start_year <= target.target_year <= plan.end_year:
                errors.append(f"年度 {target.target_year} 超出计划周期")
                continue
            validate_single_value_kind(unit, target.target_value, target.target_bool, target.target_text, f"{target.target_year} 年学院目标")
            if target.target_value is not None:
                totals[target.target_year] += target.target_value
        task_previews.append(json_value(item.model_dump()))
    differences = []
    additive = indicator.calc_method in ADDITIVE_METHODS and not data.non_additive_reason
    five_year_total = sum((item.five_year_target_value or Decimal("0") for item in data.tasks), Decimal("0"))
    if additive:
        if indicator.professional_five_year_target_value is not None:
            difference = indicator.professional_five_year_target_value - five_year_total
            if difference != 0:
                differences.append({"scope": "FIVE_YEAR", "target": indicator.professional_five_year_target_value, "allocated": five_year_total, "difference": difference})
        for year, allocated in totals.items():
            target = target_by_year.get(year)
            expected = target.professional_target_value if target else None
            # 空白年度表示源表未提供目标，不按 0 参与加总校验。
            if expected is not None and expected != allocated:
                differences.append({"scope": year, "target": expected, "allocated": allocated, "difference": expected - allocated})
    if differences:
        errors.append("学院目标合计与指标目标存在未解释差异；如属非加总分配必须填写原因")
    return {
        "indicator_id": indicator.id,
        "calc_method": indicator.calc_method,
        "non_additive_reason": data.non_additive_reason,
        "five_year": {
            "target": indicator.professional_five_year_target_value,
            "allocated": five_year_total,
            "unallocated": (indicator.professional_five_year_target_value - five_year_total)
            if indicator.professional_five_year_target_value is not None else None,
        },
        "annual_allocated": totals,
        "differences": differences,
        "errors": errors,
        "can_assign": not errors,
        "tasks": task_previews,
    }


def assign_tasks(
    db: Session,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    data: AssignmentInput,
    user: SysUser,
) -> list[CollegeTask]:
    if plan.plan_status not in {"PUBLISHED", "IN_PROGRESS"}:
        raise AppError(409, "PLAN_NOT_PUBLISHED", "计划发布后才能下发学院任务")
    assert_indicator_publishable(db, indicator, plan)
    preview = assignment_preview(db, indicator, plan, data)
    if not preview["can_assign"]:
        raise AppError(422, "ASSIGNMENT_VALIDATION_FAILED", "任务分解校验未通过", json_value(preview))
    source_row_id = indicator.source_import_row_id
    created_tasks = []
    for item in data.tasks:
        task = CollegeTask(
            task_code=new_code("TASK"),
            indicator_id=indicator.id,
            college_org_id=item.college_org_id,
            owner_user_id=item.owner_user_id,
            execution_scope=item.execution_scope,
            five_year_target_value=item.five_year_target_value,
            five_year_target_bool=item.five_year_target_bool,
            five_year_target_text=item.five_year_target_text,
            professional_allocation_five_year_raw=None,
            source_import_row_id=source_row_id,
            task_status="PENDING_RECEIVE",
            assigned_by=user.id,
            assigned_at=utcnow(),
            created_by=user.id,
        )
        if indicator.source_import_row_id:
            from app.models import SourceImportRow
            source_row = db.get(SourceImportRow, indicator.source_import_row_id)
            if source_row:
                task.professional_allocation_five_year_raw = (
                    source_row.professional_five_year_allocation_raw
                    if source_row.professional_five_year_allocation_raw is not None
                    else source_row.school_2026_division_raw
                )
        membership = db.execute(
            select(ProjectMember).where(
                ProjectMember.plan_id == plan.id,
                ProjectMember.user_id == task.owner_user_id,
                ProjectMember.org_id == task.college_org_id,
                ProjectMember.member_role == "COLLEGE_OWNER",
                ProjectMember.effective_to.is_(None),
            )
        ).scalars().first()
        if not membership:
            db.add(
                ProjectMember(
                    plan_id=plan.id,
                    user_id=task.owner_user_id,
                    org_id=task.college_org_id,
                    member_role="COLLEGE_OWNER",
                    is_primary=False,
                    effective_from=utcnow(),
                    created_by=user.id,
                )
            )
        db.add(task)
        db.flush()
        milestones = []
        for annual in sorted(item.annual_targets, key=lambda entry: entry.target_year):
            milestone = TaskAnnualMilestone(
                task_id=task.id,
                target_year=annual.target_year,
                original_target_value=annual.target_value,
                original_target_bool=annual.target_bool,
                original_target_text=annual.target_text,
                current_target_value=annual.target_value,
                current_target_bool=annual.target_bool,
                current_target_text=annual.target_text,
                professional_allocation_year_raw=annual.allocation_raw,
                milestone_status="PENDING_REPORT",
                due_date=date(annual.target_year, 12, 31),
                shortfall_value=annual.target_value if indicator.calc_method == "CUMULATIVE" else None,
                covered_value=Decimal("0") if indicator.calc_method == "CUMULATIVE" else None,
                created_by=user.id,
            )
            db.add(milestone)
            milestones.append(milestone)
        db.flush()
        snapshot = {
            "indicator": model_dict(indicator),
            "task": model_dict(task, exclude={"assigned_snapshot_json"}),
            "annual_targets": [model_dict(milestone) for milestone in milestones],
            "non_additive_reason": data.non_additive_reason,
        }
        task.assigned_snapshot_json = json_value(snapshot)
        create_todo(
            db,
            recipient_user_id=task.owner_user_id,
            plan_id=plan.id,
            biz_type="TASK_ASSIGN",
            biz_id=task.id,
            title=f"请接收学院任务：{indicator.indicator_name}",
            content_summary=f"计划：{plan.plan_name}；周期：{plan.start_year}—{plan.end_year}",
        )
        write_log(
            db,
            user,
            object_type="TASK",
            object_id=task.id,
            object_name=indicator.indicator_name,
            action_code="ASSIGN",
            plan_id=plan.id,
            college_org_id=task.college_org_id,
            after=task.assigned_snapshot_json,
        )
        created_tasks.append(task)
    indicator.indicator_status = "PUBLISHED"
    plan.plan_status = "IN_PROGRESS"
    db.flush()
    return created_tasks
