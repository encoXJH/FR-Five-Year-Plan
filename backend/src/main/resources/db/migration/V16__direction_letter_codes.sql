-- 建设计划内方向字母编号；保留全部历史行与既有学院、任务编码
USE metrics_db;
SET NAMES utf8mb4;
SET time_zone = '+08:00';

ALTER TABLE direction
  ADD COLUMN code VARCHAR(32) NULL COMMENT '计划内字母编号：A…Z、AA…ZZ、AAA…；创建后不复用、不重排' AFTER plan_id;

WITH numbered_direction AS (
  SELECT id,
         ROW_NUMBER() OVER (PARTITION BY plan_id ORDER BY sort, id) AS sequence_no
  FROM direction
)
UPDATE direction d
JOIN numbered_direction n ON n.id = d.id
SET d.code = CONCAT(REPEAT(
    CHAR(65 + ((n.sequence_no - 1) % 26) USING utf8mb4),
    FLOOR((n.sequence_no - 1) / 26) + 1))
WHERE d.code IS NULL;

ALTER TABLE direction
  MODIFY COLUMN code VARCHAR(32) NOT NULL COMMENT '计划内字母编号：A…Z、AA…ZZ、AAA…；创建后不复用、不重排',
  ADD UNIQUE KEY uk_direction_plan_code (plan_id, code);
