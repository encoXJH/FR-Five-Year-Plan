import uuid
from datetime import date

from sqlalchemy import select

from app.config import get_settings
from app.models import (
    AnnualCarryover,
    AnnualCompensation,
    CalculationSnapshot,
    CollegeTask,
    ConstructionDirection,
    ConstructionPlan,
    CrossCollegeTransfer,
    EvidenceMaterial,
    IndicatorAnnualTarget,
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
from app.services.storage import protected_storage_path
from app.utils import utcnow
from tests.helpers import login


def create_draft_plan(client, name="待删除草稿"):
    login(client, "school")
    response = client.post(
        "/api/v1/plans",
        json={
            "plan_code": f"DEL-{uuid.uuid4().hex[:10]}",
            "directory_id": 1,
            "plan_name": name,
            "construction_scope": "删除测试范围",
            "start_year": 2026,
            "end_year": 2027,
        },
    )
    assert response.status_code == 201, response.text
    return response.json()


def add_indicator(client, plan):
    direction = client.post(
        "/api/v1/directions",
        json={"plan_id": plan["id"], "direction_code": "D1", "direction_name": "删除方向", "sort_no": 1},
    )
    assert direction.status_code == 201, direction.text
    indicator = client.post(
        "/api/v1/indicators",
        json={
            "direction_id": direction.json()["id"],
            "indicator_code": "I1",
            "indicator_name": "删除指标",
            "indicator_type_code": "DEVELOPMENT",
            "unit_code": "ITEM_COUNT",
            "annual_targets": [{"target_year": 2026, "professional_target_value": 1}, {"target_year": 2027, "professional_target_value": 2}],
        },
    )
    assert indicator.status_code == 201, indicator.text
    return direction.json(), indicator.json()


def delete_plan(client, plan_id, expected_version=1, confirm=True, reason="清理无效草稿"):
    return client.request(
        "DELETE",
        f"/api/v1/plans/{plan_id}",
        json={"expected_version": expected_version, "confirm": confirm, "reason": reason},
    )


def user_by_account(db, account):
    return db.execute(select(SysUser).where(SysUser.account_name == account)).scalars().one()


def test_delete_blank_draft_keeps_logs_and_removes_members(client_db):
    client, db = client_db
    plan = create_draft_plan(client, name="空白草稿")
    response = delete_plan(client, plan["id"])
    assert response.status_code == 200, response.text
    assert response.json() == {"success": True}
    assert client.get(f"/api/v1/plans/{plan['id']}").status_code == 404
    assert db.get(ConstructionPlan, plan["id"]) is None
    assert not db.execute(select(ProjectMember).where(ProjectMember.plan_id == plan["id"])).scalars().all()
    logs = db.execute(
        select(OperationLog).where(OperationLog.object_type == "PLAN", OperationLog.object_id == plan["id"])
    ).scalars().all()
    actions = {item.action_code for item in logs}
    assert {"CREATE", "DELETE"} <= actions
    assert all(item.plan_id is None for item in logs)
    delete_log = next(item for item in logs if item.action_code == "DELETE")
    assert delete_log.object_name == "空白草稿"
    assert delete_log.operation_reason == "清理无效草稿"
    assert delete_log.before_snapshot_json["plan"]["id"] == plan["id"]
    assert delete_log.before_snapshot_json["counts"]["project_member_count"] == 1


def test_delete_configured_draft_breaks_import_links_and_cleans_file(client_db):
    client, db = client_db
    plan = create_draft_plan(client)
    direction, indicator = add_indicator(client, plan)
    owner = user_by_account(db, "school")
    object_key = f"imports/{uuid.uuid4().hex}.xlsx"
    batch = SourceImportFile(
        plan_id=plan["id"],
        file_name="source.xlsx",
        object_key=object_key,
        file_hash=uuid.uuid4().hex,
        sheet_count=1,
        data_row_count=1,
        imported_by=owner.id,
        imported_at=utcnow(),
        created_by=owner.id,
    )
    db.add(batch)
    db.flush()
    source_row = SourceImportRow(
        import_file_id=batch.id,
        sheet_name="Sheet1",
        row_number=2,
        indicator_id=indicator["id"],
        created_by=owner.id,
    )
    db.add(source_row)
    indicator_row = db.get(OutputIndicator, indicator["id"])
    indicator_row.source_import_row_id = source_row.id
    path = protected_storage_path(object_key)
    path.write_bytes(b"draft import")
    db.commit()

    response = delete_plan(client, plan["id"], reason="含导入配置")
    assert response.status_code == 200, response.text
    assert not path.exists()
    assert db.get(ConstructionPlan, plan["id"]) is None
    assert db.get(ConstructionDirection, direction["id"]) is None
    assert db.get(OutputIndicator, indicator["id"]) is None
    assert db.get(SourceImportFile, batch.id) is None
    assert db.get(SourceImportRow, source_row.id) is None
    assert not db.execute(select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == indicator["id"])).scalars().all()
    delete_log = db.execute(
        select(OperationLog).where(OperationLog.object_id == plan["id"], OperationLog.action_code == "DELETE")
    ).scalars().one()
    counts = delete_log.before_snapshot_json["counts"]
    assert {key: counts[key] for key in ("direction_count", "indicator_count", "import_file_count", "import_row_count", "project_member_count")} == {
        "direction_count": 1,
        "indicator_count": 1,
        "import_file_count": 1,
        "import_row_count": 1,
        "project_member_count": 1,
    }


def test_delete_published_plan_without_business_data_succeeds(client_db):
    client, db = client_db
    plan = create_draft_plan(client)
    direction, indicator = add_indicator(client, plan)
    row = db.get(ConstructionPlan, plan["id"])
    row.plan_status = "PUBLISHED"
    row.published_at = utcnow()
    db.commit()

    response = delete_plan(client, plan["id"], reason="清理误建计划")
    assert response.status_code == 200, response.text
    assert db.get(ConstructionPlan, plan["id"]) is None
    assert db.get(ConstructionDirection, direction["id"]) is None
    assert db.get(OutputIndicator, indicator["id"]) is None
    log = db.execute(
        select(OperationLog).where(OperationLog.object_id == plan["id"], OperationLog.action_code == "DELETE")
    ).scalars().one()
    assert log.before_snapshot_json["plan"]["plan_status"] == "PUBLISHED"
    assert log.operation_reason == "清理误建计划"


def test_delete_published_plan_with_task_cascades(client_db):
    client, db = client_db
    plan = create_draft_plan(client)
    _direction, indicator = add_indicator(client, plan)
    row = db.get(ConstructionPlan, plan["id"])
    row.plan_status = "PUBLISHED"
    row.published_at = utcnow()
    college = user_by_account(db, "college")
    task = CollegeTask(
        task_code=f"TASK-{uuid.uuid4().hex[:10]}",
        indicator_id=indicator["id"],
        college_org_id=college.primary_org_id,
        owner_user_id=college.id,
        task_status="PENDING_RECEIVE",
        created_by=college.id,
    )
    db.add(task)
    db.commit()
    response = delete_plan(client, plan["id"])
    assert response.status_code == 200, response.text
    assert db.get(ConstructionPlan, plan["id"]) is None
    assert db.get(OutputIndicator, indicator["id"]) is None
    assert db.get(CollegeTask, task.id) is None


def test_delete_draft_with_college_task_cascades(client_db):
    client, db = client_db
    plan = create_draft_plan(client)
    _direction, indicator = add_indicator(client, plan)
    college = user_by_account(db, "college")
    task = CollegeTask(
        task_code=f"TASK-{uuid.uuid4().hex[:10]}",
        indicator_id=indicator["id"],
        college_org_id=college.primary_org_id,
        owner_user_id=college.id,
        task_status="DRAFT",
        created_by=college.id,
    )
    db.add(task)
    db.commit()
    response = delete_plan(client, plan["id"])
    assert response.status_code == 200, response.text
    assert db.get(ConstructionPlan, plan["id"]) is None
    assert db.get(OutputIndicator, indicator["id"]) is None
    assert db.get(CollegeTask, task.id) is None


def test_delete_cascades_todo_and_releases_template_reference(client_db):
    client, db = client_db
    todo_plan = create_draft_plan(client)
    owner = user_by_account(db, "school")
    todo = TodoNotification(
        recipient_user_id=owner.id,
        plan_id=todo_plan["id"],
        biz_type="PLAN",
        biz_id=todo_plan["id"],
        title="待处理草稿",
        created_by=owner.id,
    )
    db.add(todo)
    db.commit()
    response = delete_plan(client, todo_plan["id"])
    assert response.status_code == 200, response.text
    assert db.get(TodoNotification, todo.id) is None

    source_plan = create_draft_plan(client)
    copy_plan = create_draft_plan(client)
    db.get(ConstructionPlan, copy_plan["id"]).template_source_plan_id = source_plan["id"]
    db.commit()
    response = delete_plan(client, source_plan["id"])
    assert response.status_code == 200, response.text
    assert db.get(ConstructionPlan, source_plan["id"]) is None
    assert db.get(ConstructionPlan, copy_plan["id"]).template_source_plan_id is None


def test_delete_plan_cascades_task_execution_data_and_files(client_db):
    client, db = client_db
    plan = create_draft_plan(client)
    _direction, indicator_payload = add_indicator(client, plan)
    indicator = db.get(OutputIndicator, indicator_payload["id"])
    college = user_by_account(db, "college")
    school = user_by_account(db, "school")
    task = CollegeTask(
        task_code=f"TASK-{uuid.uuid4().hex[:10]}",
        indicator_id=indicator.id,
        college_org_id=college.primary_org_id,
        owner_user_id=college.id,
        task_status="PENDING_RECEIVE",
        created_by=school.id,
    )
    db.add(task)
    db.flush()
    first = TaskAnnualMilestone(
        task_id=task.id, target_year=2026, original_target_value=1, current_target_value=1,
        milestone_status="PENDING_REPORT", created_by=school.id,
    )
    second = TaskAnnualMilestone(
        task_id=task.id, target_year=2027, original_target_value=1, current_target_value=1,
        milestone_status="PENDING_REPORT", created_by=school.id,
    )
    db.add_all([first, second])
    db.flush()
    submission = TaskSubmission(
        milestone_id=first.id, version_no=1, is_current=True, reported_value=1,
        completion_desc="级联删除测试填报", completion_date=date(2026, 6, 30),
        submission_status="SUBMITTED", submitted_by=college.id, submitted_at=utcnow(), created_by=college.id,
    )
    db.add(submission)
    db.flush()
    object_key = f"evidence/{uuid.uuid4().hex}.pdf"
    file_path = protected_storage_path(object_key)
    file_path.write_bytes(b"cascade")
    material = EvidenceMaterial(
        submission_id=submission.id, material_name="测试材料", original_file_name="test.pdf",
        object_key=object_key, file_extension="pdf", mime_type="application/pdf", file_size_bytes=7,
        preview_status="READY", uploaded_by=college.id, uploaded_at=utcnow(), created_by=college.id,
    )
    db.add(material)
    db.flush()
    review = ReviewRecord(
        submission_id=submission.id, review_result="APPROVED", review_comment="通过",
        recognized_value=1, reviewer_id=school.id, reviewed_at=utcnow(), created_by=school.id,
    )
    db.add(review)
    db.flush()
    first.current_submission_id = submission.id
    first.last_review_id = review.id
    snapshot = CalculationSnapshot(
        plan_id=plan["id"], indicator_id=indicator.id, task_id=task.id, milestone_id=first.id,
        target_year=2026, scope_type="MILESTONE", formula_text="累计值", calculation_status="COMPLETED",
        result_json={"value": 1}, computed_at=utcnow(), created_by=school.id,
    )
    carryover = AnnualCarryover(
        carryover_no=f"CO-{uuid.uuid4().hex[:8]}", task_id=task.id, source_milestone_id=first.id,
        target_milestone_id=second.id, carryover_amount=1, reason="测试结转",
        before_snapshot_json={}, initiated_by=school.id, initiated_at=utcnow(), created_by=school.id,
    )
    compensation = AnnualCompensation(
        task_id=task.id, deficit_milestone_id=first.id, surplus_milestone_id=second.id,
        source_review_id=review.id, compensation_amount=1, created_by=school.id,
    )
    transfer = CrossCollegeTransfer(
        transfer_no=f"TR-{uuid.uuid4().hex[:8]}", indicator_id=indicator.id, source_task_id=task.id,
        target_college_org_id=college.primary_org_id, transfer_amount=1, reason="测试划转",
        before_snapshot_json={}, initiated_by=school.id, initiated_at=utcnow(), created_by=school.id,
    )
    objection = TaskObjection(task_id=task.id, objection_text="测试异议", created_by=college.id)
    todo = TodoNotification(
        recipient_user_id=college.id, plan_id=plan["id"], biz_type="TASK_ASSIGN", biz_id=task.id,
        title="测试待办", created_by=school.id,
    )
    db.add_all([snapshot, carryover, compensation, transfer, objection, todo])
    db.commit()

    response = delete_plan(client, plan["id"], reason="级联删除验证")
    assert response.status_code == 200, response.text
    assert not file_path.exists()
    for model, key in [
        (ConstructionPlan, plan["id"]),
        (OutputIndicator, indicator.id),
        (CollegeTask, task.id),
        (TaskAnnualMilestone, first.id),
        (TaskAnnualMilestone, second.id),
        (TaskSubmission, submission.id),
        (EvidenceMaterial, material.id),
        (ReviewRecord, review.id),
        (CalculationSnapshot, snapshot.id),
        (AnnualCarryover, carryover.id),
        (AnnualCompensation, compensation.id),
        (CrossCollegeTransfer, transfer.id),
        (TaskObjection, objection.id),
        (TodoNotification, todo.id),
    ]:
        assert db.get(model, key) is None, model.__name__
    log = db.execute(
        select(OperationLog).where(OperationLog.object_id == plan["id"], OperationLog.action_code == "DELETE")
    ).scalars().one()
    assert log.before_snapshot_json["counts"]["task_count"] == 1
    assert log.before_snapshot_json["counts"]["submission_count"] == 1
    assert log.before_snapshot_json["counts"]["material_count"] == 1


def test_delete_requires_confirmation_version_and_manage_permission(client_db):
    client, db = client_db
    plan = create_draft_plan(client)
    unconfirmed = delete_plan(client, plan["id"], confirm=False)
    assert unconfirmed.status_code == 422
    assert unconfirmed.json()["detail"]["code"] == "CONFIRMATION_REQUIRED"

    stale = delete_plan(client, plan["id"], expected_version=999)
    assert stale.status_code == 409
    assert stale.json()["detail"]["code"] == "OPTIMISTIC_LOCK_CONFLICT"

    login(client, "college")
    forbidden = delete_plan(client, plan["id"])
    assert forbidden.status_code == 403
    assert forbidden.json()["detail"]["code"] == "PLAN_FORBIDDEN"
    assert db.get(ConstructionPlan, plan["id"]) is not None

    login(client, "school")
    assert delete_plan(client, plan["id"]).status_code == 200
