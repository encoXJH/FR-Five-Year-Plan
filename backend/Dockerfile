FROM node:22-bookworm-slim AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app/backend
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-core libreoffice-writer libreoffice-calc libreoffice-impress \
    && rm -rf /var/lib/apt/lists/*
COPY backend/pyproject.toml ./
COPY backend/app ./app
COPY backend/alembic ./alembic
COPY backend/alembic.ini ./
COPY backend/scripts ./scripts
RUN python -m pip install --no-cache-dir .
COPY --from=frontend-build /app/frontend/dist ./static
ENV APP_ENVIRONMENT=production \
    APP_DATABASE_URL=sqlite:////data/platform.db \
    APP_STORAGE_DIR=/data/storage \
    APP_LIBREOFFICE_PATH=/usr/bin/soffice
VOLUME ["/data"]
EXPOSE 8000
CMD ["sh", "-c", "python -m alembic upgrade head && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000"]
