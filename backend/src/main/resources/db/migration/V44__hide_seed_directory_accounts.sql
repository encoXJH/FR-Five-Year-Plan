ALTER TABLE sys_user
  ADD COLUMN directory_visible TINYINT(1) NOT NULL DEFAULT 1
  COMMENT '是否在人员目录和人员选项中对非管理员可见' AFTER is_enabled;

UPDATE sys_user
SET directory_visible = 0
WHERE username IN (
  'admin', 'leader',
  'c01', 'c02', 'c03', 'c04', 'c05',
  'c06', 'c07', 'c08', 'c09', 'c10',
  's01'
);
