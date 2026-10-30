import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.config import get_settings
from app.database import Base, get_db
from app.main import app as fastapi_app
import app.models  # noqa: F401
from scripts.seed import seed_all


@pytest.fixture
def client_db(tmp_path):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)

    @event.listens_for(engine, "connect")
    def foreign_keys_on(connection, _record):
        cursor = connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.close()

    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, class_=Session, expire_on_commit=False)
    db = factory()
    seed_all(db, with_college2=True)
    db.commit()
    settings = get_settings()
    old_storage = settings.storage_dir
    old_libreoffice = settings.libreoffice_path
    settings.storage_dir = tmp_path / "storage"
    for name in ["imports", "evidence", "previews", "transfer_attachments"]:
        (settings.storage_dir / name).mkdir(parents=True, exist_ok=True)
    settings.libreoffice_path = None

    def override_db():
        yield db

    fastapi_app.dependency_overrides[get_db] = override_db
    with TestClient(fastapi_app) as client:
        yield client, db
    fastapi_app.dependency_overrides.clear()
    db.close()
    engine.dispose()
    settings.storage_dir = old_storage
    settings.libreoffice_path = old_libreoffice
