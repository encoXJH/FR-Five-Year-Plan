from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.api import adjustments, admin, auth, imports, planning, reporting, submissions, tasks
from app.config import get_settings
from app.errors import install_error_handlers
from app.responses import DecimalJSONResponse


app = FastAPI(title="五年建设任务管理平台 API", version="1.0.0", default_response_class=DecimalJSONResponse)
settings = get_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
install_error_handlers(app)

app.include_router(auth.router, prefix="/api/v1")
app.include_router(admin.router, prefix="/api/v1")
app.include_router(planning.router, prefix="/api/v1")
app.include_router(imports.router, prefix="/api/v1")
app.include_router(tasks.router, prefix="/api/v1")
app.include_router(submissions.router, prefix="/api/v1")
app.include_router(adjustments.router, prefix="/api/v1")
app.include_router(reporting.router, prefix="/api/v1")


@app.get("/api/health")
def health():
    return {"status": "ok", "service": "five-year-platform-backend"}


backend_dir = Path(__file__).resolve().parent.parent
frontend_candidates = [backend_dir / "static", backend_dir.parent / "frontend" / "dist"]
frontend_dir = next((path for path in frontend_candidates if (path / "index.html").is_file()), None)
if frontend_dir:
    if (frontend_dir / "assets").is_dir():
        app.mount("/assets", StaticFiles(directory=frontend_dir / "assets"), name="frontend-assets")

    @app.get("/{full_path:path}", include_in_schema=False)
    def serve_frontend(full_path: str):
        if full_path.startswith("api/"):
            raise HTTPException(status_code=404, detail="API endpoint not found")
        return FileResponse(frontend_dir / "index.html")
