ALTER TABLE submission
  ADD COLUMN submitter_role VARCHAR(32) NULL COMMENT '本次提交来源：STAFF 干事 / UNIT_OWNER 学院项目负责人' AFTER status,
  ADD COLUMN current_review_node VARCHAR(32) NULL COMMENT '当前审核节点：UNIT_REVIEW 学院负责人审核 / LEADER_REVIEW 校级负责人审核；非待审为空' AFTER submitter_role;

ALTER TABLE review
  ADD COLUMN review_node VARCHAR(32) NULL COMMENT '审核节点：UNIT_REVIEW / LEADER_REVIEW；历史单级审核为空' AFTER revision_no;

UPDATE submission s
SET s.submitter_role = CASE
  WHEN s.submitted_by IS NOT NULL
    AND EXISTS (
      SELECT 1 FROM user_role staff_role
      WHERE staff_role.user_id = s.submitted_by AND staff_role.`role` = 'STAFF'
    )
    AND NOT EXISTS (
      SELECT 1 FROM user_role owner_role
      WHERE owner_role.user_id = s.submitted_by AND owner_role.`role` = 'UNIT_OWNER'
    )
  THEN 'STAFF'
  ELSE 'UNIT_OWNER'
END;

UPDATE submission s
SET s.current_review_node = CASE
  WHEN s.status = 'PENDING_REVIEW' AND s.submitter_role = 'STAFF' THEN 'UNIT_REVIEW'
  WHEN s.status = 'PENDING_REVIEW' THEN 'LEADER_REVIEW'
  ELSE NULL
END;

ALTER TABLE submission
  MODIFY COLUMN submitter_role VARCHAR(32) NOT NULL COMMENT '本次提交来源：STAFF 干事 / UNIT_OWNER 学院项目负责人',
  ADD CONSTRAINT chk_submission_submitter_role CHECK (submitter_role IN ('STAFF', 'UNIT_OWNER')),
  ADD CONSTRAINT chk_submission_current_review_node CHECK (
    current_review_node IS NULL OR current_review_node IN ('UNIT_REVIEW', 'LEADER_REVIEW')
  ),
  ADD CONSTRAINT chk_submission_review_node_status CHECK (
    (status = 'PENDING_REVIEW' AND current_review_node IS NOT NULL)
    OR (status <> 'PENDING_REVIEW' AND current_review_node IS NULL)
  ),
  ADD KEY idx_submission_review_node (status, current_review_node, deleted);

ALTER TABLE review
  ADD CONSTRAINT chk_review_review_node CHECK (
    review_node IS NULL OR review_node IN ('UNIT_REVIEW', 'LEADER_REVIEW')
  ),
  ADD UNIQUE KEY uk_review_submission_revision_node (submission_id, revision_no, review_node);

DELETE FROM role_permission
WHERE `role` = 'SCHOOL_OWNER' AND permission = 'submission:review';

INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 340000000000000001, 'UNIT_OWNER', 'submission:review', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission
  WHERE `role` = 'UNIT_OWNER' AND permission = 'submission:review'
);
