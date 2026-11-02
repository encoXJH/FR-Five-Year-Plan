-- 修正比率类目标口径：历史导入把“70（%）”直接落库为 70，前端按小数展示成 7000%。
-- 占比 / 直填百分比的合法小数取值不超过 1，大于 1 的一律按整数百分数换算。
UPDATE indicator
SET total_target = total_target / 100,
    version = version + 1,
    updated_at = NOW(3)
WHERE deleted = 0
  AND stat_caliber IN ('RATIO', 'DIRECT_PERCENT')
  AND total_target > 1;

UPDATE indicator
SET baseline_value = baseline_value / 100,
    version = version + 1,
    updated_at = NOW(3)
WHERE deleted = 0
  AND stat_caliber IN ('RATIO', 'DIRECT_PERCENT')
  AND baseline_value > 1;

UPDATE indicator_year_target iyt
JOIN indicator i ON i.id = iyt.indicator_id AND i.deleted = 0
SET iyt.target = iyt.target / 100,
    iyt.version = iyt.version + 1,
    iyt.updated_at = NOW(3)
WHERE iyt.deleted = 0
  AND i.stat_caliber IN ('RATIO', 'DIRECT_PERCENT')
  AND iyt.target > 1;

UPDATE task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0
JOIN indicator i ON i.id = t.indicator_id AND i.deleted = 0
SET ty.target = ty.target / 100,
    ty.version = ty.version + 1,
    ty.updated_at = NOW(3)
WHERE ty.deleted = 0
  AND i.stat_caliber IN ('RATIO', 'DIRECT_PERCENT')
  AND ty.target > 1;

UPDATE task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0
JOIN indicator i ON i.id = t.indicator_id AND i.deleted = 0
SET ty.original_target = ty.original_target / 100,
    ty.version = ty.version + 1,
    ty.updated_at = NOW(3)
WHERE ty.deleted = 0
  AND i.stat_caliber IN ('RATIO', 'DIRECT_PERCENT')
  AND ty.original_target > 1;
