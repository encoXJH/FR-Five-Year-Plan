ALTER TABLE training_sheet
  ADD COLUMN submitter_role VARCHAR(32) NULL COMMENT '最近提交来源：STAFF 干事 / UNIT_OWNER 学院项目负责人' AFTER status,
  ADD COLUMN current_review_node VARCHAR(32) NULL COMMENT '待审项目节点一致时为 UNIT_REVIEW / LEADER_REVIEW；混合节点或非待审为空' AFTER submitter_role,
  ADD COLUMN revision_no INT NOT NULL DEFAULT 0 COMMENT '项目最大提交轮次；初始为 0' AFTER current_review_node,
  DROP CHECK chk_training_sheet_status,
  DROP CHECK chk_training_sheet_submission;

UPDATE training_sheet
SET status = 'PENDING_REVIEW',
    submitter_role = 'UNIT_OWNER',
    current_review_node = 'LEADER_REVIEW',
    revision_no = 1
WHERE status = 'SUBMITTED';

ALTER TABLE training_project
  ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '项目状态：DRAFT / PENDING_REVIEW / APPROVED / REJECTED' AFTER data_json,
  ADD COLUMN submitter_role VARCHAR(32) NULL COMMENT '本次提交来源：STAFF / UNIT_OWNER' AFTER status,
  ADD COLUMN current_review_node VARCHAR(32) NULL COMMENT '当前审核节点：UNIT_REVIEW / LEADER_REVIEW；非待审为空' AFTER submitter_role,
  ADD COLUMN revision_no INT NOT NULL DEFAULT 0 COMMENT '提交轮次，从 1 开始；草稿为 0' AFTER current_review_node,
  ADD COLUMN submitted_at DATETIME(3) NULL COMMENT '最近提交时间' AFTER revision_no,
  ADD COLUMN submitted_by BIGINT NULL COMMENT '最近提交人，引用 sys_user.id' AFTER submitted_at;

UPDATE training_project p
JOIN training_sheet s ON s.id = p.sheet_id
SET p.status = s.status,
    p.submitter_role = s.submitter_role,
    p.current_review_node = s.current_review_node,
    p.revision_no = s.revision_no,
    p.submitted_at = s.submitted_at,
    p.submitted_by = s.submitted_by;

UPDATE plan p
JOIN training_sheet s ON s.plan_id = p.id AND s.deleted = 0
SET p.status = 'DRAFT'
WHERE p.plan_type = 'TRAINING' AND p.status = 'ACTIVE' AND s.status = 'PENDING_REVIEW';

ALTER TABLE training_sheet
  MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '汇总状态：DRAFT / PENDING_REVIEW / APPROVED / REJECTED',
  ADD CONSTRAINT chk_training_sheet_status CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
  ADD CONSTRAINT chk_training_sheet_submitter_role CHECK (
    submitter_role IS NULL OR submitter_role IN ('STAFF', 'UNIT_OWNER')
  ),
  ADD CONSTRAINT chk_training_sheet_review_node CHECK (
    current_review_node IS NULL OR current_review_node IN ('UNIT_REVIEW', 'LEADER_REVIEW')
  ),
  ADD CONSTRAINT chk_training_sheet_revision_no CHECK (revision_no >= 0),
  ADD CONSTRAINT chk_training_sheet_submission CHECK (
    (status = 'PENDING_REVIEW' AND submitted_at IS NOT NULL AND submitted_by IS NOT NULL)
    OR (status <> 'PENDING_REVIEW' AND current_review_node IS NULL)
  ),
  ADD KEY idx_training_sheet_review (status, current_review_node, unit_id, deleted, submitted_at);

ALTER TABLE training_project
  ADD CONSTRAINT chk_training_project_status CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
  ADD CONSTRAINT chk_training_project_submitter_role CHECK (
    submitter_role IS NULL OR submitter_role IN ('STAFF', 'UNIT_OWNER')
  ),
  ADD CONSTRAINT chk_training_project_review_node CHECK (
    current_review_node IS NULL OR current_review_node IN ('UNIT_REVIEW', 'LEADER_REVIEW')
  ),
  ADD CONSTRAINT chk_training_project_revision_no CHECK (revision_no >= 0),
  ADD CONSTRAINT chk_training_project_submission CHECK (
    (status = 'PENDING_REVIEW' AND current_review_node IS NOT NULL
      AND submitter_role IS NOT NULL AND submitted_at IS NOT NULL AND submitted_by IS NOT NULL)
    OR (status <> 'PENDING_REVIEW' AND current_review_node IS NULL)
  ),
  ADD KEY idx_training_project_review (sheet_id, status, current_review_node, deleted, submitted_at);

CREATE TABLE training_review (
  id           BIGINT       NOT NULL COMMENT '主键，雪花 ID',
  sheet_id     BIGINT       NOT NULL COMMENT '培训采集表，引用 training_sheet.id',
  project_id   BIGINT       NOT NULL COMMENT '培训项目，引用 training_project.id',
  revision_no  INT          NOT NULL COMMENT '提交轮次',
  review_node  VARCHAR(32)  NOT NULL COMMENT '审核节点：UNIT_REVIEW / LEADER_REVIEW',
  result       VARCHAR(32)  NOT NULL COMMENT '审核结果：APPROVED / REJECTED',
  comment      VARCHAR(1000) NULL COMMENT '审核意见；驳回必填',
  reviewer_id  BIGINT       NOT NULL COMMENT '审核人，引用 sys_user.id',
  is_proxy     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否管理员代审',
  proxy_reason VARCHAR(500) NULL COMMENT '管理员代审原因',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审核时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_training_review_project_revision_node (project_id, revision_no, review_node),
  KEY idx_training_review_sheet (sheet_id, project_id, revision_no, created_at),
  CONSTRAINT chk_training_review_node CHECK (review_node IN ('UNIT_REVIEW', 'LEADER_REVIEW')),
  CONSTRAINT chk_training_review_result CHECK (result IN ('APPROVED', 'REJECTED')),
  CONSTRAINT chk_training_review_comment CHECK (result <> 'REJECTED' OR comment IS NOT NULL AND CHAR_LENGTH(TRIM(comment)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='职业培训信息项目审核记录';
