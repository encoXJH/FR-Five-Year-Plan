CREATE TABLE role_permission (
  id          BIGINT       NOT NULL COMMENT '主键，雪花 ID',
  `role`      VARCHAR(32)  NOT NULL COMMENT '固定角色编码',
  permission  VARCHAR(64)  NOT NULL COMMENT '功能权限码',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by  BIGINT       NULL COMMENT '创建人，引用 sys_user.id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_permission (`role`, permission),
  KEY idx_role_permission_permission (permission, `role`),
  CONSTRAINT chk_role_permission_role CHECK (`role` IN ('ADMIN', 'LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER')),
  CONSTRAINT chk_role_permission_code CHECK (permission IN (
    'plan:manage', 'indicator:edit', 'allocation:edit', 'task:distribute', 'task:accept',
    'submission:submit', 'submission:review', 'adjustment:apply', 'adjustment:decide',
    'settlement:manage', 'screen:view', 'stats:view', 'audit-log:view', 'audit-log:export',
    'system:manage', 'material:view', 'material:upload'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='固定角色功能权限配置';

CREATE TABLE role_permission_version (
  `role`      VARCHAR(32)  NOT NULL COMMENT '固定角色编码',
  version     INT          NOT NULL DEFAULT 0 COMMENT '角色权限矩阵乐观锁版本号',
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by  BIGINT       NULL COMMENT '更新人，引用 sys_user.id',
  PRIMARY KEY (`role`),
  CONSTRAINT chk_role_permission_version_role CHECK (`role` IN ('ADMIN', 'LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限矩阵版本';

INSERT INTO role_permission_version (`role`, version, updated_by) VALUES
  ('ADMIN', 0, NULL),
  ('LEADER', 0, NULL),
  ('SCHOOL_OWNER', 0, NULL),
  ('UNIT_OWNER', 0, NULL);

INSERT INTO role_permission (id, `role`, permission, created_by) VALUES
  (310000000000000001, 'ADMIN', 'plan:manage', NULL),
  (310000000000000002, 'ADMIN', 'indicator:edit', NULL),
  (310000000000000003, 'ADMIN', 'allocation:edit', NULL),
  (310000000000000004, 'ADMIN', 'task:distribute', NULL),
  (310000000000000005, 'ADMIN', 'task:accept', NULL),
  (310000000000000006, 'ADMIN', 'submission:submit', NULL),
  (310000000000000007, 'ADMIN', 'submission:review', NULL),
  (310000000000000008, 'ADMIN', 'adjustment:apply', NULL),
  (310000000000000009, 'ADMIN', 'adjustment:decide', NULL),
  (310000000000000010, 'ADMIN', 'settlement:manage', NULL),
  (310000000000000011, 'ADMIN', 'screen:view', NULL),
  (310000000000000012, 'ADMIN', 'stats:view', NULL),
  (310000000000000013, 'ADMIN', 'audit-log:view', NULL),
  (310000000000000014, 'ADMIN', 'audit-log:export', NULL),
  (310000000000000015, 'ADMIN', 'system:manage', NULL),
  (310000000000000016, 'ADMIN', 'material:view', NULL),
  (310000000000000017, 'ADMIN', 'material:upload', NULL),
  (310000000000000018, 'LEADER', 'adjustment:decide', NULL),
  (310000000000000019, 'LEADER', 'screen:view', NULL),
  (310000000000000020, 'LEADER', 'stats:view', NULL),
  (310000000000000021, 'LEADER', 'audit-log:view', NULL),
  (310000000000000022, 'LEADER', 'material:view', NULL),
  (310000000000000023, 'SCHOOL_OWNER', 'indicator:edit', NULL),
  (310000000000000024, 'SCHOOL_OWNER', 'allocation:edit', NULL),
  (310000000000000025, 'SCHOOL_OWNER', 'task:distribute', NULL),
  (310000000000000026, 'SCHOOL_OWNER', 'submission:review', NULL),
  (310000000000000027, 'SCHOOL_OWNER', 'adjustment:apply', NULL),
  (310000000000000028, 'SCHOOL_OWNER', 'adjustment:decide', NULL),
  (310000000000000029, 'SCHOOL_OWNER', 'stats:view', NULL),
  (310000000000000030, 'SCHOOL_OWNER', 'material:view', NULL),
  (310000000000000031, 'SCHOOL_OWNER', 'material:upload', NULL),
  (310000000000000032, 'UNIT_OWNER', 'submission:submit', NULL),
  (310000000000000033, 'UNIT_OWNER', 'adjustment:decide', NULL),
  (310000000000000034, 'UNIT_OWNER', 'stats:view', NULL),
  (310000000000000035, 'UNIT_OWNER', 'material:view', NULL),
  (310000000000000036, 'UNIT_OWNER', 'material:upload', NULL);
