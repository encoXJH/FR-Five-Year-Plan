import uuid

from sqlalchemy import select

from app.models import (
    CollegeTask,
    ConstructionDirection,
    ConstructionDirectory,
    EvidenceMaterial,
    IndicatorAnnualTarget,
    OutputIndicator,
    ReviewRecord,
    TaskAnnualMilestone,
    TaskSubmission,
    TodoNotification,
    OperationLog,
    SysOrgUnit,
)
from tests.helpers import assignment_body, create_execution, create_ready_plan, login


def test_template_copy_modes_do_not_copy_execution_history(client_db):
    client, db = client_db
    context = create_execution(client, db)
    source = context["plan"]
    login(client, "school")
    copies = {}
    for mode in ["STRUCTURE_ONLY", "STRUCTURE_AND_TARGET"]:
        response = client.post(
            "/api/v1/plans/copy",
            json={
                "source_plan_id": source["id"],
                "plan_code": f"COPY-{mode}-{uuid.uuid4().hex[:8]}",
                "plan_name": f"复制-{mode}",
                "copy_mode": mode,
                "start_year": 2026,
                "end_year": 2027,
            },
        )
        assert response.status_code == 201, response.text
        copies[mode] = response.json()
    for mode, plan in copies.items():
        directions = db.execute(select(ConstructionDirection).where(ConstructionDirection.plan_id == plan["id"])).scalars().all()
        indicators = db.execute(select(OutputIndicator).where(OutputIndicator.plan_id == plan["id"])).scalars().all()
        tasks = db.execute(select(CollegeTask).join(OutputIndicator, OutputIndicator.id == CollegeTask.indicator_id).where(OutputIndicator.plan_id == plan["id"])).scalars().all()
        assert len(directions) == len(indicators) == len(tasks) == 1
        assert tasks[0].task_status == "DRAFT" and tasks[0].assigned_snapshot_json is None
        milestones = db.execute(select(TaskAnnualMilestone).where(TaskAnnualMilestone.task_id == tasks[0].id)).scalars().all()
        targets = db.execute(select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == indicators[0].id)).scalars().all()
        if mode == "STRUCTURE_ONLY":
            assert tasks[0].five_year_target_value is None
            assert all(item.current_target_value is None for item in milestones)
            assert all(item.professional_target_value is None for item in targets)
        else:
            assert tasks[0].five_year_target_value == 10
            assert sum(item.current_target_value for item in milestones) == 10
        assert not db.execute(select(TaskSubmission).join(TaskAnnualMilestone, TaskAnnualMilestone.id == TaskSubmission.milestone_id).where(TaskAnnualMilestone.task_id == tasks[0].id)).scalars().all()
        assert not db.execute(select(TodoNotification).where(TodoNotification.plan_id == plan["id"])).scalars().all()


def test_assignment_idempotency_optimistic_lock_and_college_scope(client_db):
    client, db = client_db
    from tests.helpers import assignment_body, create_ready_plan

    context = create_ready_plan(client, db)
    body = assignment_body(db, context["indicator"], [("college", 10, [4, 6])])
    key = "same-assignment-key"
    login(client, "school")
    first = client.post(f"/api/v1/indicators/{context['indicator']['id']}/assign", json=body, headers={"Idempotency-Key": key})
    second = client.post(f"/api/v1/indicators/{context['indicator']['id']}/assign", json=body, headers={"Idempotency-Key": key})
    assert first.status_code == second.status_code == 201
    assert first.json()["items"][0]["id"] == second.json()["items"][0]["id"]
    tasks = db.execute(select(CollegeTask).where(CollegeTask.indicator_id == context["indicator"]["id"])).scalars().all()
    assert len(tasks) == 1
    login(client, "college")
    stale = client.post(f"/api/v1/tasks/{tasks[0].id}/receive", json={"expected_version": 999})
    assert stale.status_code == 409
    assert client.get("/api/v1/tasks").json()["total"] == 1
    login(client, "college2")
    assert client.get(f"/api/v1/tasks/{tasks[0].id}").status_code == 403
    assert client.get("/api/v1/tasks").json()["total"] == 0
    login(client, "admin")
    assert client.post(f"/api/v1/plans/{context['plan']['id']}/close", json={"expected_version": 2, "confirm": True}).status_code == 403


def test_safe_user_options_filters_pagination_and_college_scope(client_db):
    client, db = client_db
    login(client, "school")
    response = client.get("/api/v1/users", params={"role": "COLLEGE_OWNER", "status": "ENABLED", "page": 1, "page_size": 1})
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["total"] == 2 and len(payload["items"]) == 1 and payload["page_size"] == 1
    assert "password_hash" not in payload["items"][0] and "auth_subject" not in payload["items"][0]
    second = client.get("/api/v1/users", params={"role": "COLLEGE_OWNER", "page": 2, "page_size": 1})
    assert second.json()["items"][0]["id"] != payload["items"][0]["id"]
    keyword = client.get("/api/v1/users", params={"keyword": "游戏", "role": "COLLEGE_OWNER"})
    assert keyword.json()["total"] == 1 and keyword.json()["items"][0]["account_name"] == "college2"
    login(client, "leader")
    assert client.get("/api/v1/users", params={"role": "COLLEGE_OWNER"}).status_code == 200
    for account in ["college", "college2"]:
        login(client, account)
        own = client.get("/api/v1/users")
        assert own.status_code == 200
        assert own.json()["total"] == 1
        assert own.json()["items"][0]["account_name"] == account
        assert all(item["primary_org_id"] == own.json()["items"][0]["primary_org_id"] for item in own.json()["items"])
    login(client, "admin")
    full = client.get("/api/v1/users", params={"page_size": 200})
    assert full.json()["total"] >= 6
    assert all("password_hash" not in item for item in full.json()["items"])


def test_new_directory_is_visible_to_school_owner_but_not_unrelated_college(client_db):
    client, _db = client_db
    login(client, "admin")
    leader = client.get("/api/v1/users", params={"role": "SCHOOL_LEADER"}).json()["items"][0]
    created = client.post(
        "/api/v1/directories",
        json={
            "directory_code": f"DIR-{uuid.uuid4().hex[:10]}",
            "directory_name": "暂无计划的新目录",
            "leader_user_id": leader["id"],
            "status": "DRAFT",
            "sort_no": 99,
        },
    )
    assert created.status_code == 201, created.text
    directory_id = created.json()["id"]
    login(client, "school")
    school_directories = client.get("/api/v1/directories")
    assert school_directories.status_code == 200
    assert directory_id in {item["id"] for item in school_directories.json()["items"]}
    login(client, "college")
    college_directories = client.get("/api/v1/directories")
    assert college_directories.status_code == 200
    assert directory_id not in {item["id"] for item in college_directories.json()["items"]}


def test_admin_deletes_empty_directory_but_not_directory_with_plans(client_db):
    client, db = client_db
    login(client, "admin")
    leader = client.get("/api/v1/users", params={"role": "SCHOOL_LEADER"}).json()["items"][0]
    created = client.post(
        "/api/v1/directories",
        json={
            "directory_code": f"DELDIR-{uuid.uuid4().hex[:8]}",
            "directory_name": "待删除空目录",
            "leader_user_id": leader["id"],
            "status": "DRAFT",
            "sort_no": 50,
        },
    )
    assert created.status_code == 201, created.text
    directory = created.json()
    listing = client.get("/api/v1/directories").json()["items"]
    assert next(item for item in listing if item["id"] == directory["id"])["plan_count"] == 0

    login(client, "school")
    forbidden = client.request(
        "DELETE",
        f"/api/v1/directories/{directory['id']}",
        json={"expected_version": directory["lock_version"], "confirm": True},
    )
    assert forbidden.status_code == 403

    login(client, "admin")
    unconfirmed = client.request(
        "DELETE",
        f"/api/v1/directories/{directory['id']}",
        json={"expected_version": directory["lock_version"], "confirm": False},
    )
    assert unconfirmed.status_code == 422
    deleted = client.request(
        "DELETE",
        f"/api/v1/directories/{directory['id']}",
        json={"expected_version": directory["lock_version"], "confirm": True, "reason": "清理空目录"},
    )
    assert deleted.status_code == 200, deleted.text
    assert db.get(ConstructionDirectory, directory["id"]) is None
    log = db.execute(
        select(OperationLog).where(
            OperationLog.object_type == "DIRECTORY",
            OperationLog.object_id == directory["id"],
            OperationLog.action_code == "DELETE",
        )
    ).scalars().one()
    assert log.operation_reason == "清理空目录"

    create_ready_plan(client, db)
    login(client, "admin")
    seeded = next(item for item in client.get("/api/v1/directories").json()["items"] if item["id"] == 1)
    assert seeded["plan_count"] >= 1
    blocked = client.request(
        "DELETE",
        "/api/v1/directories/1",
        json={"expected_version": seeded["lock_version"], "confirm": True},
    )
    assert blocked.status_code == 409
    assert blocked.json()["detail"]["code"] == "DIRECTORY_NOT_EMPTY"
    assert db.get(ConstructionDirectory, 1) is not None


def test_plan_list_detail_statistics_and_filters(client_db):
    client, db = client_db
    context = create_execution(client, db)
    login(client, "school")
    response = client.get("/api/v1/plans", params={"keyword": "集成测试", "plan_status": "IN_PROGRESS"})
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 1
    item = response.json()["items"][0]
    assert item["direction_count"] == 1 and item["indicator_count"] == 1 and item["task_count"] == 1
    assert item["directory_name"] and item["school_owner_name"] == "校级项目负责人"
    assert client.get("/api/v1/plans", params={"keyword": "不存在的关键字"}).json()["total"] == 0
    assert client.get("/api/v1/plans", params={"keyword": "集成测试", "plan_status": "DRAFT"}).json()["total"] == 0
    detail = client.get(f"/api/v1/plans/{context['plan']['id']}")
    assert detail.status_code == 200 and detail.json()["task_count"] == 1
    assert detail.json()["directory_name"] == item["directory_name"]


def test_admin_can_reenable_user_with_version_and_log(client_db):
    client, db = client_db
    login(client, "admin")
    org = db.execute(select(SysOrgUnit).where(SysOrgUnit.org_name == "游戏产业学院")).scalars().one()
    created = client.post(
        "/api/v1/users",
        json={"account_name": "reenable_user", "user_name": "待启用用户", "primary_org_id": org.id, "password": "Dev@2026", "role_codes": []},
    )
    assert created.status_code == 201, created.text
    user = created.json()
    disabled = client.post(
        f"/api/v1/users/{user['id']}/disable",
        json={"reason": "测试停用", "expected_version": user["lock_version"]},
    )
    assert disabled.status_code == 200, disabled.text
    enabled = client.patch(
        f"/api/v1/users/{user['id']}",
        json={"user_name": "重新启用用户", "primary_org_id": org.id, "status": "ENABLED", "expected_version": disabled.json()["lock_version"]},
    )
    assert enabled.status_code == 200, enabled.text
    assert enabled.json()["status"] == "ENABLED" and enabled.json()["user_name"] == "重新启用用户"
    assert db.execute(select(OperationLog).where(OperationLog.object_type == "USER", OperationLog.object_id == user["id"], OperationLog.action_code == "ENABLE")).scalars().one()
    stale = client.patch(f"/api/v1/users/{user['id']}", json={"user_name": "冲突", "expected_version": 1})
    assert stale.status_code == 409
    login(client, "reenable_user")
    assert client.get("/api/v1/auth/me").status_code == 200


def test_confirmed_blank_annual_targets_can_publish_and_assign(client_db):
    client, db = client_db
    context = create_ready_plan(client, db, annual=(1, None), five_year=1)
    body = assignment_body(db, context["indicator"], [("college", 1, (1, None))])
    login(client, "school")
    response = client.post(
        f"/api/v1/indicators/{context['indicator']['id']}/assign",
        json=body,
        headers={"Idempotency-Key": f"assign-blank-{uuid.uuid4().hex}"},
    )
    assert response.status_code == 201, response.text
    task = response.json()["items"][0]
    detail = client.get(f"/api/v1/tasks/{task['id']}").json()
    assert detail["milestones"][1]["current_target_value"] is None
    assert client.get("/api/v1/tasks", params={"indicator_id": context["indicator"]["id"]}).json()["total"] == 1
    assert client.get("/api/v1/tasks", params={"indicator_id": context["indicator"]["id"] + 1}).json()["total"] == 0


def test_blank_indicator_year_allows_college_allocation(client_db):
    client, db = client_db
    context = create_ready_plan(client, db, annual=(1, None), five_year=1)
    login(client, "school")
    response = client.post(
        f"/api/v1/indicators/{context['indicator']['id']}/assign",
        json=assignment_body(db, context["indicator"], [("college", 1, (1, 5))]),
        headers={"Idempotency-Key": f"assign-blank-year-{uuid.uuid4().hex}"},
    )
    assert response.status_code == 201, response.text
    detail = client.get(f"/api/v1/tasks/{response.json()['items'][0]['id']}").json()
    assert [item["current_target_value"] for item in detail["milestones"]] == ["1.0000", "5.0000"]

    mismatch = create_ready_plan(client, db, annual=(1, None), five_year=1)
    rejected = client.post(
        f"/api/v1/indicators/{mismatch['indicator']['id']}/assign",
        json=assignment_body(db, mismatch["indicator"], [("college", 1, (3, None))]),
        headers={"Idempotency-Key": f"assign-mismatch-{uuid.uuid4().hex}"},
    )
    assert rejected.status_code == 422
    assert rejected.json()["detail"]["code"] == "ASSIGNMENT_VALIDATION_FAILED"


def test_task_list_exposes_progress_summary(client_db):
    client, db = client_db
    context = create_ready_plan(client, db, annual=(5, 5), five_year=10)
    login(client, "school")
    assigned = client.post(
        f"/api/v1/indicators/{context['indicator']['id']}/assign",
        json=assignment_body(db, context["indicator"], [("college", 10, (5, 5))]),
        headers={"Idempotency-Key": f"assign-progress-{uuid.uuid4().hex}"},
    )
    assert assigned.status_code == 201, assigned.text
    task = assigned.json()["items"][0]
    listed = client.get("/api/v1/tasks", params={"indicator_id": context["indicator"]["id"]}).json()["items"][0]
    assert listed["milestone_count"] == 2
    assert listed["completed_milestone_count"] == 0
    assert listed["progress_percent"] == 0
    assert listed["value_kind"] == "DECIMAL"

    milestone = db.execute(
        select(TaskAnnualMilestone).where(
            TaskAnnualMilestone.task_id == task["id"],
            TaskAnnualMilestone.target_year == 2026,
        )
    ).scalars().one()
    milestone.recognized_value = 5
    milestone.milestone_status = "COMPLETED"
    db.commit()
    updated = client.get("/api/v1/tasks", params={"indicator_id": context["indicator"]["id"]}).json()["items"][0]
    assert updated["completed_milestone_count"] == 1
    assert updated["progress_percent"] == 50


def test_indicator_detail_exposes_unit_value_kind_for_assignment(client_db):
    client, db = client_db
    context = create_ready_plan(client, db)
    detail = client.get(f"/api/v1/indicators/{context['indicator']['id']}")
    assert detail.status_code == 200
    assert detail.json()["value_kind"] == "DECIMAL"

    login(client, "school")
    plan = client.post(
        "/api/v1/plans",
        json={
            "plan_code": f"BOOL-{uuid.uuid4().hex[:10]}",
            "directory_id": 1,
            "plan_name": "是否型指标计划",
            "construction_scope": "测试范围",
            "start_year": 2026,
            "end_year": 2027,
        },
    ).json()
    direction = client.post(
        "/api/v1/directions",
        json={"plan_id": plan["id"], "direction_code": "D1", "direction_name": "测试方向", "sort_no": 1},
    ).json()
    created = client.post(
        "/api/v1/indicators",
        json={
            "direction_id": direction["id"],
            "indicator_code": "B1",
            "indicator_name": "是否型指标",
            "indicator_desc": "是否型指标测试",
            "indicator_type_code": "DEVELOPMENT",
            "unit_code": "YES_NO",
            "calc_method": "BOOLEAN",
            "professional_five_year_target_bool": True,
            "professional_five_year_target_raw": "是",
            "annual_targets": [
                {"target_year": 2026, "professional_target_bool": True, "professional_target_raw": "是"},
                {"target_year": 2027, "professional_target_bool": None, "professional_target_raw": None},
            ],
        },
    )
    assert created.status_code == 201, created.text
    detail = client.get(f"/api/v1/indicators/{created.json()['id']}")
    assert detail.status_code == 200
    assert detail.json()["value_kind"] == "BOOLEAN"


def test_indicators_can_be_added_after_plan_publish_but_not_after_close(client_db):
    client, db = client_db
    context = create_ready_plan(client, db)
    plan = context["plan"]
    login(client, "school")
    payload = {
        "direction_id": context["direction"]["id"],
        "indicator_code": "I2",
        "indicator_name": "发布后新增指标",
        "indicator_desc": "计划发布后继续新增的关键产出指标",
        "indicator_type_code": "DEVELOPMENT",
        "unit_code": "ITEM_COUNT",
        "target_direction": "GE",
        "calc_method": "CUMULATIVE",
        "statistical_rule": "经校级审核认定的实际成果数量",
        "annual_targets": [{"target_year": 2026}, {"target_year": 2027}],
    }
    created = client.post("/api/v1/indicators", json=payload)
    assert created.status_code == 201, created.text
    assert created.json()["indicator_status"] == "DRAFT"

    detail = client.get(f"/api/v1/plans/{plan['id']}").json()
    closed = client.post(
        f"/api/v1/plans/{plan['id']}/close",
        json={"confirm": True, "reason": "发布后新增指标测试结项", "expected_version": detail["lock_version"]},
    )
    assert closed.status_code == 200, closed.text
    rejected = client.post("/api/v1/indicators", json={**payload, "indicator_code": "I3"})
    assert rejected.status_code == 409
    assert rejected.json()["detail"]["code"] == "PLAN_CLOSED"


def test_leader_dashboard_reports_each_indicator_progress(client_db):
    client, db = client_db
    context = create_execution(client, db)
    task = context["tasks"][0]
    milestone = db.execute(
        select(TaskAnnualMilestone).where(
            TaskAnnualMilestone.task_id == task["id"],
            TaskAnnualMilestone.target_year == 2026,
        )
    ).scalars().one()
    milestone.recognized_value = 4
    milestone.milestone_status = "COMPLETED"
    db.commit()

    login(client, "leader")
    response = client.get("/api/v1/dashboard/overview", params={"plan_id": context["plan"]["id"]})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["summary"]["direction_count"] == 1
    assert data["summary"]["indicator_count"] == 1
    assert data["summary"]["task_count"] == 1
    assert data["summary"]["milestone_count"] == 2
    assert data["summary"]["completed"] == 1
    assert data["summary"]["completion_percent"] == 50
    indicator = data["indicators"][0]
    assert indicator["progress_basis"] == "VALUE"
    assert indicator["recognized_value"] == 4
    assert indicator["target_value"] == 10
    assert indicator["progress_percent"] == 40
    assert data["directions"][0]["progress_percent"] == 50
    assert data["colleges"][0]["milestone_count"] == 2
    assert data["colleges"][0]["completed"] == 1
    assert data["years"][0]["target_year"] == 2026
    assert data["years"][0]["completion_percent"] == 100

    login(client, "college")
    forbidden = client.get("/api/v1/dashboard/overview", params={"plan_id": context["plan"]["id"]})
    assert forbidden.status_code == 403


def test_unconfirmed_blank_year_returns_one_actionable_error(client_db):
    client, db = client_db
    context = create_ready_plan(client, db, annual=(1, None), five_year=1)
    indicator = context["indicator"]
    target = next(item for item in indicator["annual_targets"] if item["target_year"] == 2027)
    login(client, "school")
    updated = client.patch(
        f"/api/v1/indicators/{indicator['id']}",
        json={
            "annual_targets": [{
                "target_year": 2027,
                "professional_target_value": None,
                "professional_target_raw": None,
                "confirm_status": "PENDING",
                "expected_version": target["lock_version"],
            }],
            "expected_version": indicator["lock_version"],
        },
    )
    assert updated.status_code == 200, updated.text
    response = client.post(
        f"/api/v1/indicators/{indicator['id']}/publish",
        json={"confirm": True, "expected_version": updated.json()["lock_version"]},
    )
    assert response.status_code == 422
    assert response.json()["detail"]["details"] == ["2027 年目标未确认（填写值、未提供或不适用均需确认）"]
