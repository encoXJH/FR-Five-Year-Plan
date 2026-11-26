-- 指标预算：A 级总预算 + 填报支出随两级审核认定（金额单位：万元）

ALTER TABLE indicator
  ADD COLUMN total_budget DECIMAL(20,2) NULL COMMENT '指标总预算（万元），NULL 表示未设置' AFTER total_target;

ALTER TABLE submission
  ADD COLUMN expense_amount DECIMAL(20,2) NULL COMMENT '本次填报支出金额（万元）' AFTER achieved,
  ADD COLUMN approved_expense_amount DECIMAL(20,2) NULL COMMENT '审核认定支出金额（万元）' AFTER approved_achieved;

ALTER TABLE submission_history
  ADD COLUMN expense_amount DECIMAL(20,2) NULL COMMENT '提交时支出金额快照（万元）' AFTER file_ids_json,
  ADD COLUMN budget_file_ids_json JSON NULL COMMENT '提交时预算佐证文件 id 数组' AFTER expense_amount;

ALTER TABLE review
  ADD COLUMN expense_amount DECIMAL(20,2) NULL COMMENT '审核时填报支出快照（万元）' AFTER approved_value_json,
  ADD COLUMN approved_expense_amount DECIMAL(20,2) NULL COMMENT '本级认定支出（万元）' AFTER expense_amount;

ALTER TABLE file_info DROP CHECK chk_file_biz_type;
ALTER TABLE file_info
  ADD CONSTRAINT chk_file_biz_type CHECK (biz_type IN (
    'SUBMISSION_ATTACHMENT', 'ADJUSTMENT_ATTACHMENT', 'MILESTONE_ATTACHMENT',
    'INDICATOR_ATTACHMENT', 'IMPORT_FILE', 'TRAINING_ATTACHMENT', 'BUDGET_ATTACHMENT'
  ));

ALTER TABLE file_ref DROP CHECK chk_file_ref_biz;
ALTER TABLE file_ref
  ADD CONSTRAINT chk_file_ref_biz CHECK (biz_type IN (
    'SUBMISSION', 'ADJUSTMENT', 'MILESTONE', 'INDICATOR', 'TRAINING_PROJECT', 'SUBMISSION_BUDGET'
  ));

ALTER TABLE role_permission
  DROP CHECK chk_role_permission_code,
  ADD CONSTRAINT chk_role_permission_code CHECK (permission IN (
    'plan:manage', 'indicator:edit', 'indicator:budget', 'allocation:edit', 'task:distribute', 'task:accept',
    'submission:submit', 'submission:review', 'adjustment:apply', 'adjustment:decide',
    'settlement:manage', 'screen:view', 'stats:view', 'audit-log:view', 'audit-log:export',
    'system:manage', 'user:create', 'material:view', 'material:upload', 'training:fill'));

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 460000000000000001, 'ADMIN', 'indicator:budget', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'ADMIN' AND permission = 'indicator:budget'
);

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 460000000000000002, 'LEADER', 'indicator:budget', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'LEADER' AND permission = 'indicator:budget'
);

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 460000000000000003, 'SCHOOL_OWNER', 'indicator:budget', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission WHERE `role` = 'SCHOOL_OWNER' AND permission = 'indicator:budget'
);
