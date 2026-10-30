import csv
import io
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, Response
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.database import get_db
from app.errors import AppError
from app.models import (
    AnnualCarryover,
    AnnualCompensation,
    CalculationSnapshot,
    CollegeTask,
    ConstructionDirection,
    ConstructionDirectory,
    ConstructionPlan,
    CrossCollegeTransfer,
    DictIndicatorUnit,
    EvidenceMaterial,
    OperationLog,
    OutputIndicator,
    ReviewRecord,
    SourceImportFile,
    SysOrgUnit,
    SysUser,
    SystemConfig,
    TaskAnnualMilestone,
    TaskSubmission,
    TodoNotification,
)
from app.security import active_role_codes, get_current_user, require_roles
from app.services.audit import write_log
from app.services.scope import ADMIN, COLLEGE, LEADER, SCHOOL, get_milestone, get_plan, get_task, is_school_owner, visible_plan_ids
from app.utils import json_value, model_dict, new_code, utcnow


from app.responses import DecimalJSONResponse

router = APIRouter(tags=["甘特待办与日志"], default_response_class=DecimalJSONResponse)
ZERO = Decimal("0")


def _cell(milestone: TaskAnnualMilestone) -> dict:
    result = model_dict(milestone)
    target = milestone.current_target_value
    actual = milestone.recognized_value
    progress = None
    capped = None
    excess = None
    if target is not None and actual is not None and target != 0:
        progress = actual / target * Decimal("100")
        capped = min(max(progress, ZERO), Decimal("100"))
        excess = max(actual - target, ZERO)
    is_overdue = bool(milestone.due_date and milestone.due_date < utcnow().date() and milestone.milestone_status not in {"COMPLETED"} and milestone.target_year <= utcnow().year)
    result.update({"progress_percent": progress, "bar_percent": capped, "excess_value": excess, "adjustment_marker": "已调整" if milestone.has_adjustment else None, "is_overdue": is_overdue})
    return result


NUMERIC_PROGRESS_METHODS = {"CUMULATIVE", "ANNUAL_QUOTA", "FINAL"}


def _status_stats(milestones: list[TaskAnnualMilestone]) -> dict:
    counts: dict[str, int] = {}
    for item in milestones:
        counts[item.milestone_status] = counts.get(item.milestone_status, 0) + 1
    return {
        "milestone_count": len(milestones),
        "completed": counts.get("COMPLETED", 0),
        "under_review": counts.get("UNDER_REVIEW", 0),
        "returned": counts.get("RETURNED", 0),
        "unmet": counts.get("RECOGNIZED_UNMET", 0),
        "pending_report": counts.get("PENDING_REPORT", 0),
        "not_started": counts.get("NOT_STARTED", 0),
        "draft": counts.get("DRAFT", 0),
    }


def _percent(numerator, denominator) -> float:
    if not denominator:
        return 0.0
    return round(float(numerator) / float(denominator) * 100, 2)


def _progress(milestones: list[TaskAnnualMilestone], calc_method: str | None) -> dict:
    stats = _status_stats(milestones)
    if calc_method in NUMERIC_PROGRESS_METHODS:
        numeric_items = [item for item in milestones if item.current_target_value is not None]
        if numeric_items:
            target = sum((item.current_target_value for item in numeric_items), ZERO)
            recognized = sum((item.recognized_value or ZERO for item in numeric_items), ZERO)
            return {
                **stats,
                "progress_basis": "VALUE",
                "target_value": float(target),
                "recognized_value": float(recognized),
                "progress_percent": round(min(max(_percent(recognized, target), 0.0), 100.0), 2),
            }
    return {
        **stats,
        "progress_basis": "COUNT",
        "target_value": None,
        "recognized_value": None,
        "progress_percent": round(min(max(_percent(stats["completed"], stats["milestone_count"]), 0.0), 100.0), 2),
    }


@router.get("/dashboard/overview")
def dashboard_overview(
    plan_id: int,
    user: SysUser = Depends(require_roles(SCHOOL, LEADER)),
    db: Session = Depends(get_db),
):
    plan = get_plan(db, user, plan_id)
    directions = db.execute(
        select(ConstructionDirection)
        .where(ConstructionDirection.plan_id == plan.id)
        .order_by(ConstructionDirection.sort_no, ConstructionDirection.id)
    ).scalars().all()
    direction_by_id = {item.id: item for item in directions}
    indicators = db.execute(
        select(OutputIndicator)
        .where(OutputIndicator.plan_id == plan.id)
        .order_by(OutputIndicator.indicator_code)
    ).scalars().all()
    tasks = db.execute(
        select(CollegeTask).where(
            CollegeTask.indicator_id.in_([item.id for item in indicators] or [-1]),
            CollegeTask.task_status.notin_(["DRAFT", "REVOKED"]),
        )
    ).scalars().all()
    tasks_by_indicator: dict[int, list[CollegeTask]] = {}
    tasks_by_college: dict[int, list[CollegeTask]] = {}
    task_by_id = {}
    for task in tasks:
        tasks_by_indicator.setdefault(task.indicator_id, []).append(task)
        tasks_by_college.setdefault(task.college_org_id, []).append(task)
        task_by_id[task.id] = task
    milestones = db.execute(
        select(TaskAnnualMilestone)
        .where(TaskAnnualMilestone.task_id.in_(list(task_by_id) or [-1]))
        .order_by(TaskAnnualMilestone.target_year)
    ).scalars().all()
    milestones_by_task: dict[int, list[TaskAnnualMilestone]] = {}
    milestones_by_year: dict[int, list[TaskAnnualMilestone]] = {}
    for milestone in milestones:
        milestones_by_task.setdefault(milestone.task_id, []).append(milestone)
        milestones_by_year.setdefault(milestone.target_year, []).append(milestone)
    units = {
        item.unit_code: item
        for item in db.execute(
            select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code.in_([item.unit_code for item in indicators] or [-1]))
        ).scalars().all()
    }
    colleges = {
        item.id: item
        for item in db.execute(
            select(SysOrgUnit).where(SysOrgUnit.id.in_(list(tasks_by_college) or [-1]))
        ).scalars().all()
    }

    indicator_rows = []
    for indicator in indicators:
        indicator_tasks = tasks_by_indicator.get(indicator.id, [])
        indicator_milestones = [
            milestone for task in indicator_tasks for milestone in milestones_by_task.get(task.id, [])
        ]
        task_status_counts: dict[str, int] = {}
        for task in indicator_tasks:
            task_status_counts[task.task_status] = task_status_counts.get(task.task_status, 0) + 1
        unit = units.get(indicator.unit_code)
        indicator_rows.append({
            "id": indicator.id,
            "indicator_code": indicator.indicator_code,
            "indicator_name": indicator.indicator_name,
            "direction_id": indicator.direction_id,
            "direction_name": direction_by_id[indicator.direction_id].direction_name if indicator.direction_id in direction_by_id else None,
            "indicator_type_code": indicator.indicator_type_code,
            "calc_method": indicator.calc_method,
            "unit_code": indicator.unit_code,
            "unit_name": unit.unit_name if unit else indicator.unit_raw,
            "value_kind": unit.value_kind if unit else None,
            "task_count": len(indicator_tasks),
            "task_status_counts": task_status_counts,
            **_progress(indicator_milestones, indicator.calc_method),
        })

    direction_rows = []
    for direction in directions:
        direction_indicators = [item for item in indicators if item.direction_id == direction.id]
        direction_milestones = [
            milestone
            for indicator in direction_indicators
            for task in tasks_by_indicator.get(indicator.id, [])
            for milestone in milestones_by_task.get(task.id, [])
        ]
        stats = _status_stats(direction_milestones)
        direction_rows.append({
            "id": direction.id,
            "direction_code": direction.direction_code,
            "direction_name": direction.direction_name,
            "indicator_count": len(direction_indicators),
            "task_count": sum(len(tasks_by_indicator.get(item.id, [])) for item in direction_indicators),
            **stats,
            "progress_percent": round(min(max(_percent(stats["completed"], stats["milestone_count"]), 0.0), 100.0), 2),
        })

    college_rows = []
    for college_id, college_tasks in tasks_by_college.items():
        college_milestones = [
            milestone for task in college_tasks for milestone in milestones_by_task.get(task.id, [])
        ]
        stats = _status_stats(college_milestones)
        college_rows.append({
            "college_org_id": college_id,
            "college_org_name": colleges[college_id].org_name if college_id in colleges else str(college_id),
            "task_count": len(college_tasks),
            **stats,
            "progress_percent": round(min(max(_percent(stats["completed"], stats["milestone_count"]), 0.0), 100.0), 2),
        })
    college_rows.sort(key=lambda item: item["college_org_name"])

    year_rows = []
    for year in range(plan.start_year, plan.end_year + 1):
        stats = _status_stats(milestones_by_year.get(year, []))
        year_rows.append({
            "target_year": year,
            **stats,
            "completion_percent": round(min(max(_percent(stats["completed"], stats["milestone_count"]), 0.0), 100.0), 2),
        })

    summary_stats = _status_stats(milestones)
    return json_value({
        "plan": model_dict(plan),
        "summary": {
            **summary_stats,
            "direction_count": len(directions),
            "indicator_count": len(indicators),
            "task_count": len(tasks),
            "completion_percent": round(min(max(_percent(summary_stats["completed"], summary_stats["milestone_count"]), 0.0), 100.0), 2),
            "reviewed_percent": round(min(max(_percent(summary_stats["completed"] + summary_stats["unmet"], summary_stats["milestone_count"]), 0.0), 100.0), 2),
        },
        "directions": direction_rows,
        "indicators": indicator_rows,
        "colleges": college_rows,
        "years": year_rows,
    })


@router.get("/gantt")
def gantt(
    plan_id: int,
    direction_id: int | None = None,
    college_org_id: int | None = None,
    owner_user_id: int | None = None,
    indicator_type_code: str | None = None,
    target_year: int | None = None,
    milestone_status: str | None = None,
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    plan = get_plan(db, user, plan_id)
    directions_query = select(ConstructionDirection).where(ConstructionDirection.plan_id == plan.id).order_by(ConstructionDirection.sort_no)
    if direction_id:
        directions_query = directions_query.where(ConstructionDirection.id == direction_id)
    directions = db.execute(directions_query).scalars().all()
    user_roles = active_role_codes(db, user.id)
    college_only = COLLEGE in user_roles and not is_school_owner(db, user, plan)
    tree = []
    for direction in directions:
        indicator_query = select(OutputIndicator).where(OutputIndicator.direction_id == direction.id)
        if indicator_type_code:
            indicator_query = indicator_query.where(OutputIndicator.indicator_type_code == indicator_type_code)
        indicators = db.execute(indicator_query).scalars().all()
        indicator_nodes = []
        for indicator in indicators:
            task_query = select(CollegeTask).where(CollegeTask.indicator_id == indicator.id)
            if college_only:
                task_query = task_query.where(CollegeTask.college_org_id == user.primary_org_id, CollegeTask.task_status != "DRAFT")
            if college_org_id:
                task_query = task_query.where(CollegeTask.college_org_id == college_org_id)
            if owner_user_id:
                task_query = task_query.where(CollegeTask.owner_user_id == owner_user_id)
            tasks = db.execute(task_query.order_by(CollegeTask.id)).scalars().all()
            task_nodes = []
            status_counts: dict[str, int] = {}
            for task in tasks:
                milestone_query = select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == task.id)
                if target_year:
                    milestone_query = milestone_query.where(TaskAnnualMilestone.target_year == target_year)
                if milestone_status:
                    milestone_query = milestone_query.where(TaskAnnualMilestone.milestone_status == milestone_status)
                milestones = db.execute(milestone_query.order_by(TaskAnnualMilestone.target_year)).scalars().all()
                if (target_year or milestone_status) and not milestones:
                    continue
                for milestone in milestones:
                    status_counts[milestone.milestone_status] = status_counts.get(milestone.milestone_status, 0) + 1
                college = db.get(SysOrgUnit, task.college_org_id)
                task_nodes.append({**model_dict(task), "college_org_name": college.org_name if college else None, "milestones": [_cell(item) for item in milestones]})
            if task_nodes or (not college_only and not college_org_id and not owner_user_id and not target_year and not milestone_status):
                indicator_nodes.append({**model_dict(indicator), "tasks": task_nodes, "status_counts": status_counts})
        if indicator_nodes:
            aggregate: dict[str, int] = {}
            for indicator in indicator_nodes:
                for status, count in indicator["status_counts"].items():
                    aggregate[status] = aggregate.get(status, 0) + count
            tree.append({**model_dict(direction), "indicators": indicator_nodes, "status_counts": aggregate})
    return json_value({
        "plan": model_dict(plan),
        "years": [target_year] if target_year else list(range(plan.start_year, plan.end_year + 1)),
        "items": tree,
        "empty": not any(indicator["tasks"] for direction in tree for indicator in direction["indicators"]),
        "filters": json_value({"direction_id": direction_id, "college_org_id": college_org_id, "owner_user_id": owner_user_id, "indicator_type_code": indicator_type_code, "target_year": target_year, "milestone_status": milestone_status}),
    })


@router.get("/gantt/milestones/{milestone_id}")
def gantt_node(milestone_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    milestone, task, indicator, plan = get_milestone(db, user, milestone_id)
    result = _cell(milestone)
    result["task"] = model_dict(task)
    result["indicator"] = model_dict(indicator)
    current = db.get(TaskSubmission, milestone.current_submission_id) if milestone.current_submission_id else None
    result["latest_submission"] = model_dict(current) if current else None
    if current:
        materials = db.execute(select(EvidenceMaterial).where(EvidenceMaterial.submission_id == current.id)).scalars().all()
        result["materials"] = [{**model_dict(item, exclude={"object_key", "preview_object_key"}), "preview_url": f"/api/v1/materials/{item.id}/preview"} for item in materials]
    reviews = db.execute(select(ReviewRecord).join(TaskSubmission, TaskSubmission.id == ReviewRecord.submission_id).where(TaskSubmission.milestone_id == milestone.id).order_by(ReviewRecord.id)).scalars().all()
    result["reviews"] = [model_dict(item) for item in reviews]
    carryovers = db.execute(select(AnnualCarryover).where(or_(AnnualCarryover.source_milestone_id == milestone.id, AnnualCarryover.target_milestone_id == milestone.id))).scalars().all()
    compensations = db.execute(select(AnnualCompensation).where(or_(AnnualCompensation.deficit_milestone_id == milestone.id, AnnualCompensation.surplus_milestone_id == milestone.id))).scalars().all()
    transfers = db.execute(select(CrossCollegeTransfer).where(or_(CrossCollegeTransfer.source_task_id == task.id, CrossCollegeTransfer.target_task_id == task.id))).scalars().all()
    result["adjustments"] = {"carryovers": [model_dict(item) for item in carryovers], "compensations": [model_dict(item) for item in compensations], "transfers": [model_dict(item) for item in transfers]}
    return json_value(result)


@router.get("/tasks/{task_id}/calculations")
def task_calculations(task_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    task, indicator, plan = get_task(db, user, task_id)
    milestones = db.execute(select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == task.id).order_by(TaskAnnualMilestone.target_year)).scalars().all()
    snapshots = db.execute(select(CalculationSnapshot).where(CalculationSnapshot.task_id == task.id).order_by(CalculationSnapshot.id.desc()).limit(50)).scalars().all()
    actual = sum((item.recognized_value or ZERO for item in milestones), ZERO) if indicator.calc_method == "CUMULATIVE" else None
    return json_value({
        "task_id": task.id,
        "calc_method": indicator.calc_method,
        "five_year_target": task.five_year_target_value,
        "five_year_recognized_total": actual,
        "attainment_rate": actual / task.five_year_target_value * Decimal("100") if actual is not None and task.five_year_target_value not in {None, ZERO} else None,
        "annual": [_cell(item) for item in milestones],
        "snapshots": [model_dict(item) for item in snapshots],
    })


@router.get("/todos")
def list_todos(todo_status: str | None = None, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    query = select(TodoNotification).where(TodoNotification.recipient_user_id == user.id)
    if todo_status:
        query = query.where(TodoNotification.todo_status == todo_status)
    rows = db.execute(query.order_by(TodoNotification.id.desc())).scalars().all()
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}


@router.post("/todos/{todo_id}/read")
def read_todo(todo_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(TodoNotification, todo_id)
    if not row or row.recipient_user_id != user.id:
        raise AppError(404, "TODO_NOT_FOUND", "待办不存在")
    if row.todo_status == "PENDING":
        row.todo_status = "READ"
        row.read_at = utcnow()
    db.commit()
    return model_dict(row)


def _log_scope_query(db: Session, user: SysUser):
    query = select(OperationLog)
    user_roles = active_role_codes(db, user.id)
    if ADMIN in user_roles:
        return query
    business_roles = user_roles.intersection({SCHOOL, LEADER, COLLEGE})
    if business_roles:
        plan_ids = visible_plan_ids(db, user)
        query = query.where(OperationLog.plan_id.in_(plan_ids or [-1]))
        if COLLEGE in user_roles and not user_roles.intersection({SCHOOL, LEADER}):
            query = query.where(OperationLog.college_org_id == user.primary_org_id)
        return query
    return query.where(OperationLog.id == -1)


def _filtered_logs(db: Session, user: SysUser, filters: dict[str, Any]):
    query = _log_scope_query(db, user)
    for key, column in [
        ("plan_id", OperationLog.plan_id),
        ("college_org_id", OperationLog.college_org_id),
        ("object_type", OperationLog.object_type),
        ("object_id", OperationLog.object_id),
        ("action_code", OperationLog.action_code),
        ("operator_user_id", OperationLog.operator_user_id),
    ]:
        if filters.get(key) not in (None, ""):
            query = query.where(column == filters[key])
    if filters.get("start_time"):
        query = query.where(OperationLog.operated_at >= filters["start_time"])
    if filters.get("end_time"):
        query = query.where(OperationLog.operated_at <= filters["end_time"])
    keyword = str(filters.get("keyword") or "").strip()
    if keyword:
        like = f"%{keyword}%"
        conditions = [
            OperationLog.log_no.like(like),
            OperationLog.object_name.like(like),
            OperationLog.operation_reason.like(like),
            OperationLog.related_order_type.like(like),
            OperationLog.object_type.like(like),
            OperationLog.action_code.like(like),
            SysUser.user_name.like(like),
            SysUser.account_name.like(like),
            ConstructionPlan.plan_name.like(like),
            SysOrgUnit.org_name.like(like),
        ]
        if keyword.isdigit():
            conditions.append(OperationLog.related_order_id == int(keyword))
        query = (
            query.outerjoin(SysUser, SysUser.id == OperationLog.operator_user_id)
            .outerjoin(ConstructionPlan, ConstructionPlan.id == OperationLog.plan_id)
            .outerjoin(SysOrgUnit, SysOrgUnit.id == OperationLog.college_org_id)
            .where(or_(*conditions))
        )
    return query


def _object_name_map(db: Session, object_type: str, ids: set[int]) -> dict[int, str | None]:
    if not ids:
        return {}
    if object_type == "USER":
        return {item.id: item.user_name for item in db.execute(select(SysUser).where(SysUser.id.in_(ids))).scalars().all()}
    if object_type == "PLAN":
        return {item.id: item.plan_name for item in db.execute(select(ConstructionPlan).where(ConstructionPlan.id.in_(ids))).scalars().all()}
    if object_type == "DIRECTION":
        return {item.id: item.direction_name for item in db.execute(select(ConstructionDirection).where(ConstructionDirection.id.in_(ids))).scalars().all()}
    if object_type == "INDICATOR":
        return {item.id: item.indicator_name for item in db.execute(select(OutputIndicator).where(OutputIndicator.id.in_(ids))).scalars().all()}
    if object_type == "TASK":
        rows = db.execute(
            select(CollegeTask.id, OutputIndicator.indicator_name)
            .join(OutputIndicator, OutputIndicator.id == CollegeTask.indicator_id)
            .where(CollegeTask.id.in_(ids))
        ).all()
        return {row[0]: row[1] for row in rows}
    if object_type == "TRANSFER":
        return {item.id: item.transfer_no for item in db.execute(select(CrossCollegeTransfer).where(CrossCollegeTransfer.id.in_(ids))).scalars().all()}
    if object_type == "CARRYOVER":
        return {item.id: item.carryover_no for item in db.execute(select(AnnualCarryover).where(AnnualCarryover.id.in_(ids))).scalars().all()}
    if object_type == "IMPORT_FILE":
        return {item.id: item.file_name for item in db.execute(select(SourceImportFile).where(SourceImportFile.id.in_(ids))).scalars().all()}
    if object_type == "ORG":
        return {item.id: item.org_name for item in db.execute(select(SysOrgUnit).where(SysOrgUnit.id.in_(ids))).scalars().all()}
    if object_type == "CONFIG":
        return {item.id: item.config_key for item in db.execute(select(SystemConfig).where(SystemConfig.id.in_(ids))).scalars().all()}
    if object_type == "DIRECTORY":
        return {item.id: item.directory_name for item in db.execute(select(ConstructionDirectory).where(ConstructionDirectory.id.in_(ids))).scalars().all()}
    if object_type == "LOG_EXPORT":
        return {0: "操作日志导出"}
    return {}


def _log_payloads(db: Session, rows: list[OperationLog]) -> list[dict[str, Any]]:
    if not rows:
        return []
    user_ids = {row.operator_user_id for row in rows}
    plan_ids = {row.plan_id for row in rows if row.plan_id}
    org_ids = {row.college_org_id for row in rows if row.college_org_id}
    users = {item.id: item for item in db.execute(select(SysUser).where(SysUser.id.in_(user_ids))).scalars().all()}
    plans = {item.id: item for item in db.execute(select(ConstructionPlan).where(ConstructionPlan.id.in_(plan_ids))).scalars().all()} if plan_ids else {}
    orgs = {item.id: item for item in db.execute(select(SysOrgUnit).where(SysOrgUnit.id.in_(org_ids))).scalars().all()} if org_ids else {}
    missing_names: dict[str, set[int]] = {}
    for row in rows:
        if not row.object_name:
            missing_names.setdefault(row.object_type, set()).add(row.object_id)
    object_names = {object_type: _object_name_map(db, object_type, ids) for object_type, ids in missing_names.items()}
    items = []
    for row in rows:
        data = model_dict(row)
        user = users.get(row.operator_user_id)
        data["operator_user_name"] = user.user_name if user else None
        data["operator_account"] = user.account_name if user else None
        data["plan_name"] = plans[row.plan_id].plan_name if row.plan_id in plans else None
        data["college_org_name"] = orgs[row.college_org_id].org_name if row.college_org_id in orgs else None
        if not data.get("object_name"):
            data["object_name"] = object_names.get(row.object_type, {}).get(row.object_id)
        items.append(data)
    return items


@router.get("/logs")
def list_logs(
    start_time: datetime | None = None,
    end_time: datetime | None = None,
    plan_id: int | None = None,
    college_org_id: int | None = None,
    object_type: str | None = None,
    object_id: int | None = None,
    action_code: str | None = None,
    operator_user_id: int | None = None,
    keyword: str | None = None,
    page: int = 1,
    page_size: int = 20,
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    filters = {
        "start_time": start_time, "end_time": end_time, "plan_id": plan_id, "college_org_id": college_org_id,
        "object_type": object_type, "object_id": object_id, "action_code": action_code,
        "operator_user_id": operator_user_id, "keyword": keyword,
    }
    page = max(page, 1)
    page_size = min(max(page_size, 1), 200)
    base = _filtered_logs(db, user, filters)
    total = db.execute(select(func.count()).select_from(base.subquery())).scalar_one()
    rows = db.execute(
        base.order_by(OperationLog.operated_at.desc(), OperationLog.id.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
    ).scalars().all()
    return {"items": _log_payloads(db, rows), "total": total, "page": page, "page_size": page_size}


@router.get("/logs/meta")
def log_meta(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    sub = _log_scope_query(db, user).subquery()
    now = utcnow()
    today_start = datetime(now.year, now.month, now.day)
    week_start = now - timedelta(days=7)
    total = db.execute(select(func.count()).select_from(sub)).scalar_one()
    today = db.execute(select(func.count()).select_from(sub).where(sub.c.operated_at >= today_start)).scalar_one()
    last_7_days = db.execute(select(func.count()).select_from(sub).where(sub.c.operated_at >= week_start)).scalar_one()
    actions = db.execute(
        select(sub.c.action_code, func.count().label("count")).group_by(sub.c.action_code).order_by(func.count().desc(), sub.c.action_code)
    ).all()
    object_types = db.execute(
        select(sub.c.object_type, func.count().label("count")).group_by(sub.c.object_type).order_by(func.count().desc(), sub.c.object_type)
    ).all()
    operator_rows = db.execute(
        select(sub.c.operator_user_id, func.count().label("count")).group_by(sub.c.operator_user_id).order_by(func.count().desc())
    ).all()
    user_map = {
        item.id: item
        for item in db.execute(select(SysUser).where(SysUser.id.in_([row.operator_user_id for row in operator_rows]))).scalars().all()
    } if operator_rows else {}
    operators = [
        {
            "id": row.operator_user_id,
            "user_name": user_map[row.operator_user_id].user_name if row.operator_user_id in user_map else None,
            "account_name": user_map[row.operator_user_id].account_name if row.operator_user_id in user_map else None,
            "count": row.count,
        }
        for row in operator_rows
    ]
    return {
        "stats": {"total": total, "today": today, "last_7_days": last_7_days, "operator_count": len(operators)},
        "actions": [{"code": row.action_code, "count": row.count} for row in actions],
        "object_types": [{"code": row.object_type, "count": row.count} for row in object_types],
        "operators": operators,
    }


def _csv_safe(value: Any) -> Any:
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        value = json_value(value)
        import json
        value = json.dumps(value, ensure_ascii=False)
    text = str(value)
    if text.startswith(("=", "+", "-", "@", "\t", "\r")):
        return "'" + text
    return text


@router.get("/logs/export")
def export_logs(
    start_time: datetime | None = None,
    end_time: datetime | None = None,
    plan_id: int | None = None,
    college_org_id: int | None = None,
    object_type: str | None = None,
    object_id: int | None = None,
    action_code: str | None = None,
    operator_user_id: int | None = None,
    keyword: str | None = None,
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    user_roles = active_role_codes(db, user.id)
    if COLLEGE in user_roles and not user_roles.intersection({ADMIN, SCHOOL, LEADER}):
        raise AppError(403, "COLLEGE_EXPORT_FORBIDDEN", "学院用户不可导出操作日志")
    filters = {key: value for key, value in {"start_time": start_time, "end_time": end_time, "plan_id": plan_id, "college_org_id": college_org_id, "object_type": object_type, "object_id": object_id, "action_code": action_code, "operator_user_id": operator_user_id, "keyword": keyword}.items() if value is not None}
    batch_no = new_code("EXPORT")
    write_log(db, user, object_type="LOG_EXPORT", object_id=0, action_code="EXPORT", plan_id=plan_id, college_org_id=college_org_id, after={"filters": json_value(filters), "export_batch_no": batch_no}, export_batch_no=batch_no)
    db.flush()
    rows = db.execute(_filtered_logs(db, user, filters).order_by(OperationLog.operated_at, OperationLog.id)).scalars().all()
    payloads = _log_payloads(db, rows)
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["导出时间", _csv_safe(utcnow())])
    writer.writerow(["筛选条件", _csv_safe(filters)])
    writer.writerow(["导出批次", batch_no])
    writer.writerow(["记录数", len(payloads)])
    writer.writerow([])
    headers = ["日志编号", "操作时间", "操作人", "账号", "操作人ID", "角色", "所属计划", "学院", "对象类型", "对象ID", "对象名称", "动作", "业务原因", "调整前摘要", "调整后摘要", "关联单据类型", "关联单据ID"]
    writer.writerow(headers)
    for item in payloads:
        writer.writerow([_csv_safe(item.get(key)) for key in [
            "log_no", "operated_at", "operator_user_name", "operator_account", "operator_user_id", "operator_role_code",
            "plan_name", "college_org_name", "object_type", "object_id", "object_name", "action_code",
            "operation_reason", "before_snapshot_json", "after_snapshot_json", "related_order_type", "related_order_id",
        ]])
    db.commit()
    content = ("\ufeff" + output.getvalue()).encode("utf-8")
    return Response(content=content, media_type="text/csv; charset=utf-8", headers={"Content-Disposition": f'attachment; filename="operation-logs-{batch_no}.csv"'})
