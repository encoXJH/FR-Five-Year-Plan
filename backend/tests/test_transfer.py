from decimal import Decimal

from app.models import SysOrgUnit, TaskAnnualMilestone
from tests.helpers import create_execution, login


def test_transfer_strict_three_party_sequence_and_idempotency(client_db):
    client, db = client_db
    context = create_execution(
        client,
        db,
        annual=(4, 6),
        five_year=10,
        allocations=[("college", 6, [2, 4]), ("college2", 4, [2, 2])],
    )
    source, target = context["tasks"]
    login(client, "school")
    source_detail = client.get(f"/api/v1/tasks/{source['id']}").json()
    body = {
        "source_task_id": source["id"],
        "target_college_org_id": target["college_org_id"],
        "target_task_id": target["id"],
        "transfer_year": 2027,
        "transfer_amount": 1,
        "reason": "原学院任务调整，由同学院任务接收",
        "expected_version": source_detail["lock_version"],
    }
    first = client.post(f"/api/v1/indicators/{context['indicator']['id']}/transfers", json=body, headers={"Idempotency-Key": "transfer-one"})
    second = client.post(f"/api/v1/indicators/{context['indicator']['id']}/transfers", json=body, headers={"Idempotency-Key": "transfer-one"})
    assert first.status_code == second.status_code == 201
    transfer = first.json()
    assert second.json()["id"] == transfer["id"]
    login(client, "college2")
    early = client.post(
        f"/api/v1/transfers/{transfer['id']}/action",
        json={"step_code": "IN_COLLEGE", "action": "CONFIRM", "confirm": True, "expected_version": transfer["lock_version"]},
    )
    assert early.status_code == 409
    login(client, "college")
    out = client.post(
        f"/api/v1/transfers/{transfer['id']}/action",
        json={"step_code": "OUT_COLLEGE", "action": "CONFIRM", "confirm": True, "expected_version": transfer["lock_version"]},
    )
    assert out.status_code == 200, out.text
    login(client, "college2")
    incoming = client.post(
        f"/api/v1/transfers/{transfer['id']}/action",
        json={"step_code": "IN_COLLEGE", "action": "CONFIRM", "confirm": True, "expected_version": out.json()["lock_version"]},
    )
    assert incoming.status_code == 200, incoming.text
    assert incoming.json()["indicator_name"] == "测试累计指标"
    assert incoming.json()["source_college_org_name"] == "智能产业学院"
    assert incoming.json()["target_college_org_name"] == "游戏产业学院"
    login(client, "leader")
    effective = client.post(
        f"/api/v1/transfers/{transfer['id']}/action",
        json={"step_code": "SCHOOL_LEADER", "action": "CONFIRM", "confirm": True, "expected_version": incoming.json()["lock_version"]},
    )
    assert effective.status_code == 200, effective.text
    assert effective.json()["transfer_status"] == "EFFECTIVE"
    db.expire_all()
    source_2027 = db.query(TaskAnnualMilestone).filter_by(task_id=source["id"], target_year=2027).one()
    target_2027 = db.query(TaskAnnualMilestone).filter_by(task_id=target["id"], target_year=2027).one()
    assert source_2027.original_target_value == Decimal("4") and source_2027.current_target_value == Decimal("3")
    assert target_2027.original_target_value == Decimal("2") and target_2027.current_target_value == Decimal("3")
    assert source_2027.current_target_value + target_2027.current_target_value == Decimal("6")


def test_transfer_attachment_upload_inline_preview_and_unrelated_college_forbidden(client_db):
    client, db = client_db
    context = create_execution(
        client,
        db,
        annual=(4, 6),
        five_year=10,
        allocations=[("college", 6, [2, 4]), ("college2", 4, [2, 2])],
    )
    source, target = context["tasks"]
    login(client, "school")
    uploaded = client.post(
        "/api/v1/transfers/attachments",
        files={"file": ("划转说明.pdf", b"%PDF-1.4\ntransfer attachment", "application/pdf")},
    )
    assert uploaded.status_code == 201, uploaded.text
    attachment = uploaded.json()
    assert attachment["name"] == "划转说明.pdf"
    assert attachment["object_key"].startswith("transfer_attachments/") and "划转说明" not in attachment["object_key"]
    source_detail = client.get(f"/api/v1/tasks/{source['id']}").json()
    transfer_response = client.post(
        f"/api/v1/indicators/{context['indicator']['id']}/transfers",
        json={
            "source_task_id": source["id"],
            "target_college_org_id": target["college_org_id"],
            "target_task_id": target["id"],
            "transfer_year": 2027,
            "transfer_amount": 1,
            "reason": "联调附件测试",
            "attachment_json": [attachment],
            "expected_version": source_detail["lock_version"],
        },
    )
    assert transfer_response.status_code == 201, transfer_response.text
    transfer = transfer_response.json()
    url = f"/api/v1/transfers/{transfer['id']}/attachments/{attachment['object_key']}"
    preview = client.get(url)
    assert preview.status_code == 200 and preview.content.startswith(b"%PDF")
    assert "inline" in preview.headers["content-disposition"]
    assert "attachment" in client.get(url, params={"download": True}).headers["content-disposition"]
    login(client, "college")
    assert client.get(url).status_code == 200
    assert client.get(f"/api/v1/transfers/{transfer['id']}/attachments/transfer_attachments/not-registered.pdf").status_code == 404
    login(client, "college2")
    assert client.get(url).status_code == 200
    login(client, "admin")
    org = db.query(SysOrgUnit).filter_by(org_name="数字金融产业学院").one()
    third = client.post(
        "/api/v1/users",
        json={"account_name": "college3", "user_name": "无关学院负责人", "primary_org_id": org.id, "password": "Dev@2026", "role_codes": ["COLLEGE_OWNER"]},
    )
    assert third.status_code == 201, third.text
    assert client.get(url).status_code == 403
    login(client, "college3")
    assert client.get(url).status_code == 403
    denied_upload = client.post(
        "/api/v1/transfers/attachments",
        files={"file": ("越权.pdf", b"%PDF-1.4", "application/pdf")},
    )
    assert denied_upload.status_code == 403
