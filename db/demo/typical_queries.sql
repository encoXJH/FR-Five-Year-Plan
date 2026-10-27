-- =====================================================================
-- 五年计划指标管理平台 · 典型查询（S / A / A+ / B 四层体系）
-- 前置：已执行 V1–V7、种子数据和同源 demo_data.sql
-- 计划：9100000000000001001（2026—2030）
-- =====================================================================

USE metrics_db;
SET NAMES utf8mb4;

-- a. 游戏产业学院 C05 的 2026/2027 年现行 B 任务
-- 【预期结果】12 行（6 条任务 × 2 年），is_historical 均为 0。
EXPLAIN
SELECT t.code, ty.year, ty.target, ty.original_target, ty.target_version, ty.year_status
FROM task t
JOIN org_unit u ON u.id = t.unit_id
JOIN task_year ty ON ty.task_id = t.id AND ty.deleted = 0
WHERE t.deleted = 0 AND t.is_historical = 0
  AND t.plan_id = 9100000000000001001 AND u.code = 'C05'
  AND ty.year IN (2026, 2027)
ORDER BY t.code, ty.year;

SELECT t.code, ty.year, ty.target, ty.original_target, ty.target_version, ty.year_status
FROM task t
JOIN org_unit u ON u.id = t.unit_id
JOIN task_year ty ON ty.task_id = t.id AND ty.deleted = 0
WHERE t.deleted = 0 AND t.is_historical = 0
  AND t.plan_id = 9100000000000001001 AND u.code = 'C05'
  AND ty.year IN (2026, 2027)
ORDER BY t.code, ty.year;

-- b. 校级负责人 203 的当前待审核填报
-- 【预期结果】5 行；历史任务的已终止填报不出现。
EXPLAIN
SELECT s.id, t.code, s.year, s.stat_caliber, s.numerator, s.denominator, s.total, s.submitted_at
FROM submission s
JOIN task t ON t.id = s.task_id AND t.deleted = 0 AND t.is_historical = 0
JOIN indicator a ON a.id = t.top_indicator_id AND a.deleted = 0
WHERE s.deleted = 0 AND s.status = 'PENDING_REVIEW' AND a.owner_id = 203
ORDER BY s.submitted_at, s.id;

SELECT s.id, t.code, s.year, s.stat_caliber, s.numerator, s.denominator, s.total, s.submitted_at
FROM submission s
JOIN task t ON t.id = s.task_id AND t.deleted = 0 AND t.is_historical = 0
JOIN indicator a ON a.id = t.top_indicator_id AND a.deleted = 0
WHERE s.deleted = 0 AND s.status = 'PENDING_REVIEW' AND a.owner_id = 203
ORDER BY s.submitted_at, s.id;

-- c. INCREMENT 型 A+ 的逐年 B 目标守恒（历史目标仍参与守恒）
-- 【预期结果】0 行；去掉 HAVING 可查看每个 A+ 各年的合计与差值。
SELECT i.code, ty.year,
       SUM(ty.target) AS b_sum,
       MAX(iyt.target) AS a_plus_target,
       SUM(ty.target) - MAX(iyt.target) AS diff
FROM task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0
JOIN indicator i ON i.id = ty.indicator_id AND i.deleted = 0
JOIN indicator_year_target iyt
  ON iyt.indicator_id = ty.indicator_id AND iyt.year = ty.year AND iyt.deleted = 0
WHERE ty.deleted = 0 AND ty.plan_id = 9100000000000001001
  AND i.level = 'A_PLUS' AND i.stat_caliber = 'INCREMENT'
GROUP BY i.code, ty.indicator_id, ty.year
HAVING ABS(COALESCE(SUM(ty.target), 0) - COALESCE(MAX(iyt.target), 0)) > 0.000001
ORDER BY i.code, ty.year;

-- d. A04-1 的 2026 年 RATIO 汇总：必须使用 Σ分子 ÷ Σ分母
-- 【预期结果】160 / 287 = 0.557491；禁止直接平均各学院百分比。
SELECT i.code, s.year,
       SUM(s.approved_numerator) AS sum_numerator,
       SUM(s.approved_denominator) AS sum_denominator,
       ROUND(SUM(s.approved_numerator) / SUM(s.approved_denominator), 6) AS ratio_correct
FROM submission s
JOIN indicator i ON i.id = s.indicator_id AND i.deleted = 0
WHERE s.deleted = 0 AND s.status = 'APPROVED'
  AND i.code = 'A04-1' AND s.year = 2026
GROUP BY i.code, s.year;

-- e. 历史只读 B 任务分布
-- 【预期结果】C09=3、C10=4、S01=7，共 14 条；原 ID 和编号保持不变。
SELECT u.code, u.name, COUNT(*) AS historical_count
FROM task t
JOIN org_unit u ON u.id = t.unit_id
WHERE t.deleted = 0 AND t.is_historical = 1
  AND t.plan_id = 9100000000000001001
GROUP BY u.id, u.code, u.name
ORDER BY u.code;

-- f. 当前在途流程与历史在途流程
-- 【预期结果】pending_review=8、pending_adjustments=2、history_open_workflows=0。
SELECT
  (SELECT COUNT(*) FROM submission s JOIN task t ON t.id = s.task_id
   WHERE s.deleted = 0 AND t.deleted = 0 AND t.is_historical = 0
     AND s.status = 'PENDING_REVIEW') AS pending_review,
  (SELECT COUNT(*) FROM adjustment a JOIN task t ON t.id = a.task_id
   WHERE a.deleted = 0 AND t.deleted = 0 AND t.is_historical = 0
     AND a.status IN ('PENDING_OWNER_REVIEW','PENDING_RECEIVER_CONFIRM','PENDING_LEADER_APPROVAL')) AS pending_adjustments,
  (SELECT
     (SELECT COUNT(*) FROM submission s JOIN task t ON t.id = s.task_id
      WHERE s.deleted = 0 AND t.is_historical = 1
        AND s.status IN ('DRAFT','PENDING_REVIEW','REJECTED')) +
     (SELECT COUNT(*) FROM adjustment a JOIN task t ON t.id = a.task_id
      WHERE a.deleted = 0 AND t.is_historical = 1
        AND a.status IN ('PENDING_OWNER_REVIEW','PENDING_RECEIVER_CONFIRM','PENDING_LEADER_APPROVAL'))
  ) AS history_open_workflows;

-- g. ADMIN 的 S 级目录与总负责人配置
-- 【预期结果】8 行；S 仅用于后台管理，不进入普通业务统计。
SELECT d.id, d.name, d.sort, d.leader_id, u.name AS leader_name
FROM direction d
LEFT JOIN sys_user u ON u.id = d.leader_id AND u.deleted = 0
WHERE d.deleted = 0 AND d.plan_id = 9100000000000001001
ORDER BY d.sort, d.id;

-- h. 2026 年现行 B 的单元达标率（历史任务不计入当前执行统计）
-- 【预期结果】仅包含有正目标的现行单元，不返回 C09 / C10 / S01。
EXPLAIN
SELECT u.code, u.name,
       COUNT(ty.id) AS target_count,
       SUM(ty.year_status = 'ACHIEVED') AS achieved_count,
       ROUND(SUM(ty.year_status = 'ACHIEVED') / COUNT(ty.id), 4) AS complete_rate
FROM task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0 AND t.is_historical = 0
JOIN org_unit u ON u.id = ty.unit_id
WHERE ty.deleted = 0 AND ty.plan_id = 9100000000000001001
  AND ty.year = 2026 AND ty.target IS NOT NULL AND ty.target > 0
GROUP BY u.id, u.code, u.name
ORDER BY complete_rate DESC, u.code;

SELECT u.code, u.name,
       COUNT(ty.id) AS target_count,
       SUM(ty.year_status = 'ACHIEVED') AS achieved_count,
       ROUND(SUM(ty.year_status = 'ACHIEVED') / COUNT(ty.id), 4) AS complete_rate
FROM task_year ty
JOIN task t ON t.id = ty.task_id AND t.deleted = 0 AND t.is_historical = 0
JOIN org_unit u ON u.id = ty.unit_id
WHERE ty.deleted = 0 AND ty.plan_id = 9100000000000001001
  AND ty.year = 2026 AND ty.target IS NOT NULL AND ty.target > 0
GROUP BY u.id, u.code, u.name
ORDER BY complete_rate DESC, u.code;

-- i. 同源任务 ID、编号和历史标识核对
-- 【预期结果】total=63、current=49、historical=14、distinct_id=63、distinct_code=63。
SELECT COUNT(*) AS total,
       SUM(t.is_historical = 0) AS current_count,
       SUM(t.is_historical = 1) AS historical_count,
       COUNT(DISTINCT t.id) AS distinct_id,
       COUNT(DISTINCT t.code) AS distinct_code
FROM task t
WHERE t.deleted = 0 AND t.plan_id = 9100000000000001001;
