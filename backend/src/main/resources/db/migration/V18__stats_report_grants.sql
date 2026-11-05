-- 统计报表按执行单元下发配置
CREATE TABLE stats_report_grant (
  id         BIGINT      NOT NULL COMMENT '主键，雪花 ID',
  plan_id    BIGINT      NOT NULL COMMENT '计划，引用 plan.id',
  unit_ids   JSON        NOT NULL COMMENT '获授权执行单元 ID（字符串数组）',
  version    INT         NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by BIGINT      NULL COMMENT '创建人，引用 sys_user.id',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by BIGINT      NULL COMMENT '更新人，引用 sys_user.id',
  deleted    BIGINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stats_report_grant_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统计报表下发配置';
