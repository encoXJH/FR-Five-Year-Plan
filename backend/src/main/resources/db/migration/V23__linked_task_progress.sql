ALTER TABLE task_year
  ADD COLUMN own_actual_value DECIMAL(20,6) NULL COMMENT '本任务自身审核通过填报计算的年度完成值' AFTER frozen_amount,
  ADD COLUMN own_actual_numerator DECIMAL(20,4) NULL COMMENT '本任务自身 RATIO 分子' AFTER own_actual_value,
  ADD COLUMN own_actual_denominator DECIMAL(20,4) NULL COMMENT '本任务自身 RATIO 分母' AFTER own_actual_numerator,
  ADD COLUMN own_actual_total DECIMAL(20,4) NULL COMMENT '本任务自身 STOCK / GROWTH 总量' AFTER own_actual_denominator;

UPDATE task_year
SET own_actual_value = actual_value,
    own_actual_numerator = actual_numerator,
    own_actual_denominator = actual_denominator,
    own_actual_total = actual_total;
