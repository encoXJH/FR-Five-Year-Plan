from decimal import Decimal
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import (
    AnnualCompensation,
    CalculationSnapshot,
    CollegeTask,
    ConstructionPlan,
    ExternalRecognition,
    IndicatorAnnualTarget,
    OutputIndicator,
    ReviewRecord,
    SysUser,
    TaskAnnualMilestone,
    TaskSubmission,
)
from app.utils import json_value, model_dict, utcnow
from app.services.values import compare_values
from app.services.audit import close_todos, create_todo, write_log


ZERO = Decimal("0")


def _recognized_parts(submission: TaskSubmission | None, review: ReviewRecord | None) -> dict[str, Any]:
    if not review:
        return {"value": None, "bool": None, "text": None, "numerator": None, "denominator": None}
    return {
        "value": review.recognized_value,
        "bool": review.recognized_bool,
        "text": review.recognized_text,
        "numerator": review.recognized_numerator,
        "denominator": review.recognized_denominator,
    }


def latest_effective_review(db: Session, submission_id: int) -> ReviewRecord | None:
    return db.execute(
        select(ReviewRecord)
        .where(ReviewRecord.submission_id == submission_id, ReviewRecord.review_result == "APPROVED")
        .order_by(ReviewRecord.id.desc())
    ).scalars().first()


def evaluate_annual(
    indicator: OutputIndicator,
    target: dict[str, Any],
    recognized: dict[str, Any],
    *,
    previous_absolute: Decimal | None = None,
    use_cycle_baseline: bool = True,
) -> dict[str, Any]:
    method = indicator.calc_method
    result: dict[str, Any] = {
        "achieved": None,
        "actual": None,
        "target": target.get("value") if target.get("value") is not None else target.get("bool") if target.get("bool") is not None else target.get("text"),
        "formula": method or "UNCONFIGURED",
        "status": "CALCULATED",
        "message": None,
    }
    if not method:
        result.update(status="UNCALCULABLE", message="尚未配置核算方式")
        return result
    if method == "EXTERNAL" and not any(recognized.get(key) is not None for key in ("value", "bool", "text")):
        result.update(status="UNCALCULABLE", message="尚无外部认定结果")
        return result
    if method == "BOOLEAN":
        if target.get("bool") is None or recognized.get("bool") is None:
            result.update(status="UNCALCULABLE", message="是否型目标或认定值为空")
            return result
        actual = recognized["bool"]
        achieved = actual is True if target["bool"] is True else actual is target["bool"]
        result.update(actual=actual, achieved=achieved, formula="认定是/否 = 目标是/否")
        return result
    if target.get("value") is None:
        if method == "FINAL" and target.get("text") in {None, "—"}:
            result.update(status="NOT_APPLICABLE", message="本年度不设置期末型阶段目标")
            return result
        result.update(status="UNCALCULABLE", message="年度目标未量化或不适用")
        return result
    target_value = target["value"]
    actual = recognized.get("value")
    if method == "RATIO":
        numerator = recognized.get("numerator")
        denominator = recognized.get("denominator")
        if numerator is None or denominator is None:
            result.update(status="UNCALCULABLE", message="比率型缺少认定分子或分母")
            return result
        if denominator == 0:
            result.update(status="UNCALCULABLE", message="分母为 0，无法自动核算")
            return result
        actual = numerator / denominator * Decimal("100")
        result["numerator"] = numerator
        result["denominator"] = denominator
        result["formula"] = "认定分子 ÷ 认定分母 × 100%，再按目标方向比较"
    elif method == "GROWTH":
        baseline = previous_absolute if previous_absolute is not None else indicator.baseline_value if use_cycle_baseline else None
        if actual is None or baseline is None:
            result.update(status="UNCALCULABLE", message="增长率型缺少基期绝对值或报告期绝对值")
            return result
        if baseline == 0:
            result.update(status="UNCALCULABLE", message="基期为 0，无法自动核算增长率")
            return result
        result["baseline"] = baseline
        result["reported_absolute"] = actual
        result["growth_amount"] = actual - baseline
        actual = (actual - baseline) / baseline * Decimal("100")
        result["formula"] = "（报告期绝对值 − 基期绝对值）÷ 基期绝对值 × 100%"
    if actual is None:
        result.update(status="UNCALCULABLE", message="认定完成值为空")
        return result
    result["actual"] = actual
    result["achieved"] = compare_values(actual, target_value, indicator.target_direction)
    return result


def milestone_target(milestone: TaskAnnualMilestone, original: bool = False) -> dict[str, Any]:
    prefix = "original_target" if original else "current_target"
    return {
        "value": getattr(milestone, f"{prefix}_value"),
        "bool": getattr(milestone, f"{prefix}_bool"),
        "text": getattr(milestone, f"{prefix}_text"),
    }


def save_calculation_snapshot(
    db: Session,
    *,
    plan: ConstructionPlan,
    indicator: OutputIndicator,
    task: CollegeTask | None,
    milestone: TaskAnnualMilestone | None,
    scope_type: str,
    result: dict[str, Any],
    original_target: dict[str, Any] | None,
    adjusted_target: dict[str, Any] | None,
    recognized: dict[str, Any] | None,
    target_year: int | None,
    target_version_no: int | None,
    user_id: int,
) -> CalculationSnapshot:
    snapshot = CalculationSnapshot(
        plan_id=plan.id,
        indicator_id=indicator.id,
        task_id=task.id if task else None,
        milestone_id=milestone.id if milestone else None,
        target_year=target_year,
        scope_type=scope_type,
        target_version_no=target_version_no,
        original_target_json=json_value(original_target) if original_target is not None else None,
        adjusted_target_json=json_value(adjusted_target) if adjusted_target is not None else None,
        recognized_json=json_value(recognized) if recognized is not None else None,
        formula_text=str(result.get("formula", "")),
        calculation_status=result.get("status", "CALCULATED"),
        result_json=json_value(result),
        computed_at=utcnow(),
        created_by=user_id,
    )
    db.add(snapshot)
    return snapshot


def recalculate_milestone(
    db: Session,
    milestone: TaskAnnualMilestone,
    task: CollegeTask,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    user_id: int,
    review: ReviewRecord | None,
) -> dict[str, Any]:
    submission = db.get(TaskSubmission, milestone.current_submission_id) if milestone.current_submission_id else None
    previous_absolute = None
    if indicator.calc_method == "GROWTH":
        previous = db.execute(
            select(TaskAnnualMilestone).where(
                TaskAnnualMilestone.task_id == task.id,
                TaskAnnualMilestone.target_year == milestone.target_year - 1,
            )
        ).scalars().first()
        previous_absolute = previous.recognized_value if previous else indicator.baseline_value
    recognized = _recognized_parts(submission, review)
    result = evaluate_annual(
        indicator,
        milestone_target(milestone),
        recognized,
        previous_absolute=previous_absolute,
        use_cycle_baseline=milestone.target_year == plan.start_year,
    )
    milestone.recognized_value = review.recognized_value if review else None
    milestone.recognized_bool = review.recognized_bool if review else None
    milestone.recognized_text = review.recognized_text if review else None
    milestone.last_review_id = review.id if review else milestone.last_review_id
    milestone.recognized_at = review.reviewed_at if review else milestone.recognized_at
    if review:
        milestone.milestone_status = "COMPLETED" if result["achieved"] is True else "RECOGNIZED_UNMET"
        if result["status"] == "UNCALCULABLE":
            milestone.milestone_status = "RECOGNIZED_UNMET"
    save_calculation_snapshot(
        db,
        plan=plan,
        indicator=indicator,
        task=task,
        milestone=milestone,
        scope_type="ANNUAL",
        result=result,
        original_target=milestone_target(milestone, original=True),
        adjusted_target=milestone_target(milestone),
        recognized=recognized,
        target_year=milestone.target_year,
        target_version_no=milestone.target_version_no,
        user_id=user_id,
    )
    return result


def reverse_compensations(db: Session, task_id: int, reason: str) -> list[AnnualCompensation]:
    rows = db.execute(
        select(AnnualCompensation).where(
            AnnualCompensation.task_id == task_id,
            AnnualCompensation.compensation_status == "EFFECTIVE",
        )
    ).scalars().all()
    now = utcnow()
    for row in rows:
        row.compensation_status = "REVERSED"
        row.reversed_reason = reason
        row.reversed_at = now
    return rows


def rebuild_compensation(
    db: Session,
    task: CollegeTask,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    user_id: int,
    *,
    reverse_existing: bool,
) -> None:
    milestones = db.execute(
        select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == task.id).order_by(TaskAnnualMilestone.target_year)
    ).scalars().all()
    if indicator.calc_method != "CUMULATIVE":
        for milestone in milestones:
            milestone.shortfall_value = None
            milestone.covered_value = None
        return
    operator = db.get(SysUser, user_id)
    if reverse_existing:
        reversed_rows = reverse_compensations(db, task.id, "审核认定或目标变化，重新分配补位余量")
        if operator:
            for reversed_row in reversed_rows:
                write_log(
                    db,
                    operator,
                    object_type="COMPENSATION",
                    object_id=reversed_row.id,
                    action_code="COMPENSATION_REVERSE",
                    plan_id=plan.id,
                    college_org_id=task.college_org_id,
                    reason=reversed_row.reversed_reason,
                    related_order_type="COMPENSATION",
                    related_order_id=reversed_row.id,
                )
    effective = db.execute(
        select(AnnualCompensation).where(
            AnnualCompensation.task_id == task.id,
            AnnualCompensation.compensation_status == "EFFECTIVE",
        )
    ).scalars().all()
    for milestone in milestones:
        milestone.covered_value = sum(
            (row.compensation_amount for row in effective if row.deficit_milestone_id == milestone.id), ZERO
        )
    for milestone in milestones:
        target = milestone.current_target_value
        recognized = milestone.recognized_value or ZERO
        covered = milestone.covered_value or ZERO
        milestone.shortfall_value = max(target - recognized - covered, ZERO) if target is not None else None
        if milestone.recognized_at and target is not None:
            milestone.milestone_status = "COMPLETED" if milestone.shortfall_value == 0 else "RECOGNIZED_UNMET"
            if milestone.shortfall_value == 0:
                close_todos(db, "UNMET", milestone.id)
    for surplus in milestones:
        if surplus.current_target_value is None or surplus.recognized_value is None or not surplus.last_review_id:
            continue
        consumed = db.execute(
            select(AnnualCompensation).where(
                AnnualCompensation.surplus_milestone_id == surplus.id,
                AnnualCompensation.compensation_status == "EFFECTIVE",
            )
        ).scalars().all()
        used = sum((row.compensation_amount for row in consumed), ZERO)
        available = max(surplus.recognized_value - surplus.current_target_value - used, ZERO)
        if available <= 0:
            continue
        deficits = [m for m in milestones if m.target_year < surplus.target_year and (m.shortfall_value or ZERO) > 0]
        for deficit in deficits:
            if available <= 0:
                break
            amount = min(available, deficit.shortfall_value)
            row = AnnualCompensation(
                task_id=task.id,
                deficit_milestone_id=deficit.id,
                surplus_milestone_id=surplus.id,
                source_review_id=surplus.last_review_id,
                compensation_amount=amount,
                created_by=user_id,
            )
            db.add(row)
            db.flush()
            if operator:
                write_log(
                    db,
                    operator,
                    object_type="COMPENSATION",
                    object_id=row.id,
                    action_code="COMPENSATE",
                    plan_id=plan.id,
                    college_org_id=task.college_org_id,
                    after=json_value({"deficit_year": deficit.target_year, "surplus_year": surplus.target_year, "amount": amount}),
                    related_order_type="COMPENSATION",
                    related_order_id=row.id,
                )
            deficit.covered_value = (deficit.covered_value or ZERO) + amount
            deficit.shortfall_value = max(deficit.shortfall_value - amount, ZERO)
            if deficit.shortfall_value == 0 and deficit.recognized_at:
                deficit.milestone_status = "COMPLETED"
                close_todos(db, "UNMET", deficit.id)
            deficit.has_adjustment = True
            surplus.has_adjustment = True
            available -= amount
    save_five_year_snapshot(db, task, indicator, plan, milestones, user_id)


def save_five_year_snapshot(
    db: Session,
    task: CollegeTask,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    milestones: list[TaskAnnualMilestone],
    user_id: int,
) -> dict[str, Any]:
    method = indicator.calc_method
    numeric_target = task.five_year_target_value
    target = numeric_target if method != "BOOLEAN" else task.five_year_target_bool
    original_target = None
    if task.assigned_snapshot_json and isinstance(task.assigned_snapshot_json, dict):
        original_task = task.assigned_snapshot_json.get("task") or {}
        original_target = original_task.get("five_year_target_bool") if method == "BOOLEAN" else original_task.get("five_year_target_value")
    if original_target is None:
        original_target = target
    actual: Decimal | bool | str | None = None
    achieved = None
    status = "CALCULATED"
    formula = method or "UNCONFIGURED"
    message = None
    if method == "CUMULATIVE":
        actual = sum((m.recognized_value or ZERO for m in milestones), ZERO)
        formula = "Σ 已审核认定增量 ÷ 调整后五年目标"
        if numeric_target is not None:
            achieved = compare_values(actual, numeric_target, indicator.target_direction)
    elif method in {"ANNUAL_QUOTA", "RATIO", "BOOLEAN"}:
        formula = "逐年度独立认定，不跨年度加总"
        annual = [m.milestone_status for m in milestones if m.recognized_at]
        achieved = bool(annual) and all(item == "COMPLETED" for item in annual)
    elif method in {"FINAL", "GROWTH", "EXTERNAL"}:
        final = next((m for m in milestones if m.target_year == plan.end_year), None)
        actual = final.recognized_value if final else None
        if method == "GROWTH":
            baseline = indicator.baseline_value
            if baseline in {None, ZERO} or actual is None:
                status, message = "UNCALCULABLE", "基期为 0 或期末绝对值未认定"
            else:
                actual = (actual - baseline) / baseline * Decimal("100")
                formula = "（期末认定绝对值 − 周期基期）÷ 周期基期 × 100%"
        if status == "CALCULATED" and numeric_target is not None and actual is not None:
            achieved = compare_values(actual, numeric_target, indicator.target_direction)
    else:
        status, message = "UNCALCULABLE", "尚未配置核算方式"
    result = {
        "achieved": achieved,
        "actual": actual,
        "target": target,
        "formula": formula,
        "status": status,
        "message": message,
        "annual_shortfalls": {str(m.target_year): m.shortfall_value for m in milestones},
        "annual_covered": {str(m.target_year): m.covered_value for m in milestones},
    }
    save_calculation_snapshot(
        db,
        plan=plan,
        indicator=indicator,
        task=task,
        milestone=None,
        scope_type="FIVE_YEAR",
        result=result,
        original_target={"value": original_target},
        adjusted_target={"value": target},
        recognized={"value": actual},
        target_year=None,
        target_version_no=task.current_target_version_no,
        user_id=user_id,
    )
    return result


def calculate_external(
    db: Session,
    indicator: OutputIndicator,
    plan: ConstructionPlan,
    recognition: ExternalRecognition,
    user_id: int,
) -> dict[str, Any]:
    target = db.execute(
        select(IndicatorAnnualTarget).where(
            IndicatorAnnualTarget.indicator_id == indicator.id,
            IndicatorAnnualTarget.target_year == recognition.target_year,
        )
    ).scalars().first()
    target_data = {
        "value": target.professional_target_value if target else None,
        "bool": target.professional_target_bool if target else None,
        "text": target.professional_target_text if target else None,
    }
    recognized = {
        "value": recognition.recognized_value,
        "bool": recognition.recognized_bool,
        "text": recognition.recognized_text,
        "numerator": None,
        "denominator": None,
    }
    result = evaluate_annual(indicator, target_data, recognized)
    save_calculation_snapshot(
        db,
        plan=plan,
        indicator=indicator,
        task=None,
        milestone=None,
        scope_type="EXTERNAL_ANNUAL",
        result=result,
        original_target=target_data,
        adjusted_target=target_data,
        recognized=recognized,
        target_year=recognition.target_year,
        target_version_no=target.target_version_no if target else None,
        user_id=user_id,
    )
    recognitions = db.execute(
        select(ExternalRecognition)
        .where(ExternalRecognition.indicator_id == indicator.id)
        .order_by(ExternalRecognition.target_year, ExternalRecognition.id)
    ).scalars().all()
    latest_by_year = {item.target_year: item for item in recognitions}
    final_recognition = latest_by_year.get(plan.end_year)
    five_target = {
        "value": indicator.professional_five_year_target_value,
        "bool": indicator.professional_five_year_target_bool,
        "text": indicator.professional_five_year_target_text,
    }
    five_recognized = {
        "value": final_recognition.recognized_value if final_recognition else None,
        "bool": final_recognition.recognized_bool if final_recognition else None,
        "text": final_recognition.recognized_text if final_recognition else None,
        "numerator": None,
        "denominator": None,
    }
    five_result = evaluate_annual(indicator, five_target, five_recognized)
    five_result["formula"] = "以建设期末年度最新外部认定值结算五年目标"
    if not final_recognition:
        five_result.update(status="UNCALCULABLE", message="期末年度尚无外部认定结果", achieved=None)
    save_calculation_snapshot(
        db,
        plan=plan,
        indicator=indicator,
        task=None,
        milestone=None,
        scope_type="EXTERNAL_FIVE_YEAR",
        result=five_result,
        original_target=five_target,
        adjusted_target=five_target,
        recognized=five_recognized,
        target_year=None,
        target_version_no=indicator.target_version_no,
        user_id=user_id,
    )
    return result
