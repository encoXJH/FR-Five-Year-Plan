from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


BACKEND_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=BACKEND_DIR / ".env", env_prefix="APP_", extra="ignore")

    database_url: str = f"sqlite:///{(BACKEND_DIR / 'platform.db').as_posix()}"
    jwt_secret: str = "local-development-only-change-me"
    jwt_algorithm: str = "HS256"
    jwt_expire_minutes: int = 480
    cookie_name: str = "five_year_session"
    cookie_secure: bool = False
    environment: str = "local"
    storage_dir: Path = BACKEND_DIR / "storage"
    libreoffice_path: str | None = None
    libreoffice_timeout_seconds: int = 60
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173"

    @property
    def cors_origin_list(self) -> list[str]:
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    settings.storage_dir.mkdir(parents=True, exist_ok=True)
    (settings.storage_dir / "imports").mkdir(exist_ok=True)
    (settings.storage_dir / "evidence").mkdir(exist_ok=True)
    (settings.storage_dir / "previews").mkdir(exist_ok=True)
    (settings.storage_dir / "transfer_attachments").mkdir(exist_ok=True)
    return settings
