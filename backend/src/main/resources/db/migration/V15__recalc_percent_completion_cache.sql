-- V14 修正比率类目标后，同步重算任务与指标年度完成缓存。
UPDATE task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0
JOIN indicator i ON i.id = t.indicator_id AND i.deleted = 0
SET ty.completion_rate = CASE
        WHEN ty.actual_value IS NULL OR ty.target IS NULL OR ty.target = 0 THEN NULL
        ELSE ROUND(ty.actual_value / ty.target, 6)
    END,
    ty.is_achieved = CASE
        WHEN ty.actual_value IS NULL OR ty.target IS NULL THEN NULL
        WHEN i.target_direction = 'LOWER_BETTER' THEN ty.actual_value <= ty.target
        ELSE ty.actual_value >= ty.target
    END,
    ty.year_status = CASE
        WHEN ty.year_status NOT IN ('NOT_ACTIVATED', 'IN_PROGRESS', 'PENDING_REVIEW', 'ACHIEVED')
            THEN ty.year_status
        WHEN ty.actual_value IS NULL AND NOT EXISTS (
            SELECT 1 FROM submission s
            WHERE s.task_year_id = ty.id AND s.deleted = 0
        ) THEN ty.year_status
        WHEN EXISTS (
            SELECT 1 FROM submission s
            WHERE s.task_year_id = ty.id AND s.deleted = 0 AND s.status = 'PENDING_REVIEW'
        ) THEN 'PENDING_REVIEW'
        WHEN ty.actual_value IS NOT NULL AND ty.target IS NOT NULL AND (
            (i.target_direction = 'LOWER_BETTER' AND ty.actual_value <= ty.target)
            OR (i.target_direction <> 'LOWER_BETTER' AND ty.actual_value >= ty.target)
        ) THEN 'ACHIEVED'
        ELSE 'IN_PROGRESS'
    END,
    ty.version = ty.version + 1,
    ty.updated_at = NOW(3)
WHERE ty.deleted = 0
  AND i.stat_caliber IN ('RATIO', 'DIRECT_PERCENT');

UPDATE indicator_year_target iyt
JOIN indicator i ON i.id = iyt.indicator_id AND i.deleted = 0
SET iyt.completion_rate = CASE
        WHEN iyt.actual_value IS NULL OR iyt.target IS NULL OR iyt.target = 0 THEN NULL
        ELSE ROUND(iyt.actual_value / iyt.target, 6)
    END,
    iyt.achieve_status = CASE
        WHEN iyt.actual_value IS NULL AND iyt.actual_numerator IS NULL AND iyt.actual_total IS NULL
            THEN 'NOT_STARTED'
        WHEN iyt.actual_value IS NULL OR iyt.target IS NULL THEN 'IN_PROGRESS'
        WHEN (i.target_direction = 'LOWER_BETTER' AND iyt.actual_value <= iyt.target)
            OR (i.target_direction <> 'LOWER_BETTER' AND iyt.actual_value >= iyt.target)
            THEN CASE
                WHEN iyt.target <> 0 AND iyt.actual_value / iyt.target > 1 THEN 'EXCEEDED'
                ELSE 'ACHIEVED'
            END
        ELSE 'IN_PROGRESS'
    END,
    iyt.calculated_at = NOW(3),
    iyt.version = iyt.version + 1,
    iyt.updated_at = NOW(3)
WHERE iyt.deleted = 0
  AND i.stat_caliber IN ('RATIO', 'DIRECT_PERCENT');
