CREATE TABLE user_unit (
  id         BIGINT      NOT NULL COMMENT '主键，雪花 ID',
  user_id    BIGINT      NOT NULL COMMENT '用户，引用 sys_user.id',
  unit_id    BIGINT      NOT NULL COMMENT '执行单元，引用 org_unit.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by BIGINT      NULL COMMENT '创建人，引用 sys_user.id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_unit (user_id, unit_id),
  KEY idx_user_unit_unit_user (unit_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户执行单元关联';

INSERT INTO user_unit (id, user_id, unit_id, created_at, created_by)
SELECT id, id, unit_id, CURRENT_TIMESTAMP(3), created_by
FROM sys_user
WHERE deleted = 0 AND unit_id IS NOT NULL;
