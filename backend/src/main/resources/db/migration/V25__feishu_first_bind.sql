-- =====================================================================
-- V25：飞书首次绑定一次性票据
-- 仅保存票据 SHA-256 与飞书身份；绑定前必须经校内教师接口核验。
-- =====================================================================

CREATE TABLE feishu_binding_ticket (
  id               BIGINT       NOT NULL                COMMENT '主键，雪花 ID',
  ticket_hash      CHAR(64)     NOT NULL                COMMENT '绑定票据 SHA-256，不保存原文',
  feishu_open_id   VARCHAR(64)  NOT NULL                COMMENT '飞书 open_id',
  feishu_union_id  VARCHAR(64)  NULL                    COMMENT '飞书 union_id',
  feishu_name      VARCHAR(128) NULL                    COMMENT '飞书返回的用户姓名',
  expires_at       DATETIME(3)  NOT NULL                COMMENT '过期时间',
  consumed_at      DATETIME(3)  NULL                    COMMENT '消费时间；只允许消费一次',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_feishu_binding_ticket_hash (ticket_hash),
  KEY idx_feishu_binding_ticket_expiry (expires_at, consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书首次绑定一次性票据';
