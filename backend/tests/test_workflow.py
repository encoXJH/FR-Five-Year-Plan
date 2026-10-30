from decimal import Decimal

from sqlalchemy import select

from app.models import AnnualCompensation, ReviewRecord, TaskAnnualMilestone, TaskSubmission
from tests.helpers import create_draft_with_pdf, create_execution, login


def _submit(client, submission, key):
    body = {"expected_version": submission["lock_version"], "confirm": True}
    first = client.post(f"/api/v1/submissions/{submission['id']}/submit", json=body, headers={"Idempotency-Key": key})
    assert first.status_code == 200, first.text
    second = client.post(f"/api/v1/submissions/{submission['id']}/submit", json=body, headers={"Idempotency-Key": key})
    assert second.status_code == 200 and second.json()["id"] == first.json()["id"]
    return first.json()


def _approve(client, submission, key):
    login(client, "school")
    body = {
        "review_result": "APPROVED",
        "review_comment": "材料与成果经人工核验",
        "confirm": True,
        "expected_version": submission["lock_version"],
    }
    first = client.post(f"/api/v1/submissions/{submission['id']}/review", json=body, headers={"Idempotency-Key": key})
    assert first.status_code == 201, first.text
    second = client.post(f"/api/v1/submissions/{submission['id']}/review", json=body, headers={"Idempotency-Key": key})
    assert second.status_code == 201 and second.json()["id"] == first.json()["id"]
    return first.json()


def test_material_upload_retries_are_idempotent(client_db):
    client, db = client_db
    context = create_execution(client, db)
    task = context["tasks"][0]
    login(client, "college")
    detail_task = client.get(f"/api/v1/tasks/{task['id']}").json()
    milestone = detail_task["milestones"][0]
    draft = client.post(
        f"/api/v1/milestones/{milestone['id']}/draft",
        json={"reported_value": 1, "completion_desc": "材料幂等验证", "completion_date": "2026-10-29"},
    ).json()
    files = {"file": ("evidence.pdf", b"%PDF-1.4\n% same upload", "application/pdf")}
    first = client.post(f"/api/v1/submissions/{draft['id']}/materials", files=files, headers={"Idempotency-Key": "same-material-upload"})
    assert first.status_code == 201, first.text
    second = client.post(f"/api/v1/submissions/{draft['id']}/materials", files=files, headers={"Idempotency-Key": "same-material-upload"})
    assert second.status_code == 201 and second.json()["id"] == first.json()["id"]
    detail = client.get(f"/api/v1/submissions/{draft['id']}").json()
    assert len(detail["materials"]) == 1


def test_submission_withdraw_return_resubmit_and_material_permission(client_db):
    client, db = client_db
    context = create_execution(client, db)
    task = context["tasks"][0]
    login(client, "college")
    draft = create_draft_with_pdf(client, task, value=2)
    submitted = _submit(client, draft, "submit-withdraw")
    withdrawn = client.post(
        f"/api/v1/submissions/{submitted['id']}/withdraw",
        json={"expected_version": submitted["lock_version"], "reason": "补充完成说明"},
    )
    assert withdrawn.status_code == 200, withdrawn.text
    second_draft = withdrawn.json()
    assert second_draft["version_no"] == 2 and second_draft["submission_status"] == "DRAFT"
    second_submitted = _submit(client, second_draft, "submit-return")
    login(client, "school")
    returned = client.post(
        f"/api/v1/submissions/{second_submitted['id']}/review",
        json={
            "review_result": "RETURNED",
            "review_comment": "请补充具体成果清单",
            "confirm": True,
            "expected_version": second_submitted["lock_version"],
        },
    )
    assert returned.status_code == 201, returned.text
    login(client, "college")
    third_draft = create_draft_with_pdf(client, task, value=2)
    assert third_draft["version_no"] == 3
    third_submitted = _submit(client, third_draft, "submit-final")
    login(client, "school")
    pending = client.get("/api/v1/reviews")
    assert pending.status_code == 200 and pending.json()["total"] == 1
    pending_item = pending.json()["items"][0]
    assert pending_item["id"] == third_submitted["id"]
    assert pending_item["milestone"]["id"] == third_submitted["milestone_id"]
    assert pending_item["task"]["id"] == task["id"] and pending_item["indicator"]["id"] == context["indicator"]["id"]
    login(client, "college")
    assert client.get("/api/v1/reviews").json()["total"] == 1
    login(client, "college2")
    assert client.get("/api/v1/reviews").json()["total"] == 0
    _approve(client, third_submitted, "review-final")
    assert client.get("/api/v1/reviews").json()["total"] == 0
    versions = db.execute(select(TaskSubmission).where(TaskSubmission.milestone_id == third_draft["milestone_id"]).order_by(TaskSubmission.version_no)).scalars().all()
    assert [item.submission_status for item in versions] == ["WITHDRAWN", "RETURNED", "APPROVED"]
    assert len(db.execute(select(ReviewRecord)).scalars().all()) == 2
    login(client, "college2")
    forbidden = client.get(f"/api/v1/submissions/{third_submitted['id']}")
    assert forbidden.status_code == 403
    material_id = third_submitted["materials"][0]["id"]
    assert client.get(f"/api/v1/materials/{material_id}/preview").status_code == 403


def test_unmet_then_earliest_deficit_compensation_without_double_count(client_db):
    client, db = client_db
    context = create_execution(client, db, annual=(4, 6), five_year=10)
    task = context["tasks"][0]
    login(client, "college")
    first = create_draft_with_pdf(client, task, year=2026, value=2)
    first = _submit(client, first, "submit-2026")
    _approve(client, first, "review-2026")
    milestone_2026 = db.get(TaskAnnualMilestone, first["milestone_id"])
    assert milestone_2026.recognized_value == Decimal("2")
    assert milestone_2026.shortfall_value == Decimal("2")
    assert milestone_2026.milestone_status == "RECOGNIZED_UNMET"
    login(client, "college")
    second = create_draft_with_pdf(client, task, year=2027, value=8)
    second = _submit(client, second, "submit-2027")
    second_review = _approve(client, second, "review-2027")
    db.expire_all()
    milestone_2026 = db.get(TaskAnnualMilestone, first["milestone_id"])
    milestone_2027 = db.get(TaskAnnualMilestone, second["milestone_id"])
    compensation = db.execute(select(AnnualCompensation).where(AnnualCompensation.compensation_status == "EFFECTIVE")).scalars().one()
    assert compensation.deficit_milestone_id == milestone_2026.id
    assert compensation.surplus_milestone_id == milestone_2027.id
    assert compensation.compensation_amount == Decimal("2")
    assert milestone_2026.recognized_value == Decimal("2") and milestone_2026.covered_value == Decimal("2")
    assert milestone_2026.shortfall_value == 0 and milestone_2026.milestone_status == "COMPLETED"
    assert milestone_2027.recognized_value == Decimal("8")
    login(client, "school")
    calculation = client.get(f"/api/v1/tasks/{task['id']}/calculations")
    assert calculation.status_code == 200
    assert Decimal(calculation.json()["five_year_recognized_total"]) == Decimal("10")
    assert isinstance(calculation.json()["five_year_recognized_total"], str)

    current_submission = client.get(f"/api/v1/submissions/{second['id']}").json()
    corrected = client.post(
        f"/api/v1/reviews/{second_review['id']}/correct",
        json={
            "correction_reason": "复核发现超额部分属于下一年度",
            "review_comment": "更正 2027 年认定值",
            "recognized_value": 6,
            "recognition_basis": "校级复核记录",
            "confirm": True,
            "expected_version": current_submission["lock_version"],
        },
    )
    assert corrected.status_code == 201, corrected.text
    assert corrected.json()["corrects_review_id"] == second_review["id"]
    db.expire_all()
    assert db.get(AnnualCompensation, compensation.id).compensation_status == "REVERSED"
    milestone_2026 = db.get(TaskAnnualMilestone, first["milestone_id"])
    assert milestone_2026.shortfall_value == Decimal("2") and milestone_2026.covered_value == 0
    assert db.get(ReviewRecord, second_review["id"]).recognized_value == Decimal("8")
