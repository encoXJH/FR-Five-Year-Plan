from datetime import date

from sqlalchemy import select

from app.models import CalculationSnapshot, SystemConfig
from tests.helpers import assignment_body, create_execution, create_ready_plan, login


def test_external_recognition_by_school_owner_and_five_year_snapshot(client_db):
    client, db = client_db
    context = create_ready_plan(
        client,
        db,
        calc_method="EXTERNAL",
        indicator_overrides={"external_data_source": "省厅年度统计公报，由校级负责人每年更新"},
    )
    indicator = context["indicator"]
    body = assignment_body(db, indicator, [("college", 10, [4, 6])])
    rejected = client.post(f"/api/v1/indicators/{indicator['id']}/assignment-preview", json=body)
    assert rejected.status_code == 422
    for year, value in [(2026, 4), (2027, 6)]:
        response = client.post(
            f"/api/v1/indicators/{indicator['id']}/external-recognitions",
            json={"target_year": year, "recognized_value": value, "external_source": "省厅年度统计公报"},
        )
        assert response.status_code == 201, response.text
        assert response.json()["calculation"]["achieved"] is True
    snapshots = db.execute(select(CalculationSnapshot).where(CalculationSnapshot.indicator_id == indicator["id"])).scalars().all()
    assert {item.scope_type for item in snapshots} == {"EXTERNAL_ANNUAL", "EXTERNAL_FIVE_YEAR"}
    five = [item for item in snapshots if item.scope_type == "EXTERNAL_FIVE_YEAR"][-1]
    assert five.result_json["actual"] == "6"
    login(client, "college")
    assert client.get(f"/api/v1/indicators/{indicator['id']}/external-recognitions").status_code == 403


def test_office_preview_failure_is_clear_and_does_not_leak_path(client_db):
    client, db = client_db
    context = create_execution(client, db)
    task = context["tasks"][0]
    config = db.execute(select(SystemConfig).where(SystemConfig.config_key == "libreoffice")).scalars().one()
    config.config_value = {"path": "Z:/definitely-missing/soffice"}
    db.commit()
    login(client, "college")
    detail = client.get(f"/api/v1/tasks/{task['id']}").json()
    milestone = detail["milestones"][0]
    draft_response = client.post(
        f"/api/v1/milestones/{milestone['id']}/draft",
        json={"reported_value": 2, "completion_desc": "Office 预览失败测试", "completion_date": date.today().isoformat()},
    )
    draft = draft_response.json()
    upload = client.post(
        f"/api/v1/submissions/{draft['id']}/materials",
        files={"file": ("evidence.docx", b"not a real office file", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")},
    )
    assert upload.status_code == 201, upload.text
    material = upload.json()
    assert material["preview_status"] == "FAILED"
    assert "LibreOffice" in material["preview_error"]
    assert "storage" not in material["preview_error"].lower() and "object_key" not in material
    submit = client.post(
        f"/api/v1/submissions/{draft['id']}/submit",
        json={"expected_version": draft["lock_version"], "confirm": True},
    )
    assert submit.status_code == 422
    assert submit.json()["detail"]["code"] == "PREVIEWABLE_MATERIAL_REQUIRED"


def test_gantt_and_csv_export_follow_scope(client_db):
    client, db = client_db
    context = create_execution(client, db)
    login(client, "school")
    gantt = client.get("/api/v1/gantt", params={"plan_id": context["plan"]["id"]})
    assert gantt.status_code == 200
    assert gantt.json()["empty"] is False and gantt.json()["years"] == [2026, 2027]
    first_task = gantt.json()["items"][0]["indicators"][0]["tasks"][0]
    assert first_task["college_org_name"] == "智能产业学院"
    exported = client.get("/api/v1/logs/export", params={"plan_id": context["plan"]["id"]})
    assert exported.status_code == 200
    assert exported.content.startswith(b"\xef\xbb\xbf")
    assert "筛选条件".encode("utf-8") in exported.content
    login(client, "college")
    assert client.get("/api/v1/logs/export").status_code == 403
    login(client, "college2")
    other_gantt = client.get("/api/v1/gantt", params={"plan_id": context["plan"]["id"]})
    assert other_gantt.status_code == 403


def test_frontend_is_served_same_origin_without_swallowing_api_404(client_db):
    client, _db = client_db
    page = client.get("/planning/plans")
    assert page.status_code == 200 and "五年建设任务管理平台" in page.text
    assert client.get("/api/v1/definitely-not-a-route").status_code == 404
