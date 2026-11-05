-- 指标按建设方向字母编码；执行单元统一使用数字编码
-- 仅修改 indicator.code 与 org_unit.code，保留全部业务 ID、历史 A+、任务及执行记录
USE metrics_db;
SET NAMES utf8mb4;
SET time_zone = '+08:00';

DROP TEMPORARY TABLE IF EXISTS tmp_indicator_code_v17;
CREATE TEMPORARY TABLE tmp_indicator_code_v17 (
  id      BIGINT      NOT NULL PRIMARY KEY,
  new_code VARCHAR(32) NOT NULL
) ENGINE = MEMORY;

-- 已分配方向：每个方向内按原 sort、id 独立编号，如 A01、B01、AA01
INSERT INTO tmp_indicator_code_v17 (id, new_code)
WITH numbered AS (
  SELECT i.id,
         d.code AS direction_code,
         ROW_NUMBER() OVER (
           PARTITION BY i.plan_id, i.direction_id
           ORDER BY i.sort, i.id
         ) AS sequence_no
  FROM indicator i
  JOIN direction d ON d.id = i.direction_id AND d.plan_id = i.plan_id
  WHERE i.level = 'A'
    AND i.parent_id IS NULL
    AND i.deleted = 0
)
SELECT id, CONCAT(direction_code, LPAD(sequence_no, 2, '0'))
FROM numbered;

-- 未分配方向：保留 A 系列；从方向 A 已占用的序号之后取号，避免计划内重号
INSERT INTO tmp_indicator_code_v17 (id, new_code)
WITH numbered AS (
  SELECT i.id,
         i.plan_id,
         ROW_NUMBER() OVER (PARTITION BY i.plan_id ORDER BY i.sort, i.id) AS sequence_no
  FROM indicator i
  WHERE i.level = 'A'
    AND i.parent_id IS NULL
    AND i.direction_id IS NULL
    AND i.deleted = 0
), direction_a_count AS (
  SELECT i.plan_id, COUNT(*) AS code_count
  FROM indicator i
  JOIN direction d ON d.id = i.direction_id AND d.plan_id = i.plan_id
  WHERE i.level = 'A'
    AND i.parent_id IS NULL
    AND i.deleted = 0
    AND d.code = 'A'
  GROUP BY i.plan_id
)
SELECT n.id, CONCAT('A', LPAD(n.sequence_no + COALESCE(c.code_count, 0), 2, '0'))
FROM numbered n
LEFT JOIN direction_a_count c ON c.plan_id = n.plan_id;

-- 两阶段更新避免交换编码时触发同计划唯一索引冲突
UPDATE indicator i
JOIN tmp_indicator_code_v17 t ON t.id = i.id
SET i.code = CONCAT('R17#', i.id);

UPDATE indicator i
JOIN tmp_indicator_code_v17 t ON t.id = i.id
SET i.code = t.new_code;

ALTER TABLE indicator
  MODIFY COLUMN code VARCHAR(32) NOT NULL
  COMMENT '指标编号：方向字母编码+两位序号，如 A01、B01、AA01；历史 A+ 原编码保留';

DROP TEMPORARY TABLE IF EXISTS tmp_org_unit_code_v17;
CREATE TEMPORARY TABLE tmp_org_unit_code_v17 (
  id      BIGINT      NOT NULL PRIMARY KEY,
  new_code VARCHAR(16) NOT NULL
) ENGINE = MEMORY;

-- 现行 C01–C10 依次迁移为 01–10，S01 等其他旧编码随后迁移；
-- 已存在的纯数字单元排在历史编码之后，软删除记录最后处理，避免占用现行序号
INSERT INTO tmp_org_unit_code_v17 (id, new_code)
WITH numbered AS (
  SELECT id,
         ROW_NUMBER() OVER (
           ORDER BY
             CASE WHEN deleted = 0 THEN 0 ELSE 1 END,
             CASE
               WHEN code REGEXP '^C[0-9]+$' THEN 0
               WHEN code REGEXP '^[0-9]+$' THEN 2
               ELSE 1
             END,
             sort,
             id
         ) AS sequence_no
  FROM org_unit
)
SELECT id, LPAD(sequence_no, 2, '0')
FROM numbered;

UPDATE org_unit u
JOIN tmp_org_unit_code_v17 t ON t.id = u.id
SET u.code = CONCAT('R17#', t.new_code);

UPDATE org_unit u
JOIN tmp_org_unit_code_v17 t ON t.id = u.id
SET u.code = t.new_code;

ALTER TABLE org_unit
  MODIFY COLUMN code VARCHAR(16) NOT NULL COMMENT '单元数字编码：01、02、03…，用于新 B 任务编号';

DROP TEMPORARY TABLE IF EXISTS tmp_indicator_code_v17;
DROP TEMPORARY TABLE IF EXISTS tmp_org_unit_code_v17;
