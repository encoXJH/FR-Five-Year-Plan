from typing import Any

from fastapi import APIRouter, Depends
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
    ExternalRecognition,
    IndicatorAnnualTarget,
    IndicatorDepartmentRel,
    OperationLog,
    OutputIndicator,
    ProjectMember,
    ReviewRecord,
    SourceImportFile,
    SourceImportRow,
    SysUser,
    TaskAnnualMilestone,
    TaskObjection,
    TaskSubmission,
    TodoNotification,
)
from app.schemas import (
    DirectionInput,
    DirectionUpdate,
    DirectoryAction,
    DirectoryInput,
    DirectoryUpdate,
    IndicatorAction,
    IndicatorInput,
    IndicatorUpdate,
    PlanAction,
    PlanCopyInput,
    PlanInput,
    PlanUpdate,
)
from app.security import active_role_codes, get_current_user, require_roles
from app.services import storage
from app.services.audit import write_log
from app.services.planning import assert_indicator_publishable, copy_plan, validate_indicator_for_publish
from app.services.scope import ADMIN, COLLEGE, LEADER, SCHOOL, can_view_plan, get_plan, is_school_owner, visible_plan_ids
from app.utils import check_version, json_value, model_dict, utcnow


from app.responses import DecimalJSONResponse

router = APIRouter(tags=["目录计划与指标"], default_response_class=DecimalJSONResponse)
school_only = require_roles(SCHOOL)
admin_only = require_roles(ADMIN)


def _leader_valid(db: Session, user_id: int) -> bool:
    return bool(db.get(SysUser, user_id)) and LEADER in active_role_codes(db, user_id)


@router.get("/directories")
def list_directories(user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    user_roles = active_role_codes(db, user.id)
    query = select(ConstructionDirectory).order_by(ConstructionDirectory.sort_no, ConstructionDirectory.id)
    if ADMIN not in user_roles and SCHOOL not in user_roles:
        plan_ids = visible_plan_ids(db, user)
        directory_ids = set(db.execute(select(ConstructionPlan.directory_id).where(ConstructionPlan.id.in_(plan_ids or [-1]))).scalars().all())
        if LEADER in user_roles:
            directory_ids.update(db.execute(select(ConstructionDirectory.id).where(ConstructionDirectory.leader_user_id == user.id)).scalars().all())
        query = query.where(ConstructionDirectory.id.in_(directory_ids or {-1}))
    rows = db.execute(query).scalars().all()
    counts = dict(
        db.execute(
            select(ConstructionPlan.directory_id, func.count(ConstructionPlan.id))
            .where(ConstructionPlan.directory_id.in_([row.id for row in rows] or [-1]))
            .group_by(ConstructionPlan.directory_id)
        ).all()
    )
    return {"items": [{**model_dict(row), "plan_count": counts.get(row.id, 0)} for row in rows], "total": len(rows)}


@router.post("/directories", status_code=201)
def create_directory(data: DirectoryInput, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    if not _leader_valid(db, data.leader_user_id):
        raise AppError(422, "DIRECTORY_LEADER_INVALID", "目录总负责人须具备有效校领导角色")
    row = ConstructionDirectory(**data.model_dump(), created_by=user.id)
    db.add(row)
    db.flush()
    write_log(db, user, object_type="DIRECTORY", object_id=row.id, action_code="CREATE", after=model_dict(row))
    db.commit()
    return model_dict(row)


@router.patch("/directories/{directory_id}")
def update_directory(directory_id: int, data: DirectoryUpdate, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    row = db.get(ConstructionDirectory, directory_id)
    if not row:
        raise AppError(404, "DIRECTORY_NOT_FOUND", "建设目录不存在")
    check_version(row, data.expected_version)
    values = data.model_dump(exclude_unset=True, exclude={"expected_version"})
    if "leader_user_id" in values and not _leader_valid(db, values["leader_user_id"]):
        raise AppError(422, "DIRECTORY_LEADER_INVALID", "目录总负责人须具备有效校领导角色")
    before = model_dict(row)
    for key, value in values.items():
        setattr(row, key, value)
    row.lock_version += 1
    write_log(db, user, object_type="DIRECTORY", object_id=row.id, action_code="UPDATE", before=before, after=model_dict(row))
    db.commit()
    return model_dict(row)


@router.delete("/directories/{directory_id}")
def delete_directory(directory_id: int, data: DirectoryAction, user: SysUser = Depends(admin_only), db: Session = Depends(get_db)):
    row = db.get(ConstructionDirectory, directory_id)
    if not row:
        raise AppError(404, "DIRECTORY_NOT_FOUND", "建设目录不存在")
    check_version(row, data.expected_version)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "删除建设目录需要二次确认")
    plan_count = db.execute(
        select(func.count(ConstructionPlan.id)).where(ConstructionPlan.directory_id == row.id)
    ).scalar_one()
    if plan_count:
        raise AppError(409, "DIRECTORY_NOT_EMPTY", "目录下存在建设计划，不可删除", [f"{plan_count} 个建设计划"])
    before = model_dict(row)
    write_log(db, user, object_type="DIRECTORY", object_id=row.id, action_code="DELETE", reason=data.reason, before=before)
    db.delete(row)
    db.commit()
    return {"success": True}


def _plan_payload(db: Session, row: ConstructionPlan) -> dict:
    result = model_dict(row)
    result["directory_name"] = db.get(ConstructionDirectory, row.directory_id).directory_name
    result["school_owner_name"] = db.get(SysUser, row.school_owner_id).user_name
    result["direction_count"] = db.execute(select(func.count(ConstructionDirection.id)).where(ConstructionDirection.plan_id == row.id)).scalar_one()
    result["indicator_count"] = db.execute(select(func.count(OutputIndicator.id)).where(OutputIndicator.plan_id == row.id)).scalar_one()
    result["task_count"] = db.execute(
        select(func.count(CollegeTask.id)).join(OutputIndicator, OutputIndicator.id == CollegeTask.indicator_id).where(OutputIndicator.plan_id == row.id)
    ).scalar_one()
    return result


@router.get("/plans")
def list_plans(
    directory_id: int | None = None,
    keyword: str | None = None,
    plan_status: str | None = None,
    user: SysUser = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    ids = visible_plan_ids(db, user)
    query = select(ConstructionPlan).where(ConstructionPlan.id.in_(ids or [-1])).order_by(ConstructionPlan.id.desc())
    if directory_id:
        query = query.where(ConstructionPlan.directory_id == directory_id)
    if plan_status:
        query = query.where(ConstructionPlan.plan_status == plan_status)
    if keyword and keyword.strip():
        value = keyword.strip()
        query = query.where(or_(ConstructionPlan.plan_name.contains(value), ConstructionPlan.plan_code.contains(value), ConstructionPlan.construction_scope.contains(value)))
    rows = db.execute(query).scalars().all()
    return {"items": [_plan_payload(db, row) for row in rows], "total": len(rows)}


@router.post("/plans", status_code=201)
def create_plan(data: PlanInput, user: SysUser = Depends(school_only), db: Session = Depends(get_db)):
    if not db.get(ConstructionDirectory, data.directory_id):
        raise AppError(404, "DIRECTORY_NOT_FOUND", "建设目录不存在")
    owner_id = data.school_owner_id or user.id
    if owner_id != user.id:
        raise AppError(403, "PLAN_OWNER_FORBIDDEN", "校级负责人只能创建本人负责的计划")
    values = data.model_dump()
    values["school_owner_id"] = owner_id
    row = ConstructionPlan(**values, created_by=user.id)
    db.add(row)
    db.flush()
    db.add(ProjectMember(plan_id=row.id, user_id=user.id, member_role="SCHOOL_OWNER", is_primary=True, effective_from=utcnow(), created_by=user.id))
    write_log(db, user, object_type="PLAN", object_id=row.id, action_code="CREATE", plan_id=row.id, after=model_dict(row))
    db.commit()
    return model_dict(row)


@router.post("/plans/copy", status_code=201)
def copy_existing_plan(data: PlanCopyInput, user: SysUser = Depends(school_only), db: Session = Depends(get_db)):
    source = get_plan(db, user, data.source_plan_id)
    owner_id = data.school_owner_id or user.id
    if owner_id != user.id:
        raise AppError(403, "PLAN_OWNER_FORBIDDEN", "复制计划的主要负责人必须为当前校级负责人")
    row = copy_plan(db, source, data, user)
    write_log(db, user, object_type="PLAN", object_id=row.id, action_code="COPY", plan_id=row.id, after={"source_plan_id": source.id, "copy_mode": data.copy_mode})
    db.commit()
    return model_dict(row)


@router.get("/plans/{plan_id}")
def plan_detail(plan_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = get_plan(db, user, plan_id)
    return _plan_payload(db, row)


@router.patch("/plans/{plan_id}")
def update_plan(plan_id: int, data: PlanUpdate, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = get_plan(db, user, plan_id, manage=True)
    check_version(row, data.expected_version)
    if row.plan_status != "DRAFT" and (data.start_year is not None or data.end_year is not None):
        raise AppError(409, "PUBLISHED_PERIOD_LOCKED", "计划发布后周期不可直接修改")
    start = data.start_year if data.start_year is not None else row.start_year
    end = data.end_year if data.end_year is not None else row.end_year
    if end < start:
        raise AppError(422, "INVALID_PLAN_YEARS", "结束年度不得早于开始年度")
    before = model_dict(row)
    for key, value in data.model_dump(exclude_unset=True, exclude={"expected_version"}).items():
        setattr(row, key, value)
    row.lock_version += 1
    write_log(db, user, object_type="PLAN", object_id=row.id, action_code="UPDATE", plan_id=row.id, before=before, after=model_dict(row))
    db.commit()
    return model_dict(row)


def _plan_scope_ids(db: Session, plan_id: int) -> tuple[list[int], list[int], list[int]]:
    indicator_ids = list(db.execute(select(OutputIndicator.id).where(OutputIndicator.plan_id == plan_id)).scalars().all())
    direction_ids = list(db.execute(select(ConstructionDirection.id).where(ConstructionDirection.plan_id == plan_id)).scalars().all())
    file_ids = list(db.execute(select(SourceImportFile.id).where(SourceImportFile.plan_id == plan_id)).scalars().all())
    return indicator_ids, direction_ids, file_ids


def _delete_plan_cascade(db: Session, plan: ConstructionPlan) -> tuple[dict, list[str]]:
    indicator_ids, direction_ids, file_ids = _plan_scope_ids(db, plan.id)
    task_ids = list(db.execute(select(CollegeTask.id).where(CollegeTask.indicator_id.in_(indicator_ids or [-1]))).scalars().all())
    milestone_ids = list(db.execute(select(TaskAnnualMilestone.id).where(TaskAnnualMilestone.task_id.in_(task_ids or [-1]))).scalars().all())
    submission_ids = list(db.execute(select(TaskSubmission.id).where(TaskSubmission.milestone_id.in_(milestone_ids or [-1]))).scalars().all())
    review_ids = list(db.execute(select(ReviewRecord.id).where(ReviewRecord.submission_id.in_(submission_ids or [-1]))).scalars().all())

    materials = db.execute(select(EvidenceMaterial).where(EvidenceMaterial.submission_id.in_(submission_ids or [-1]))).scalars().all()
    reviews = db.execute(select(ReviewRecord).where(ReviewRecord.id.in_(review_ids or [-1]))).scalars().all()
    submissions = db.execute(select(TaskSubmission).where(TaskSubmission.id.in_(submission_ids or [-1]))).scalars().all()
    milestones = db.execute(select(TaskAnnualMilestone).where(TaskAnnualMilestone.id.in_(milestone_ids or [-1]))).scalars().all()
    tasks = db.execute(select(CollegeTask).where(CollegeTask.id.in_(task_ids or [-1]))).scalars().all()
    transfers = db.execute(
        select(CrossCollegeTransfer).where(
            or_(
                CrossCollegeTransfer.indicator_id.in_(indicator_ids or [-1]),
                CrossCollegeTransfer.source_task_id.in_(task_ids or [-1]),
                CrossCollegeTransfer.target_task_id.in_(task_ids or [-1]),
            )
        )
    ).scalars().all()
    transfer_ids = [item.id for item in transfers]
    carryovers = db.execute(select(AnnualCarryover).where(AnnualCarryover.task_id.in_(task_ids or [-1]))).scalars().all()
    compensations = db.execute(select(AnnualCompensation).where(AnnualCompensation.task_id.in_(task_ids or [-1]))).scalars().all()
    objections = db.execute(select(TaskObjection).where(TaskObjection.task_id.in_(task_ids or [-1]))).scalars().all()
    snapshots = db.execute(select(CalculationSnapshot).where(CalculationSnapshot.plan_id == plan.id)).scalars().all()
    recognitions = db.execute(select(ExternalRecognition).where(ExternalRecognition.indicator_id.in_(indicator_ids or [-1]))).scalars().all()
    todos = db.execute(select(TodoNotification).where(TodoNotification.plan_id == plan.id)).scalars().all()
    members = db.execute(select(ProjectMember).where(ProjectMember.plan_id == plan.id)).scalars().all()
    member_ids = [item.id for item in members]
    rows_import = db.execute(select(SourceImportRow).where(SourceImportRow.import_file_id.in_(file_ids or [-1]))).scalars().all()
    source_files = db.execute(select(SourceImportFile).where(SourceImportFile.id.in_(file_ids or [-1]))).scalars().all()
    indicators = db.execute(select(OutputIndicator).where(OutputIndicator.id.in_(indicator_ids or [-1]))).scalars().all()
    directions = db.execute(select(ConstructionDirection).where(ConstructionDirection.id.in_(direction_ids or [-1]))).scalars().all()

    counts = {
        "direction_count": len(directions),
        "indicator_count": len(indicators),
        "task_count": len(tasks),
        "milestone_count": len(milestones),
        "submission_count": len(submissions),
        "material_count": len(materials),
        "review_count": len(reviews),
        "external_recognition_count": len(recognitions),
        "calculation_snapshot_count": len(snapshots),
        "carryover_count": len(carryovers),
        "compensation_count": len(compensations),
        "transfer_count": len(transfers),
        "objection_count": len(objections),
        "todo_count": len(todos),
        "import_file_count": len(source_files),
        "import_row_count": len(rows_import),
        "project_member_count": len(members),
    }
    object_keys: set[str] = {item.object_key for item in materials if item.object_key}
    object_keys.update(item.preview_object_key for item in materials if item.preview_object_key)
    object_keys.update(item.object_key for item in source_files if item.object_key)
    for transfer in transfers:
        attachments = transfer.attachment_json if isinstance(transfer.attachment_json, list) else []
        object_keys.update(item.get("object_key") for item in attachments if isinstance(item, dict) and item.get("object_key"))

    # 先解除可空的交叉引用，避免删除顺序触发外键约束。
    for milestone in milestones:
        milestone.current_submission_id = None
        milestone.last_review_id = None
    for review in reviews:
        review.corrects_review_id = None
    for transfer in transfers:
        transfer.copied_from_transfer_id = None
    for reference in db.execute(
        select(CrossCollegeTransfer).where(CrossCollegeTransfer.copied_from_transfer_id.in_(transfer_ids or [-1]))
    ).scalars().all():
        reference.copied_from_transfer_id = None
    for indicator in indicators:
        indicator.source_import_row_id = None
    for row in rows_import:
        row.indicator_id = None
    for copy in db.execute(select(ConstructionPlan).where(ConstructionPlan.template_source_plan_id == plan.id)).scalars().all():
        copy.template_source_plan_id = None
    for reference in db.execute(select(ProjectMember).where(ProjectMember.handover_from_id.in_(member_ids or [-1]))).scalars().all():
        reference.handover_from_id = None
    for log in db.execute(select(OperationLog).where(OperationLog.plan_id == plan.id)).scalars().all():
        log.plan_id = None
    db.flush()

    for item in materials:
        db.delete(item)
    db.flush()
    for item in compensations:
        db.delete(item)
    for item in carryovers:
        db.delete(item)
    for item in transfers:
        db.delete(item)
    for item in objections:
        db.delete(item)
    for item in snapshots:
        db.delete(item)
    for item in recognitions:
        db.delete(item)
    for item in todos:
        db.delete(item)
    db.flush()
    for item in reviews:
        db.delete(item)
    db.flush()
    for item in submissions:
        db.delete(item)
    db.flush()
    for item in milestones:
        db.delete(item)
    db.flush()
    for item in tasks:
        db.delete(item)
    db.flush()
    for indicator in indicators:
        for target in db.execute(select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == indicator.id)).scalars().all():
            db.delete(target)
        for relation in db.execute(select(IndicatorDepartmentRel).where(IndicatorDepartmentRel.indicator_id == indicator.id)).scalars().all():
            db.delete(relation)
    db.flush()
    for item in indicators:
        db.delete(item)
    db.flush()
    for item in directions:
        db.delete(item)
    for item in members:
        db.delete(item)
    for item in rows_import:
        db.delete(item)
    db.flush()
    for item in source_files:
        db.delete(item)
    db.flush()
    return counts, sorted(object_keys)


@router.delete("/plans/{plan_id}")
def delete_plan(plan_id: int, data: PlanAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = get_plan(db, user, plan_id, manage=True)
    check_version(row, data.expected_version)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "删除计划需要二次确认")
    plan_name = row.plan_name
    action_code = "DELETE_DRAFT" if row.plan_status == "DRAFT" and row.published_at is None else "DELETE"
    before_plan = model_dict(row)
    counts, object_keys = _delete_plan_cascade(db, row)
    write_log(
        db,
        user,
        object_type="PLAN",
        object_id=row.id,
        object_name=plan_name,
        action_code=action_code,
        plan_id=None,
        reason=data.reason,
        before={"plan": before_plan, "counts": counts},
    )
    db.delete(row)
    db.commit()
    for object_key in object_keys:
        try:
            path = storage.protected_storage_path(object_key)
            if path.is_file():
                path.unlink()
        except Exception:
            continue
    return {"success": True}


@router.post("/plans/{plan_id}/publish")
def publish_plan(plan_id: int, data: PlanAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = get_plan(db, user, plan_id, manage=True)
    check_version(row, data.expected_version)
    if row.plan_status != "DRAFT":
        raise AppError(409, "PLAN_NOT_DRAFT", "仅草稿计划可发布")
    indicators = db.execute(select(OutputIndicator).where(OutputIndicator.plan_id == row.id)).scalars().all()
    if not indicators:
        raise AppError(422, "PLAN_HAS_NO_INDICATORS", "计划至少需要一个完整量化指标")
    failures = {f"{item.indicator_code}｜{item.indicator_name}": validate_indicator_for_publish(db, item, row) for item in indicators}
    failures = {key: value for key, value in failures.items() if value}
    if failures:
        raise AppError(422, "PLAN_NOT_READY", "计划仍有指标未达到发布条件", failures)
    for indicator in indicators:
        indicator.indicator_status = "PUBLISHED"
    row.plan_status = "PUBLISHED"
    row.published_by = user.id
    row.published_at = utcnow()
    row.current_version_no += 1
    row.lock_version += 1
    write_log(db, user, object_type="PLAN", object_id=row.id, action_code="PUBLISH", plan_id=row.id, after={"status": row.plan_status})
    db.commit()
    return model_dict(row)


@router.post("/plans/{plan_id}/start")
def start_plan(plan_id: int, data: PlanAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = get_plan(db, user, plan_id, manage=True)
    check_version(row, data.expected_version)
    if row.plan_status != "PUBLISHED":
        raise AppError(409, "PLAN_NOT_PUBLISHED", "仅已发布计划可进入执行")
    row.plan_status = "IN_PROGRESS"
    row.lock_version += 1
    write_log(db, user, object_type="PLAN", object_id=row.id, action_code="START", plan_id=row.id, after={"status": row.plan_status})
    db.commit()
    return model_dict(row)


@router.post("/plans/{plan_id}/close")
def close_plan(plan_id: int, data: PlanAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = get_plan(db, user, plan_id, manage=True)
    check_version(row, data.expected_version)
    if not data.confirm:
        raise AppError(422, "CONFIRMATION_REQUIRED", "计划结项需要二次确认")
    if row.plan_status not in {"PUBLISHED", "IN_PROGRESS"}:
        raise AppError(409, "PLAN_NOT_CLOSABLE", "当前计划状态不可结项")
    row.plan_status = "CLOSED"
    row.closed_at = utcnow()
    row.lock_version += 1
    write_log(db, user, object_type="PLAN", object_id=row.id, action_code="CLOSE", plan_id=row.id, reason=data.reason)
    db.commit()
    return model_dict(row)


@router.get("/directions")
def list_directions(plan_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    plan = get_plan(db, user, plan_id)
    rows = db.execute(select(ConstructionDirection).where(ConstructionDirection.plan_id == plan.id).order_by(ConstructionDirection.sort_no)).scalars().all()
    if COLLEGE in active_role_codes(db, user.id) and not is_school_owner(db, user, plan):
        allowed_directions = set(
            db.execute(
                select(OutputIndicator.direction_id)
                .join(CollegeTask, CollegeTask.indicator_id == OutputIndicator.id)
                .where(OutputIndicator.plan_id == plan.id, CollegeTask.college_org_id == user.primary_org_id, CollegeTask.task_status != "DRAFT")
            ).scalars().all()
        )
        rows = [row for row in rows if row.id in allowed_directions]
    return {"items": [model_dict(row) for row in rows], "total": len(rows)}


@router.post("/directions", status_code=201)
def create_direction(data: DirectionInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    plan = get_plan(db, user, data.plan_id, manage=True)
    if plan.plan_status == "CLOSED":
        raise AppError(409, "PLAN_CLOSED", "已结项计划不可新增方向")
    row = ConstructionDirection(**data.model_dump(), created_by=user.id)
    db.add(row)
    db.flush()
    write_log(db, user, object_type="DIRECTION", object_id=row.id, action_code="CREATE", plan_id=plan.id, after=model_dict(row))
    db.commit()
    return model_dict(row)


@router.patch("/directions/{direction_id}")
def update_direction(direction_id: int, data: DirectionUpdate, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(ConstructionDirection, direction_id)
    if not row:
        raise AppError(404, "DIRECTION_NOT_FOUND", "建设方向不存在")
    plan = get_plan(db, user, row.plan_id, manage=True)
    check_version(row, data.expected_version)
    before = model_dict(row)
    for key, value in data.model_dump(exclude_unset=True, exclude={"expected_version"}).items():
        setattr(row, key, value)
    row.lock_version += 1
    write_log(db, user, object_type="DIRECTION", object_id=row.id, action_code="UPDATE", plan_id=plan.id, before=before, after=model_dict(row))
    db.commit()
    return model_dict(row)


def _indicator_detail(db: Session, indicator: OutputIndicator, include_source: bool = True, direction: ConstructionDirection | None = None) -> dict:
    result = model_dict(indicator)
    direction = direction or db.get(ConstructionDirection, indicator.direction_id)
    if direction:
        result["direction_name"] = direction.direction_name
        result["direction_code"] = direction.direction_code
    unit = db.execute(select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code == indicator.unit_code)).scalars().first()
    if unit:
        result["value_kind"] = unit.value_kind
        result["unit_name"] = unit.unit_name
    targets = db.execute(select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == indicator.id).order_by(IndicatorAnnualTarget.target_year)).scalars().all()
    result["annual_targets"] = [model_dict(item) for item in targets]
    relations = db.execute(select(IndicatorDepartmentRel).where(IndicatorDepartmentRel.indicator_id == indicator.id)).scalars().all()
    result["departments"] = [model_dict(item) for item in relations]
    if include_source and indicator.source_import_row_id:
        source = db.get(SourceImportRow, indicator.source_import_row_id)
        result["source_row"] = model_dict(source) if source else None
    return result


@router.get("/indicators")
def list_indicators(plan_id: int, direction_id: int | None = None, indicator_type_code: str | None = None, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    plan = get_plan(db, user, plan_id)
    query = select(OutputIndicator).where(OutputIndicator.plan_id == plan.id)
    if direction_id:
        query = query.where(OutputIndicator.direction_id == direction_id)
    if indicator_type_code:
        query = query.where(OutputIndicator.indicator_type_code == indicator_type_code)
    rows = db.execute(query).scalars().all()
    if COLLEGE in active_role_codes(db, user.id) and not is_school_owner(db, user, plan):
        allowed = set(db.execute(select(CollegeTask.indicator_id).where(CollegeTask.college_org_id == user.primary_org_id, CollegeTask.task_status != "DRAFT")).scalars().all())
        rows = [row for row in rows if row.id in allowed]
    directions = {
        item.id: item
        for item in db.execute(select(ConstructionDirection).where(ConstructionDirection.plan_id == plan.id)).scalars().all()
    }
    return {"items": [_indicator_detail(db, row, include_source=False, direction=directions.get(row.direction_id)) for row in rows], "total": len(rows)}


@router.post("/indicators", status_code=201)
def create_indicator(data: IndicatorInput, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    direction = db.get(ConstructionDirection, data.direction_id)
    if not direction:
        raise AppError(404, "DIRECTION_NOT_FOUND", "建设方向不存在")
    plan = get_plan(db, user, direction.plan_id, manage=True)
    if plan.plan_status == "CLOSED":
        raise AppError(409, "PLAN_CLOSED", "已结项计划不可新建指标")
    values = data.model_dump(exclude={"annual_targets", "departments"})
    values["indicator_desc"] = values.get("indicator_desc") or values["indicator_name"]
    from app.models import DictIndicatorUnit
    unit = db.execute(select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code == data.unit_code)).scalars().first()
    if not unit:
        raise AppError(422, "UNIT_NOT_FOUND", "指标单位不存在")
    values["unit_raw"] = unit.unit_name
    row = OutputIndicator(plan_id=plan.id, parse_status="NEED_CONFIRM", created_by=user.id, **values)
    db.add(row)
    db.flush()
    supplied = {item.target_year: item for item in data.annual_targets}
    for year in range(plan.start_year, plan.end_year + 1):
        item = supplied.get(year)
        target_data = item.model_dump(exclude={"expected_version"}) if item else {"target_year": year}
        db.add(IndicatorAnnualTarget(indicator_id=row.id, created_by=user.id, **target_data))
    for relation in data.departments:
        db.add(IndicatorDepartmentRel(indicator_id=row.id, created_by=user.id, **relation.model_dump()))
    write_log(db, user, object_type="INDICATOR", object_id=row.id, action_code="CREATE", plan_id=plan.id, after=_indicator_detail(db, row))
    db.commit()
    return _indicator_detail(db, row)


@router.get("/indicators/{indicator_id}")
def indicator_detail(indicator_id: int, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(OutputIndicator, indicator_id)
    if not row:
        raise AppError(404, "INDICATOR_NOT_FOUND", "关键产出指标不存在")
    plan = get_plan(db, user, row.plan_id)
    is_college_only = COLLEGE in active_role_codes(db, user.id) and not is_school_owner(db, user, plan)
    if is_college_only:
        assigned = db.execute(select(CollegeTask.id).where(CollegeTask.indicator_id == row.id, CollegeTask.college_org_id == user.primary_org_id, CollegeTask.task_status != "DRAFT")).first()
        if not assigned:
            raise AppError(403, "INDICATOR_FORBIDDEN", "仅可查看已下发至本学院的指标摘要")
    return _indicator_detail(db, row, include_source=not is_college_only)


@router.patch("/indicators/{indicator_id}")
def update_indicator(indicator_id: int, data: IndicatorUpdate, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(OutputIndicator, indicator_id)
    if not row:
        raise AppError(404, "INDICATOR_NOT_FOUND", "关键产出指标不存在")
    plan = get_plan(db, user, row.plan_id, manage=True)
    check_version(row, data.expected_version)
    before = _indicator_detail(db, row)
    values = data.model_dump(exclude_unset=True, exclude={"annual_targets", "departments", "expected_version"})
    assigned_tasks = db.execute(select(CollegeTask).where(CollegeTask.indicator_id == row.id, CollegeTask.task_status != "DRAFT")).scalars().all()
    if assigned_tasks and any(field in values for field in ["indicator_type_code", "unit_code", "calc_method"]):
        raise AppError(409, "ASSIGNED_INDICATOR_FROZEN", "已下发指标的类型、单位和核算方式不可直接覆盖，须走调整流程")
    if values.get("parse_status") == "CONFIRMED":
        row.confirmed_by = user.id
        row.confirmed_at = utcnow()
    for key, value in values.items():
        setattr(row, key, value)
    if "unit_code" in values:
        from app.models import DictIndicatorUnit
        unit = db.execute(select(DictIndicatorUnit).where(DictIndicatorUnit.unit_code == row.unit_code)).scalars().first()
        if not unit:
            raise AppError(422, "UNIT_NOT_FOUND", "指标单位不存在")
        row.unit_raw = unit.unit_name
    if data.annual_targets is not None:
        for item in data.annual_targets:
            if not plan.start_year <= item.target_year <= plan.end_year:
                raise AppError(422, "TARGET_YEAR_INVALID", "目标年度超出计划周期")
            target = db.execute(select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == row.id, IndicatorAnnualTarget.target_year == item.target_year)).scalars().first()
            if not target:
                raise AppError(404, "ANNUAL_TARGET_NOT_FOUND", "年度目标记录不存在")
            check_version(target, item.expected_version)
            if assigned_tasks:
                milestones = db.execute(select(TaskAnnualMilestone).join(CollegeTask, CollegeTask.id == TaskAnnualMilestone.task_id).where(CollegeTask.indicator_id == row.id, TaskAnnualMilestone.target_year == item.target_year)).scalars().all()
                occupied = any(m.current_submission_id or m.has_adjustment or m.milestone_status == "UNDER_REVIEW" for m in milestones)
                if item.target_year <= utcnow().year or occupied:
                    raise AppError(409, "EXECUTED_TARGET_FROZEN", "已执行或有关联填报、审核、调整的年度目标不可直接覆盖")
            target_before = model_dict(target)
            for key, value in item.model_dump(exclude={"target_year", "expected_version"}, exclude_unset=True).items():
                setattr(target, key, value)
            if item.confirm_status == "CONFIRMED":
                target.confirmed_by = user.id
                target.confirmed_at = utcnow()
            target.target_version_no += 1
            target.lock_version += 1
            write_log(db, user, object_type="INDICATOR_TARGET", object_id=target.id, action_code="TARGET_UPDATE", plan_id=plan.id, before=target_before, after=model_dict(target))
    if data.departments is not None:
        existing = db.execute(select(IndicatorDepartmentRel).where(IndicatorDepartmentRel.indicator_id == row.id)).scalars().all()
        for relation in existing:
            db.delete(relation)
        for relation in data.departments:
            db.add(IndicatorDepartmentRel(indicator_id=row.id, created_by=user.id, **relation.model_dump()))
    row.target_version_no += 1
    row.lock_version += 1
    write_log(db, user, object_type="INDICATOR", object_id=row.id, action_code="QUANTIFY", plan_id=plan.id, before=before, after=_indicator_detail(db, row))
    db.commit()
    return _indicator_detail(db, row)


@router.post("/indicators/{indicator_id}/publish")
def publish_indicator(indicator_id: int, data: IndicatorAction, user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(OutputIndicator, indicator_id)
    if not row:
        raise AppError(404, "INDICATOR_NOT_FOUND", "关键产出指标不存在")
    plan = get_plan(db, user, row.plan_id, manage=True)
    check_version(row, data.expected_version)
    if plan.plan_status not in {"PUBLISHED", "IN_PROGRESS"}:
        raise AppError(409, "PLAN_NOT_PUBLISHED", "计划发布后才能发布指标")
    assert_indicator_publishable(db, row, plan)
    row.indicator_status = "PUBLISHED"
    row.lock_version += 1
    write_log(db, user, object_type="INDICATOR", object_id=row.id, action_code="PUBLISH", plan_id=plan.id)
    db.commit()
    return _indicator_detail(db, row)
