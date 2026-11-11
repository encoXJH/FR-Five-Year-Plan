-- 修复两张源表全量替换时的专用解析错误：百分比指标误取“在校生数*比例=人数”中等号后的人数。
-- 仅匹配专业群计划 2026 年仍为错误原值的 9 条记录；人数型任务及正式数字 Excel 导入不受影响。
INSERT INTO audit_log (
  id, occurred_at, operator_id, operator_name, roles, unit_id, plan_id,
  action, resource_type, resource_id, resource_code, before_json, after_json,
  stakeholder_ids, reason, request_id
)
SELECT
  2311112800000000000 + ROW_NUMBER() OVER (ORDER BY i.code, t.unit_id),
  NOW(3), 201, '平台管理员', JSON_ARRAY('ADMIN'), NULL, p.id,
  'UPDATE', 'task_year', ty.id, t.code,
  JSON_OBJECT('year', ty.year, 'target', ty.target, 'originalTarget', ty.original_target),
  JSON_OBJECT('year', ty.year, 'target', c.new_target, 'originalTarget', c.new_target),
  JSON_ARRAY(t.owner_id),
  '源表公式解析修复：百分比目标误取等号后人数，按源表恢复学院百分比目标',
  'flyway-v28-formula-percent-targets'
FROM task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0
JOIN indicator i ON i.id = t.indicator_id AND i.deleted = 0
JOIN plan p ON p.id = t.plan_id AND p.deleted = 0
JOIN (
  SELECT 'B01' AS code, 104 AS unit_id, 95 AS old_target, 0.13 AS new_target
  UNION ALL SELECT 'B01', 107, 98, 0.13
  UNION ALL SELECT 'B01', 108, 269, 0.13
  UNION ALL SELECT 'H02', 104, 146, 0.20
  UNION ALL SELECT 'H02', 107, 150, 0.20
  UNION ALL SELECT 'H02', 108, 414, 0.20
  UNION ALL SELECT 'H03', 104, 95, 0.13
  UNION ALL SELECT 'H03', 107, 98, 0.13
  UNION ALL SELECT 'H03', 108, 269, 0.13
) c ON c.code = i.code AND c.unit_id = t.unit_id
WHERE p.id = 2311110000000000001
  AND ty.deleted = 0
  AND ty.year = 2026
  AND i.stat_caliber = 'DIRECT_PERCENT'
  AND ty.target = c.old_target
  AND ty.original_target = c.old_target;

UPDATE task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0
JOIN indicator i ON i.id = t.indicator_id AND i.deleted = 0
JOIN plan p ON p.id = t.plan_id AND p.deleted = 0
JOIN (
  SELECT 'B01' AS code, 104 AS unit_id, 95 AS old_target, 0.13 AS new_target
  UNION ALL SELECT 'B01', 107, 98, 0.13
  UNION ALL SELECT 'B01', 108, 269, 0.13
  UNION ALL SELECT 'H02', 104, 146, 0.20
  UNION ALL SELECT 'H02', 107, 150, 0.20
  UNION ALL SELECT 'H02', 108, 414, 0.20
  UNION ALL SELECT 'H03', 104, 95, 0.13
  UNION ALL SELECT 'H03', 107, 98, 0.13
  UNION ALL SELECT 'H03', 108, 269, 0.13
) c ON c.code = i.code AND c.unit_id = t.unit_id
SET ty.target = c.new_target,
    ty.original_target = c.new_target,
    ty.version = ty.version + 1,
    ty.updated_at = NOW(3),
    ty.updated_by = 201
WHERE p.id = 2311110000000000001
  AND ty.deleted = 0
  AND ty.year = 2026
  AND i.stat_caliber = 'DIRECT_PERCENT'
  AND ty.target = c.old_target
  AND ty.original_target = c.old_target;
