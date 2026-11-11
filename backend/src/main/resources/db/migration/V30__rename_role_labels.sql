-- 角色展示称谓统一：原 LEADER「校领导」改为「校级负责人」，原 SCHOOL_OWNER「校级项目负责人 / 校级负责人」改为「建设方向负责人」。
-- 仅修改展示文字和字段注释，不修改角色编码、权限、审批节点及只追加审计日志。

ALTER TABLE user_role
  MODIFY COLUMN role VARCHAR(32) NOT NULL COMMENT '角色：ADMIN 平台管理员 / LEADER 校级负责人 / SCHOOL_OWNER 建设方向负责人 / UNIT_OWNER 学院项目负责人';

ALTER TABLE indicator
  MODIFY COLUMN owner_id BIGINT NULL COMMENT '建设方向负责人，引用 sys_user.id；A+ 为空时沿用上级 A';

ALTER TABLE adjustment
  MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING_OWNER_REVIEW' COMMENT '调整状态：PENDING_OWNER_REVIEW 待初审 / PENDING_RECEIVER_CONFIRM 待接收方确认 / PENDING_LEADER_APPROVAL 待校级负责人审批 / APPROVED 已生效 / REJECTED 已驳回 / WITHDRAWN 已撤回';

ALTER TABLE approval_step
  MODIFY COLUMN node VARCHAR(32) NOT NULL COMMENT '审批环节：OWNER_REVIEW 建设方向负责人初审 / RECEIVER_CONFIRM 接收方确认 / LEADER_APPROVAL 校级负责人审批';

UPDATE sys_user
SET name = CASE username
    WHEN 'leader' THEN '校级负责人·陈明'
    WHEN 'owner1' THEN '建设方向负责人·林华'
    WHEN 'owner2' THEN '建设方向负责人·赵芸'
  END,
  version = version + 1,
  updated_at = NOW(3)
WHERE (username = 'leader' AND name = '校领导·陈明')
   OR (username = 'owner1' AND name = '校级负责人·林华')
   OR (username = 'owner2' AND name = '校级负责人·赵芸');

UPDATE notification
SET title = REPLACE(
    REPLACE(
      REPLACE(
        REPLACE(title, '校级项目负责人', '建设方向负责人'),
        '校级负责人', '建设方向负责人'),
      '校领导', '校级负责人'),
    '学校级负责人', '学校领导'),
  content = REPLACE(
    REPLACE(
      REPLACE(
        REPLACE(content, '校级项目负责人', '建设方向负责人'),
        '校级负责人', '建设方向负责人'),
      '校领导', '校级负责人'),
    '学校级负责人', '学校领导')
WHERE title LIKE '%校领导%'
   OR title LIKE '%校级项目负责人%'
   OR title LIKE '%校级负责人%'
   OR content LIKE '%校领导%'
   OR content LIKE '%校级项目负责人%'
   OR content LIKE '%校级负责人%';
