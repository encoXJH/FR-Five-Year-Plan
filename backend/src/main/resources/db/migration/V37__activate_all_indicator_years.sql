UPDATE plan
SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP(3)
WHERE plan_type = 'INDICATOR' AND status = 'DRAFT' AND deleted = 0;

UPDATE plan_year py
JOIN plan p ON p.id = py.plan_id AND p.plan_type = 'INDICATOR' AND p.deleted = 0
SET py.status = 'ACTIVE',
    py.activated_at = COALESCE(py.activated_at, CURRENT_TIMESTAMP(3)),
    py.activated_by = COALESCE(py.activated_by, py.created_by),
    py.updated_at = CURRENT_TIMESTAMP(3)
WHERE py.status = 'NOT_ACTIVATED' AND py.deleted = 0;

INSERT INTO plan_year (
  id, plan_id, year, status, activated_at, activated_by,
  created_at, created_by, updated_at, updated_by, deleted
)
SELECT UUID_SHORT(), p.id, p.start_year + n.offset_value, 'ACTIVE', CURRENT_TIMESTAMP(3), p.created_by,
       CURRENT_TIMESTAMP(3), p.created_by, CURRENT_TIMESTAMP(3), p.created_by, 0
FROM plan p
JOIN (
  SELECT 0 AS offset_value UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) n ON p.start_year + n.offset_value <= p.end_year
LEFT JOIN plan_year py ON py.plan_id = p.id AND py.year = p.start_year + n.offset_value AND py.deleted = 0
WHERE p.plan_type = 'INDICATOR' AND p.deleted = 0 AND py.id IS NULL;

UPDATE task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0 AND t.is_historical = 0
JOIN plan p ON p.id = ty.plan_id AND p.plan_type = 'INDICATOR' AND p.deleted = 0
JOIN plan_year py ON py.plan_id = p.id AND py.year = ty.year AND py.status = 'ACTIVE' AND py.deleted = 0
SET ty.year_status = 'IN_PROGRESS', ty.updated_at = CURRENT_TIMESTAMP(3)
WHERE ty.year_status = 'NOT_ACTIVATED' AND ty.deleted = 0
  AND t.status IN ('DISTRIBUTED', 'IN_PROGRESS');
