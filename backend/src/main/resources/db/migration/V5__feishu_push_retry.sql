-- 阶段 8：飞书推送指数退避（下次重试时间 + 最近一次失败原因）；审计动作补 SYNC_ORG（组织同步）
-- 不改 V1–V4，按约定走 V5+ 迁移
ALTER TABLE notification
  ADD COLUMN feishu_next_retry_at DATETIME(3) NULL COMMENT '飞书推送下次重试时间（指数退避；空表示立即可推送）' AFTER feishu_retry_count,
  ADD COLUMN feishu_last_error   VARCHAR(500) NULL COMMENT '飞书推送最近一次失败原因' AFTER feishu_next_retry_at;

ALTER TABLE audit_log DROP CHECK chk_audit_action;

ALTER TABLE audit_log
  ADD CONSTRAINT chk_audit_action CHECK (action IN ('LOGIN','CREATE','UPDATE','DELETE','IMPORT','EXPORT','FINALIZE','DISTRIBUTE','ACCEPT','SUBMIT','WITHDRAW','REVIEW','ADJUST_APPLY','ADJUST_DECIDE','SETTLE','UNLOCK','PERMISSION_CHANGE','FILE_DOWNLOAD','FILE_PREVIEW','SYNC_ORG'));
