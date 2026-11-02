-- 修复历史不一致：计划年度已激活且任务已下发，但任务年度仍停留在“未激活”
UPDATE task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0 AND t.is_historical = 0
JOIN plan_year py ON py.plan_id = t.plan_id AND py.year = ty.year AND py.deleted = 0
SET ty.year_status = 'IN_PROGRESS',
    ty.updated_at = NOW(3)
WHERE ty.year_status = 'NOT_ACTIVATED'
  AND ty.deleted = 0
  AND t.status IN ('DISTRIBUTED', 'IN_PROGRESS')
  AND py.status = 'ACTIVE';
