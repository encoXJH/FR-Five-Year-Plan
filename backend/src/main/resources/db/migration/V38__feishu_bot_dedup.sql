-- 双高管理平台机器人：通知业务去重、校级审核汇总类型与管理端日志查询索引
ALTER TABLE notification
  ADD COLUMN dedup_key VARCHAR(160) NULL COMMENT '业务通知去重键；空表示历史或非幂等通知' AFTER feishu_last_error,
  ADD UNIQUE KEY uk_notification_dedup_key (dedup_key),
  ADD KEY idx_notification_feishu_created (feishu_status, created_at);

ALTER TABLE notification DROP CHECK chk_notification_type;

ALTER TABLE notification
  ADD CONSTRAINT chk_notification_type CHECK (type IN ('TASK_DISTRIBUTED','DEADLINE_REMINDER','REVIEW_RESULT','REVIEW_TODO','ADJUSTMENT_TODO','ADJUSTMENT_RESULT','WARNING'));
