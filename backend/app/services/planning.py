from copy import deepcopy
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import AppError
from app.models import (
    CollegeTask,
    ConstructionDirection,
    ConstructionPlan,
    DictIndicatorUnit,
    IndicatorAnnualTarget,
    IndicatorDepartmentRel,
    OutputIndicator,
    ProjectMember,
    SysUser,
    TaskAnnualMilestone,
)
from app.schemas import PlanCopyInput
from app.services.audit import write_log
from app.services.values import validate_single_value_kind
from app.utils import new_code, utcnow


CALC_METHODS = {"CUMULATIVE", "ANNUAL_QUOTA", "FINAL", "RATIO", "GROWTH", "BOOLEAN", "EXTERNAL"}


def validate_indicator_for_publish(db: Session, indicator: OutputIndicator, plan: ConstructionPlan) -> list[str]:
    errors: list[str] = []
    unit = db.execute(select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code == indicator.unit_code)).scalars().one()
    if indicator.parse_status != "CONFIRMED":
        errors.append("指标量化尚未确认")
    if not indicator.calc_method or indicator.calc_method not in CALC_METHODS:
        errors.append("未选择合法核算方式")
    if unit.value_kind != "NONE" and not indicator.target_direction:
        errors.append("未填写目标方向")
    if indicator.calc_method in {"CUMULATIVE", "ANNUAL_QUOTA", "FINAL", "RATIO", "GROWTH"} and not indicator.statistical_rule:
        errors.append("未填写统计口径")
    if unit.is_compound and not indicator.standard_unit_code:
        errors.append("复合单位尚未人工标准化")
    if indicator.calc_method == "RATIO":
        if not indicator.numerator_rule or not indicator.denominator_rule:
            errors.append("比率型须填写分子、分母来源")
        if not indicator.baseline_year:
            errors.append("比率型须填写统计基期年度")
    if indicator.calc_method == "GROWTH":
        if indicator.baseline_value is None or not indicator.baseline_year:
            errors.append("增长率型须填写可核算基期绝对值和基期年度")
        elif indicator.baseline_value == 0:
            errors.append("增长率型基期不能为 0")
    if indicator.calc_method == "EXTERNAL" and not indicator.external_data_source:
        errors.append("外部数据型须填写数据来源和更新责任说明")
    if unit.value_kind == "BOOLEAN" and indicator.calc_method != "BOOLEAN":
        errors.append("是/否单位必须使用是否型核算")
    validate_single_value_kind(
        unit,
        indicator.professional_five_year_target_value,
        indicator.professional_five_year_target_bool,
        indicator.professional_five_year_target_text,
        "专业群五年目标",
    )
    targets = db.execute(
        select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == indicator.id)
    ).scalars().all()
    target_years = {item.target_year for item in targets}
    missing_years = set(range(plan.start_year, plan.end_year + 1)) - target_years
    if missing_years:
        errors.append(f"缺少年度目标记录：{sorted(missing_years)}")
    for target in targets:
        if not plan.start_year <= target.target_year <= plan.end_year:
            errors.append(f"年度 {target.target_year} 超出计划周期")
        if target.confirm_status != "CONFIRMED":
            errors.append(f"{target.target_year} 年目标未确认（填写值、未提供或不适用均需确认）")
        validate_single_value_kind(
            unit,
            target.professional_target_value,
            target.professional_target_bool,
            target.professional_target_text,
            f"{target.target_year} 年目标",
        )
    return errors


def assert_indicator_publishable(db: Session, indicator: OutputIndicator, plan: ConstructionPlan) -> None:
    errors = validate_indicator_for_publish(db, indicator, plan)
    if errors:
        raise AppError(422, "INDICATOR_NOT_READY", "指标未达到发布条件", errors)


def auto_publish_plan(db: Session, plan: ConstructionPlan, user: SysUser) -> int:
    """导入确认后自动补齐可发布口径并发布计划，避免二次点击发布。"""
    if plan.plan_status == "CLOSED":
        raise AppError(409, "PLAN_CLOSED", "已结项计划不可自动发布")
    indicators = db.execute(select(OutputIndicator).where(OutputIndicator.plan_id == plan.id)).scalars().all()
    if not indicators:
        raise AppError(422, "PLAN_HAS_NO_INDICATORS", "计划至少需要一个完整量化指标")
    for indicator in indicators:
        unit = db.execute(select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code == indicator.unit_code)).scalars().first()
        value_kind = unit.value_kind if unit else "DECIMAL"
        if not indicator.calc_method:
            if value_kind == "BOOLEAN":
                indicator.calc_method = "BOOLEAN"
            elif value_kind == "RATIO":
                indicator.calc_method = "RATIO"
            else:
                indicator.calc_method = "CUMULATIVE" if value_kind != "NONE" else "FINAL"
        if value_kind != "NONE":
            indicator.target_direction = indicator.target_direction or "GE"
        indicator.statistical_rule = indicator.statistical_rule or indicator.indicator_desc or indicator.indicator_name
        if unit and unit.is_compound and not indicator.standard_unit_code:
            indicator.standard_unit_code = unit.unit_code
        if indicator.calc_method == "RATIO":
            indicator.numerator_rule = indicator.numerator_rule or "按指标描述中的分子口径统计"
            indicator.denominator_rule = indicator.denominator_rule or "按指标描述中的分母口径统计"
            indicator.baseline_year = indicator.baseline_year or plan.start_year
        indicator.parse_status = "CONFIRMED"
        indicator.confirmed_by = user.id
        indicator.confirmed_at = utcnow()
        indicator.indicator_status = "PUBLISHED"
        indicator.lock_version += 1
        targets = db.execute(
            select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == indicator.id)
        ).scalars().all()
        for target in targets:
            target.confirm_status = "CONFIRMED"
            target.confirmed_by = user.id
            target.confirmed_at = utcnow()
            target.lock_version += 1
    if plan.plan_status == "DRAFT":
        plan.plan_status = "PUBLISHED"
        plan.published_by = user.id
        plan.published_at = utcnow()
        plan.current_version_no += 1
    plan.lock_version += 1
    write_log(
        db,
        user,
        object_type="PLAN",
        object_id=plan.id,
        object_name=plan.plan_name,
        action_code="AUTO_PUBLISH",
        plan_id=plan.id,
        after={"indicator_count": len(indicators), "status": plan.plan_status},
    )
    return len(indicators)


def copy_plan(db: Session, source: ConstructionPlan, data: PlanCopyInput, user: SysUser) -> ConstructionPlan:
    start_year = data.start_year if data.start_year is not None else source.start_year
    end_year = data.end_year if data.end_year is not None else source.end_year
    if end_year < start_year:
        raise AppError(422, "INVALID_PLAN_YEARS", "结束年度不得早于开始年度")
    target = ConstructionPlan(
        plan_code=data.plan_code,
        directory_id=data.directory_id or source.directory_id,
        plan_name=data.plan_name,
        construction_scope=data.construction_scope or source.construction_scope,
        start_year=start_year,
        end_year=end_year,
        school_owner_id=data.school_owner_id or user.id,
        template_source_plan_id=source.id,
        copy_mode=data.copy_mode,
        plan_status="DRAFT",
        created_by=user.id,
    )
    db.add(target)
    db.flush()
    db.add(
        ProjectMember(
            plan_id=target.id,
            user_id=target.school_owner_id,
            member_role="SCHOOL_OWNER",
            is_primary=True,
            effective_from=utcnow(),
            created_by=user.id,
        )
    )
    source_members = db.execute(
        select(ProjectMember).where(
            ProjectMember.plan_id == source.id,
            ProjectMember.effective_to.is_(None),
            ProjectMember.member_role != "SCHOOL_OWNER",
        )
    ).scalars().all()
    seen_members: set[tuple[int, int | None, str]] = set()
    for member in source_members:
        key = (member.user_id, member.org_id, member.member_role)
        if key in seen_members:
            continue
        seen_members.add(key)
        db.add(
            ProjectMember(
                plan_id=target.id,
                user_id=member.user_id,
                org_id=member.org_id,
                member_role=member.member_role,
                is_primary=False,
                effective_from=utcnow(),
                created_by=user.id,
            )
        )
    directions = db.execute(
        select(ConstructionDirection).where(ConstructionDirection.plan_id == source.id).order_by(ConstructionDirection.sort_no)
    ).scalars().all()
    direction_map: dict[int, ConstructionDirection] = {}
    for old in directions:
        new = ConstructionDirection(
            plan_id=target.id,
            direction_code=old.direction_code,
            direction_name=old.direction_name,
            source_seq=old.source_seq,
            description=old.description,
            sort_no=old.sort_no,
            status=old.status,
            created_by=user.id,
        )
        db.add(new)
        db.flush()
        direction_map[old.id] = new
    indicators = db.execute(select(OutputIndicator).where(OutputIndicator.plan_id == source.id)).scalars().all()
    indicator_map: dict[int, OutputIndicator] = {}
    include_targets = data.copy_mode == "STRUCTURE_AND_TARGET"
    for old in indicators:
        values = {
            "professional_five_year_target_value": old.professional_five_year_target_value,
            "professional_five_year_target_bool": old.professional_five_year_target_bool,
            "professional_five_year_target_text": old.professional_five_year_target_text,
            "professional_five_year_target_raw": old.professional_five_year_target_raw,
            "school_five_year_target_value": old.school_five_year_target_value,
            "school_five_year_target_bool": old.school_five_year_target_bool,
            "school_five_year_target_text": old.school_five_year_target_text,
            "school_five_year_target_raw": old.school_five_year_target_raw,
        }
        if not include_targets:
            values = {key: None for key in values}
        new = OutputIndicator(
            plan_id=target.id,
            direction_id=direction_map[old.direction_id].id,
            indicator_code=old.indicator_code,
            indicator_name=old.indicator_name,
            indicator_desc=old.indicator_desc,
            indicator_type_code=old.indicator_type_code,
            unit_code=old.unit_code,
            standard_unit_code=old.standard_unit_code,
            unit_raw=old.unit_raw,
            target_direction=old.target_direction,
            calc_method=old.calc_method,
            statistical_rule=old.statistical_rule,
            baseline_value=old.baseline_value if include_targets else None,
            baseline_year=old.baseline_year,
            numerator_rule=old.numerator_rule,
            denominator_rule=old.denominator_rule,
            decimal_precision=old.decimal_precision,
            external_data_source=old.external_data_source,
            parse_status=old.parse_status if include_targets else "NEED_CONFIRM",
            indicator_status="DRAFT",
            created_by=user.id,
            **values,
        )
        db.add(new)
        db.flush()
        indicator_map[old.id] = new
        relations = db.execute(
            select(IndicatorDepartmentRel).where(IndicatorDepartmentRel.indicator_id == old.id)
        ).scalars().all()
        for relation in relations:
            db.add(
                IndicatorDepartmentRel(
                    indicator_id=new.id,
                    org_id=relation.org_id,
                    relation_type=relation.relation_type,
                    responsibility_desc=relation.responsibility_desc,
                    created_by=user.id,
                )
            )
        annual_targets = db.execute(
            select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == old.id)
        ).scalars().all()
        for old_target in annual_targets:
            if not start_year <= old_target.target_year <= end_year:
                continue
            target_values = {
                "professional_target_value": old_target.professional_target_value,
                "professional_target_bool": old_target.professional_target_bool,
                "professional_target_text": old_target.professional_target_text,
                "professional_target_raw": old_target.professional_target_raw,
                "school_target_value": old_target.school_target_value,
                "school_target_bool": old_target.school_target_bool,
                "school_target_text": old_target.school_target_text,
                "school_target_raw": old_target.school_target_raw,
            }
            if not include_targets:
                target_values = {key: None for key in target_values}
            db.add(
                IndicatorAnnualTarget(
                    indicator_id=new.id,
                    target_year=old_target.target_year,
                    school_division_raw=old_target.school_division_raw,
                    confirm_status="PENDING",
                    created_by=user.id,
                    **target_values,
                )
            )
        for year in range(start_year, end_year + 1):
            if year not in {item.target_year for item in annual_targets}:
                db.add(IndicatorAnnualTarget(indicator_id=new.id, target_year=year, created_by=user.id))
    db.flush()
    tasks = db.execute(
        select(CollegeTask).join(OutputIndicator, OutputIndicator.id == CollegeTask.indicator_id).where(OutputIndicator.plan_id == source.id)
    ).scalars().all()
    task_map: dict[int, CollegeTask] = {}
    for old in tasks:
        new = CollegeTask(
            task_code=new_code("TASK"),
            indicator_id=indicator_map[old.indicator_id].id,
            college_org_id=old.college_org_id,
            owner_user_id=old.owner_user_id,
            execution_scope=old.execution_scope,
            five_year_target_value=old.five_year_target_value if include_targets else None,
            five_year_target_bool=old.five_year_target_bool if include_targets else None,
            five_year_target_text=old.five_year_target_text if include_targets else None,
            professional_allocation_five_year_raw=old.professional_allocation_five_year_raw,
            task_status="DRAFT",
            created_by=user.id,
        )
        db.add(new)
        db.flush()
        task_map[old.id] = new
    for old_task_id, new_task in task_map.items():
        milestones = db.execute(
            select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == old_task_id)
        ).scalars().all()
        old_years = {item.target_year for item in milestones}
        for old in milestones:
            if not start_year <= old.target_year <= end_year:
                continue
            target_values = {
                "original_target_value": old.original_target_value,
                "original_target_bool": old.original_target_bool,
                "original_target_text": old.original_target_text,
                "current_target_value": old.current_target_value,
                "current_target_bool": old.current_target_bool,
                "current_target_text": old.current_target_text,
            }
            if not include_targets:
                target_values = {key: None for key in target_values}
            db.add(
                TaskAnnualMilestone(
                    task_id=new_task.id,
                    target_year=old.target_year,
                    professional_allocation_year_raw=old.professional_allocation_year_raw,
                    milestone_status="NOT_STARTED",
                    due_date=date(old.target_year, 12, 31),
                    created_by=user.id,
                    **target_values,
                )
            )
        for year in range(start_year, end_year + 1):
            if year not in old_years:
                db.add(
                    TaskAnnualMilestone(
                        task_id=new_task.id,
                        target_year=year,
                        due_date=date(year, 12, 31),
                        created_by=user.id,
                    )
                )
    db.flush()
    return target
