INSERT INTO role_permission (id, `role`, permission, created_by)
SELECT 430000000000000001, 'LEADER', 'system:manage', NULL
WHERE NOT EXISTS (
  SELECT 1 FROM role_permission
  WHERE `role` = 'LEADER' AND permission = 'system:manage'
);
