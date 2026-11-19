ALTER TABLE user_role
  DROP CHECK chk_user_role_role,
  ADD CONSTRAINT chk_user_role_role CHECK (role IN ('ADMIN', 'LEADER', 'SCHOOL_OWNER', 'UNIT_OWNER', 'STAFF')),
  MODIFY COLUMN role VARCHAR(32) NOT NULL COMMENT '角色：ADMIN 平台管理员 / LEADER 校级负责人 / SCHOOL_OWNER 建设方向负责人 / UNIT_OWNER 学院项目负责人 / STAFF 干事';

ALTER TABLE role_permission
  DROP CHECK chk_role_permission_code,
  ADD CONSTRAINT chk_role_permission_code CHECK (permission IN (
    'plan:manage', 'indicator:edit', 'allocation:edit', 'task:distribute', 'task:accept',
    'submission:submit', 'submission:review', 'adjustment:apply', 'adjustment:decide',
    'settlement:manage', 'screen:view', 'stats:view', 'audit-log:view', 'audit-log:export',
    'system:manage', 'user:create', 'material:view', 'material:upload'));

ALTER TABLE sys_user
  ADD COLUMN owner_user_id BIGINT NULL COMMENT '干事归属的学院负责人，仅 STAFF 使用' AFTER unit_id,
  ADD KEY idx_sys_user_owner_user (owner_user_id);

CREATE TABLE task_staff (
  id          BIGINT      NOT NULL COMMENT '主键，雪花 ID',
  task_id     BIGINT      NOT NULL COMMENT '学院任务，引用 task.id',
  staff_id    BIGINT      NOT NULL COMMENT '干事用户，引用 sys_user.id',
  assigned_by BIGINT      NOT NULL COMMENT '分配人，引用 sys_user.id',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '分配时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_staff (task_id, staff_id),
  KEY idx_task_staff_staff_task (staff_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学院任务干事分配';

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 330000000000000001, 'ADMIN', 'user:create', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'ADMIN' AND permission = 'user:create'
);

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 330000000000000002, 'LEADER', 'user:create', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'LEADER' AND permission = 'user:create'
);

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 330000000000000003, 'SCHOOL_OWNER', 'user:create', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'SCHOOL_OWNER' AND permission = 'user:create'
);

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 330000000000000004, 'UNIT_OWNER', 'user:create', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'UNIT_OWNER' AND permission = 'user:create'
);
