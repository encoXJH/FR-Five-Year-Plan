UPDATE plan_year
SET status = 'ACTIVE',
    activated_at = COALESCE(activated_at, NOW(3))
WHERE status = 'NOT_ACTIVATED'
  AND deleted = 0;

UPDATE task_year ty
JOIN task t ON t.id = ty.task_id
SET ty.year_status = 'IN_PROGRESS'
WHERE ty.year_status = 'NOT_ACTIVATED'
  AND ty.deleted = 0
  AND t.deleted = 0
  AND t.is_historical = 0
  AND t.status IN ('DISTRIBUTED', 'IN_PROGRESS');
