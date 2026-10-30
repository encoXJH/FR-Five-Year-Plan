import uuid
from datetime import date

from sqlalchemy import select

from app.models import ConstructionPlan, OutputIndicator, SysOrgUnit, SysUser


PASSWORD = "Dev@2026"


def login(client, account):
    client.cookies.clear()
    response = client.post("/api/v1/auth/login", json={"account_name": account, "password": PASSWORD})
    assert response.status_code == 200, response.text
    return response.json()


def create_ready_plan(client, db, annual=(4, 6), five_year=10, calc_method="CUMULATIVE", indicator_overrides=None):
    login(client, "school")
    suffix = uuid.uuid4().hex[:10]
    plan_response = client.post(
        "/api/v1/plans",
        json={
            "plan_code": f"TEST-{suffix}",
            "directory_id": 1,
            "plan_name": "集成测试计划",
            "construction_scope": "测试专业群",
            "start_year": 2026,
            "end_year": 2025 + len(annual),
        },
    )
    assert plan_response.status_code == 201, plan_response.text
    plan = plan_response.json()
    direction_response = client.post(
        "/api/v1/directions",
        json={"plan_id": plan["id"], "direction_code": "D1", "direction_name": "测试方向", "sort_no": 1},
    )
    direction = direction_response.json()
    annual_targets = [
        {
            "target_year": 2026 + index,
            "professional_target_value": value,
            "professional_target_raw": str(value),
            "confirm_status": "CONFIRMED",
        }
        for index, value in enumerate(annual)
    ]
    indicator_payload = {
        "direction_id": direction["id"],
        "indicator_code": "I1",
        "indicator_name": "测试累计指标",
        "indicator_desc": "用于测试完整业务流程",
        "indicator_type_code": "DEVELOPMENT",
        "unit_code": "ITEM_COUNT",
        "target_direction": "GE",
        "calc_method": calc_method,
        "statistical_rule": "经校级审核的实际成果数量",
        "professional_five_year_target_value": five_year,
        "professional_five_year_target_raw": str(five_year),
        "annual_targets": annual_targets,
    }
    indicator_payload.update(indicator_overrides or {})
    indicator_response = client.post("/api/v1/indicators", json=indicator_payload)
    assert indicator_response.status_code == 201, indicator_response.text
    indicator = indicator_response.json()
    confirmed = client.patch(
        f"/api/v1/indicators/{indicator['id']}",
        json={"parse_status": "CONFIRMED", "expected_version": indicator["lock_version"]},
    )
    assert confirmed.status_code == 200, confirmed.text
    indicator = confirmed.json()
    if plan["plan_status"] == "PUBLISHED":
        published_plan = plan
    else:
        published = client.post(f"/api/v1/plans/{plan['id']}/publish", json={"expected_version": plan["lock_version"]})
        assert published.status_code == 200, published.text
        published_plan = published.json()
    return {"plan": published_plan, "direction": direction, "indicator": indicator}


def assignment_body(db, indicator, allocations):
    tasks = []
    for account, five_year, annual_values in allocations:
        owner = db.execute(select(SysUser).where(SysUser.account_name == account)).scalars().one()
        org = db.get(SysOrgUnit, owner.primary_org_id)
        tasks.append(
            {
                "college_org_id": org.id,
                "owner_user_id": owner.id,
                "execution_scope": f"{account} 执行范围",
                "five_year_target_value": five_year,
                "annual_targets": [
                    {"target_year": 2026 + index, "target_value": value}
                    for index, value in enumerate(annual_values)
                ],
            }
        )
    return {"tasks": tasks, "expected_version": indicator["lock_version"]}


def create_execution(client, db, annual=(4, 6), five_year=10, allocations=None):
    context = create_ready_plan(client, db, annual=annual, five_year=five_year)
    allocations = allocations or [("college", five_year, annual)]
    body = assignment_body(db, context["indicator"], allocations)
    login(client, "school")
    response = client.post(
        f"/api/v1/indicators/{context['indicator']['id']}/assign",
        json=body,
        headers={"Idempotency-Key": f"assign-{uuid.uuid4().hex}"},
    )
    assert response.status_code == 201, response.text
    context["assignment_body"] = body
    context["tasks"] = response.json()["items"]
    for task, allocation in zip(context["tasks"], allocations):
        login(client, allocation[0])
        received = client.post(f"/api/v1/tasks/{task['id']}/receive", json={"expected_version": task["lock_version"]})
        assert received.status_code == 200, received.text
    return context


def create_draft_with_pdf(client, task, year=2026, value=2):
    milestone = next(item for item in task["milestones"] if item["target_year"] == year) if "milestones" in task else None
    if milestone is None:
        detail = client.get(f"/api/v1/tasks/{task['id']}")
        assert detail.status_code == 200, detail.text
        task = detail.json()
        milestone = next(item for item in task["milestones"] if item["target_year"] == year)
    response = client.post(
        f"/api/v1/milestones/{milestone['id']}/draft",
        json={
            "reported_value": value,
            "completion_desc": "已完成并取得佐证",
            "achievement_name": "测试成果",
            "completion_date": date.today().isoformat(),
        },
    )
    assert response.status_code == 201, response.text
    submission = response.json()
    upload = client.post(
        f"/api/v1/submissions/{submission['id']}/materials",
        files={"file": ("evidence.pdf", b"%PDF-1.4\n% test evidence", "application/pdf")},
    )
    assert upload.status_code == 201, upload.text
    assert upload.json()["preview_status"] == "READY"
    return submission
