from decimal import Decimal

from app.models import TaskAnnualMilestone
from tests.helpers import create_draft_with_pdf, create_execution, login


def test_carryover_preserves_original_target_and_only_increases_receiver(client_db):
    client, db = client_db
    context = create_execution(client, db, annual=(4, 6), five_year=10)
    task = context["tasks"][0]
    login(client, "college")
    draft = create_draft_with_pdf(client, task, year=2026, value=2)
    submitted = client.post(
        f"/api/v1/submissions/{draft['id']}/submit",
        json={"expected_version": draft["lock_version"], "confirm": True},
    ).json()
    login(client, "school")
    reviewed = client.post(
        f"/api/v1/submissions/{submitted['id']}/review",
        json={"review_result": "APPROVED", "review_comment": "人工认定", "confirm": True, "expected_version": submitted["lock_version"]},
    )
    assert reviewed.status_code == 201, reviewed.text
    detail = client.get(f"/api/v1/tasks/{task['id']}").json()
    source = next(item for item in detail["milestones"] if item["target_year"] == 2026)
    target = next(item for item in detail["milestones"] if item["target_year"] == 2027)
    body = {
        "source_milestone_id": source["id"],
        "target_milestone_id": target["id"],
        "carryover_amount": 2,
        "reason": "当年成果延期验收",
        "expected_version": detail["lock_version"],
    }
    first = client.post(f"/api/v1/tasks/{task['id']}/carryovers", json=body, headers={"Idempotency-Key": "carry-one"})
    second = client.post(f"/api/v1/tasks/{task['id']}/carryovers", json=body, headers={"Idempotency-Key": "carry-one"})
    assert first.status_code == second.status_code == 201
    carryover = first.json()
    assert second.json()["id"] == carryover["id"]
    approved = client.post(
        f"/api/v1/carryovers/{carryover['id']}/action",
        json={"action": "APPROVE", "confirm": True, "expected_version": carryover["lock_version"]},
    )
    assert approved.status_code == 200, approved.text
    db.expire_all()
    source_row = db.get(TaskAnnualMilestone, source["id"])
    target_row = db.get(TaskAnnualMilestone, target["id"])
    assert source_row.original_target_value == Decimal("4") and source_row.current_target_value == Decimal("4")
    assert source_row.recognized_value == Decimal("2") and source_row.has_adjustment is True
    assert target_row.original_target_value == Decimal("6") and target_row.current_target_value == Decimal("8")
    assert approved.json()["after_snapshot_json"] is not None
