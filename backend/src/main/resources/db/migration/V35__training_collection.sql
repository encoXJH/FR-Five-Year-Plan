ALTER TABLE plan
  ADD COLUMN plan_type VARCHAR(32) NOT NULL DEFAULT 'INDICATOR' COMMENT '计划类型：INDICATOR 指标计划 / TRAINING 职业培训信息采集' AFTER name,
  ADD CONSTRAINT chk_plan_plan_type CHECK (plan_type IN ('INDICATOR', 'TRAINING'));

ALTER TABLE role_permission
  DROP CHECK chk_role_permission_code,
  ADD CONSTRAINT chk_role_permission_code CHECK (permission IN (
    'plan:manage', 'indicator:edit', 'allocation:edit', 'task:distribute', 'task:accept',
    'submission:submit', 'submission:review', 'adjustment:apply', 'adjustment:decide',
    'settlement:manage', 'screen:view', 'stats:view', 'audit-log:view', 'audit-log:export',
    'system:manage', 'user:create', 'material:view', 'material:upload', 'training:fill'));

CREATE TABLE training_sheet (
  id           BIGINT       NOT NULL COMMENT '主键，雪花 ID',
  plan_id      BIGINT       NOT NULL COMMENT '培训计划，引用 plan.id',
  unit_id      BIGINT       NOT NULL COMMENT '填报执行单元，引用 org_unit.id',
  owner_id     BIGINT       NOT NULL COMMENT '填报负责人，引用 sys_user.id',
  status       VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT 草稿 / SUBMITTED 已提交',
  submitted_at DATETIME(3)  NULL COMMENT '提交时间',
  submitted_by BIGINT       NULL COMMENT '提交人，引用 sys_user.id',
  version      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by   BIGINT       NULL COMMENT '创建人，引用 sys_user.id',
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by   BIGINT       NULL COMMENT '更新人，引用 sys_user.id',
  deleted      BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_training_sheet_plan (plan_id, deleted),
  KEY idx_training_sheet_unit_owner (unit_id, owner_id, status, deleted),
  KEY idx_training_sheet_owner (owner_id, deleted),
  CONSTRAINT chk_training_sheet_status CHECK (status IN ('DRAFT', 'SUBMITTED')),
  CONSTRAINT chk_training_sheet_submission CHECK (
    (status = 'SUBMITTED' AND submitted_at IS NOT NULL AND submitted_by IS NOT NULL)
    OR (status = 'DRAFT' AND submitted_at IS NULL AND submitted_by IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='职业培训信息采集表';

CREATE TABLE training_project (
  id         BIGINT      NOT NULL COMMENT '主键，雪花 ID',
  sheet_id   BIGINT      NOT NULL COMMENT '培训采集表，引用 training_sheet.id',
  row_no     INT         NOT NULL COMMENT '项目行号，从 1 开始',
  data_json  JSON        NOT NULL COMMENT '培训项目 57 列数据，键为 c01-c57',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by BIGINT      NULL COMMENT '创建人，引用 sys_user.id',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by BIGINT      NULL COMMENT '更新人，引用 sys_user.id',
  deleted    BIGINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_training_project_sheet_row (sheet_id, row_no, deleted),
  KEY idx_training_project_sheet (sheet_id, deleted, row_no),
  CONSTRAINT chk_training_project_row_no CHECK (row_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='职业培训信息项目';

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 350000000000000001, 'ADMIN', 'training:fill', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'ADMIN' AND permission = 'training:fill'
);

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 350000000000000002, 'UNIT_OWNER', 'training:fill', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'UNIT_OWNER' AND permission = 'training:fill'
);
