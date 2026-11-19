INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 320000000000000001, 'LEADER', 'submission:review', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission
  WHERE `role` = 'LEADER' AND permission = 'submission:review'
);
