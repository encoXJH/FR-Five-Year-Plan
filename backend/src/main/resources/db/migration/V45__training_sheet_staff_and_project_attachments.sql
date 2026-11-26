CREATE TABLE training_sheet_staff (
  id          BIGINT      NOT NULL COMMENT '主键，雪花 ID',
  sheet_id    BIGINT      NOT NULL COMMENT '培训采集表，引用 training_sheet.id',
  staff_id    BIGINT      NOT NULL COMMENT '干事用户，引用 sys_user.id',
  assigned_by BIGINT      NOT NULL COMMENT '分配人，引用 sys_user.id',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '分配时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_training_sheet_staff (sheet_id, staff_id),
  KEY idx_training_sheet_staff_staff_sheet (staff_id, sheet_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='职业培训采集表干事分配';

ALTER TABLE file_info DROP CHECK chk_file_biz_type;
ALTER TABLE file_info
  ADD CONSTRAINT chk_file_biz_type CHECK (biz_type IN (
    'SUBMISSION_ATTACHMENT', 'ADJUSTMENT_ATTACHMENT', 'MILESTONE_ATTACHMENT',
    'INDICATOR_ATTACHMENT', 'IMPORT_FILE', 'TRAINING_ATTACHMENT'
  ));

ALTER TABLE file_ref DROP CHECK chk_file_ref_biz;
ALTER TABLE file_ref
  ADD CONSTRAINT chk_file_ref_biz CHECK (biz_type IN (
    'SUBMISSION', 'ADJUSTMENT', 'MILESTONE', 'INDICATOR', 'TRAINING_PROJECT'
  ));
