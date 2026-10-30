import argparse
import json
from pathlib import Path

from sqlalchemy import select

from app.config import BACKEND_DIR
from app.database import SessionLocal
from app.models import ConstructionPlan, SourceImportRow, SysUser
from app.services.imports import confirm_import_row, import_excel, initialization_stats, refresh_import_status
from app.utils import json_value
from scripts.seed import seed_all


DEFAULT_EXCEL = BACKEND_DIR.parent / "docs" / "智能生产装备与技术专业群建设目标量化清单_合并去重版.xlsx"
SCHOOL_EXCEL = BACKEND_DIR.parent / "docs" / "《智能生产装备与技术专业群完整版建设方案》学校建设目标清单.xlsx"
TASK_EXCEL = BACKEND_DIR.parent / "docs" / "智能生产装备与技术专业群建设目标量化清单——任务分配.xlsx"
SCHOOL_PLAN_CODE = "PLAN-SMART-SCHOOL-2026-2030"
TASK_PLAN_CODE = "PLAN-SMART-TASK-2026-2030"
PROJECT_SPECS = [
    {
        "plan_code": SCHOOL_PLAN_CODE,
        "plan_name": "智能生产装备与技术专业群学校建设项目",
        "construction_scope": "智能生产装备与技术专业群",
        "excel_path": SCHOOL_EXCEL,
        "source_format": "SCHOOL",
        "expected": {"direction_count": 10, "indicator_count": 38, "annual_target_count": 190, "college_task_count": 0, "source_rows": 38},
    },
    {
        "plan_code": TASK_PLAN_CODE,
        "plan_name": "智能生产装备与技术专业群任务分配项目",
        "construction_scope": "智能生产装备与技术专业群",
        "excel_path": TASK_EXCEL,
        "source_format": "TASK",
        "expected": {"direction_count": 15, "indicator_count": 90, "annual_target_count": 450, "college_task_count": 0, "source_rows": 90},
    },
]


def _initialize_project(db, *, plan: ConstructionPlan, school: SysUser, spec: dict) -> dict:
    excel_path = spec["excel_path"]
    batch, duplicate = import_excel(
        db,
        plan=plan,
        user=school,
        file_name=excel_path.name,
        content=excel_path.read_bytes(),
        duplicate_mode="SKIP",
        source_format=spec["source_format"],
    )
    rows = db.execute(select(SourceImportRow).where(SourceImportRow.import_file_id == batch.id).order_by(SourceImportRow.id)).scalars().all()
    confirmed = 0
    for row in rows:
        if row.indicator_id:
            continue
        overrides = {"indicator_unit_name": "—"} if spec["source_format"] == "SCHOOL" and not row.indicator_unit_raw else None
        confirm_import_row(db, row=row, plan=plan, user=school, overrides=overrides)
        confirmed += 1
    refresh_import_status(db, batch)
    db.commit()
    stats = initialization_stats(db, plan)
    stats.update({"plan_code": plan.plan_code, "duplicate_skipped": duplicate, "newly_confirmed": confirmed, "import_file_id": batch.id, "source_rows": len(rows)})
    stats["acceptance_passed"] = all(stats[key] == value for key, value in spec["expected"].items())
    if not stats["acceptance_passed"]:
        raise RuntimeError(f"初始化核验失败：{json.dumps(json_value(stats), ensure_ascii=False)}")
    return stats


def _initialize_legacy(db, excel_path: Path, with_college2: bool) -> dict:
    seed = seed_all(db, with_college2=with_college2)
    db.commit()
    plan = db.get(ConstructionPlan, seed["plan_id"])
    school = db.get(SysUser, seed["users"]["school"])
    spec = {"excel_path": excel_path, "source_format": "MERGED", "expected": {}}
    stats = _initialize_project(db, plan=plan, school=school, spec=spec)
    stats["acceptance_passed"] = (
        stats["direction_count"] == 15
        and stats["indicator_count"] == 97
        and stats["annual_target_count"] == 485
        and stats["college_task_count"] == 0
        and stats["indicator_types"] == {"CONSTRAINED": 28, "DEVELOPMENT": 59, "FEATURED": 10}
        and stats["unit_count"] == 16
        and stats["professional_five_year_empty"] == 7
        and stats["professional_2026_empty"] == 7
        and all(stats["school_division_nonempty"][str(year)] == 5 for year in range(2027, 2031))
    )
    if not stats["acceptance_passed"]:
        raise RuntimeError(f"初始化核验失败：{json.dumps(json_value(stats), ensure_ascii=False)}")
    return stats


def initialize(db, excel_path: Path | None = None, with_college2: bool = False) -> dict:
    if excel_path is not None:
        return _initialize_legacy(db, excel_path, with_college2)
    seed = seed_all(db, with_college2=with_college2, plan_definitions=PROJECT_SPECS)
    db.commit()
    school = db.get(SysUser, seed["users"]["school"])
    projects = {}
    for spec in PROJECT_SPECS:
        plan = db.get(ConstructionPlan, seed["plan_ids"][spec["plan_code"]])
        projects[spec["plan_code"]] = _initialize_project(db, plan=plan, school=school, spec=spec)
    totals = {
        key: sum(project[key] for project in projects.values())
        for key in ("direction_count", "indicator_count", "annual_target_count", "college_task_count", "source_rows", "newly_confirmed")
    }
    return {
        **totals,
        "plan_count": len(projects),
        "import_file_count": len(projects),
        "duplicate_skipped": all(project["duplicate_skipped"] for project in projects.values()),
        "acceptance_passed": all(project["acceptance_passed"] for project in projects.values()),
        "projects": projects,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--excel", type=Path)
    parser.add_argument("--with-college2", action="store_true")
    args = parser.parse_args()
    db = SessionLocal()
    try:
        stats = initialize(db, args.excel, with_college2=args.with_college2)
        print(json.dumps(json_value(stats), ensure_ascii=False, indent=2))
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


if __name__ == "__main__":
    main()
