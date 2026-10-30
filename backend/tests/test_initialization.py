from pathlib import Path

from sqlalchemy import func, select

from app.config import BACKEND_DIR
from app.models import ConstructionPlan, IndicatorAnnualTarget, OperationLog, OutputIndicator, SourceImportFile, SourceImportRow
from scripts.initialize import SCHOOL_PLAN_CODE, TASK_PLAN_CODE, initialize
from tests.helpers import login


def test_excel_initialization_exact_and_idempotent(client_db):
    client, db = client_db
    stats = initialize(db, with_college2=True)
    assert stats["acceptance_passed"] is True
    assert (stats["plan_count"], stats["direction_count"], stats["indicator_count"], stats["annual_target_count"], stats["college_task_count"]) == (2, 25, 128, 640, 0)
    assert (stats["source_rows"], stats["import_file_count"]) == (128, 2)
    school = stats["projects"][SCHOOL_PLAN_CODE]
    task = stats["projects"][TASK_PLAN_CODE]
    assert (school["direction_count"], school["indicator_count"], school["annual_target_count"], school["source_rows"]) == (10, 38, 190, 38)
    assert (task["direction_count"], task["indicator_count"], task["annual_target_count"], task["source_rows"]) == (15, 90, 450, 90)
    plans = db.execute(select(ConstructionPlan).where(ConstructionPlan.plan_code.in_([SCHOOL_PLAN_CODE, TASK_PLAN_CODE]))).scalars().all()
    assert len(plans) == 2 and len({plan.directory_id for plan in plans}) == 1

    school_rows = db.execute(select(SourceImportRow).where(SourceImportRow.import_file_id == school["import_file_id"]).order_by(SourceImportRow.row_number)).scalars().all()
    task_rows = db.execute(select(SourceImportRow).where(SourceImportRow.import_file_id == task["import_file_id"]).order_by(SourceImportRow.row_number)).scalars().all()
    assert (school_rows[0].row_number, school_rows[-1].row_number) == (4, 41)
    assert (task_rows[0].row_number, task_rows[-1].row_number) == (3, 92)
    assert all(row.direction_raw for row in school_rows + task_rows)
    assert all(row.professional_five_year_raw is None and row.professional_2026_raw is None for row in school_rows)
    school_indicators = [db.get(OutputIndicator, row.indicator_id) for row in school_rows]
    assert all(item.professional_five_year_target_raw is None for item in school_indicators)
    school_annual = db.execute(select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id.in_([item.id for item in school_indicators]))).scalars().all()
    assert all(item.professional_target_raw is None for item in school_annual)
    divided_row = next(row for row in school_rows if row.school_2027_division_raw)
    divided_target = db.execute(select(IndicatorAnnualTarget).where(IndicatorAnnualTarget.indicator_id == divided_row.indicator_id, IndicatorAnnualTarget.target_year == 2027)).scalar_one()
    assert divided_target.school_division_raw == divided_row.school_2027_division_raw
    blank_unit_rows = [row for row in school_rows if row.indicator_unit_raw is None]
    assert len(blank_unit_rows) == 2
    assert all(db.get(OutputIndicator, row.indicator_id).unit_code == "NOT_APPLICABLE" for row in blank_unit_rows)

    zero_rows = [row for row in task_rows if row.professional_five_year_raw == "0"]
    assert len(zero_rows) == 24
    assert all(row.professional_five_year_allocation_raw == "无" for row in zero_rows)
    assert all(db.get(OutputIndicator, row.indicator_id).professional_five_year_target_value == 0 for row in zero_rows)
    dash_rows = [row for row in task_rows if row.indicator_unit_raw == "—"]
    assert len(dash_rows) == 2
    assert all(row.professional_five_year_raw == "—" and row.professional_2026_raw == "—" for row in dash_rows)
    abnormal = next(row for row in task_rows if any("不一致" in issue["message"] for issue in row.validation_json))
    assert abnormal.professional_five_year_allocation_raw or abnormal.professional_2026_allocation_raw

    counts = {
        model: db.execute(select(func.count()).select_from(model)).scalar_one()
        for model in (SourceImportFile, SourceImportRow, OutputIndicator, IndicatorAnnualTarget, OperationLog)
    }
    second = initialize(db, with_college2=True)
    assert second["duplicate_skipped"] is True and second["newly_confirmed"] == 0
    assert all(project["duplicate_skipped"] and project["newly_confirmed"] == 0 for project in second["projects"].values())
    assert {
        model: db.execute(select(func.count()).select_from(model)).scalar_one()
        for model in counts
    } == counts


def test_health_and_login_cookie(client_db):
    client, db = client_db
    assert client.get("/api/health").json()["status"] == "ok"
    bad = client.post("/api/v1/auth/login", json={"account_name": "school", "password": "wrong"})
    assert bad.status_code == 401
    response = client.post("/api/v1/auth/login", json={"account_name": "school", "password": "Dev@2026"})
    assert response.status_code == 200
    assert "five_year_session" in response.cookies
    assert "HttpOnly" in response.headers["set-cookie"]
    me = client.get("/api/v1/auth/me")
    assert me.status_code == 200 and me.json()["roles"] == ["SCHOOL_OWNER"]


def test_import_list_scope_filters_and_validation_summary(client_db):
    client, db = client_db
    excel = BACKEND_DIR.parent / "docs" / "智能生产装备与技术专业群建设目标量化清单_合并去重版.xlsx"
    stats = initialize(db, excel, with_college2=True)
    login(client, "school")
    response = client.get("/api/v1/imports", params={"plan_id": stats["plan_id"], "import_status": "COMPLETED"})
    assert response.status_code == 200, response.text
    assert response.json()["total"] == 1
    batch = response.json()["items"][0]
    assert batch["id"] == stats["import_file_id"] and "items" not in batch
    assert sum(batch["validation_summary"].values()) == 97
    assert client.get("/api/v1/imports", params={"import_status": "PREVIEW"}).json()["total"] == 0
    login(client, "leader")
    assert client.get("/api/v1/imports").json()["total"] == 1
    login(client, "college")
    assert client.get("/api/v1/imports").json()["total"] == 1
    login(client, "admin")
    assert client.get("/api/v1/imports").json()["total"] == 0
