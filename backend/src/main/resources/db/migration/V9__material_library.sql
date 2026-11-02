-- 材料库：支持直接绑定关键产出指标，并保留分片上传用途
ALTER TABLE file_info DROP CHECK chk_file_biz_type;
ALTER TABLE file_info
    ADD CONSTRAINT chk_file_biz_type CHECK (biz_type IN (
        'SUBMISSION_ATTACHMENT', 'ADJUSTMENT_ATTACHMENT', 'MILESTONE_ATTACHMENT',
        'INDICATOR_ATTACHMENT', 'IMPORT_FILE'
    ));

ALTER TABLE file_ref DROP CHECK chk_file_ref_biz;
ALTER TABLE file_ref
    ADD CONSTRAINT chk_file_ref_biz CHECK (biz_type IN (
        'SUBMISSION', 'ADJUSTMENT', 'MILESTONE', 'INDICATOR'
    ));

ALTER TABLE file_upload_session
    ADD COLUMN biz_type VARCHAR(32) NOT NULL DEFAULT 'SUBMISSION_ATTACHMENT' COMMENT '文件用途' AFTER mime_type;
