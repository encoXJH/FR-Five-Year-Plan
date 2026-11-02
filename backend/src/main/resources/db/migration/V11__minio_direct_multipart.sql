-- MINIO 浏览器分片直传；既有会话保持 LOCAL 后端分片行为
ALTER TABLE file_upload_session
    ADD COLUMN storage_type VARCHAR(32) NOT NULL DEFAULT 'LOCAL' COMMENT '存储类型：LOCAL 后端分片 / MINIO 浏览器直传' AFTER sha256,
    ADD COLUMN storage_key VARCHAR(512) NULL COMMENT 'MINIO 对象键' AFTER storage_type,
    ADD COLUMN remote_upload_id VARCHAR(128) NULL COMMENT 'MINIO S3 multipart uploadId' AFTER storage_key,
    ADD CONSTRAINT chk_upload_session_storage CHECK (storage_type IN ('LOCAL', 'MINIO'));
