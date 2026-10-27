-- =====================================================================
-- V6：年度填报截止日参数（界面设计规范 A8 / P07 / P08 / P16 截止时间展示）
-- 填报截止 = 当年 fillDeadline（MM-dd）；与核算截止（次年 settleDeadline）区分。
-- =====================================================================
INSERT INTO sys_setting (id, setting_key, setting_value, value_type, description, created_by, updated_by) VALUES
(420, 'plan.fillDeadline', '12-31', 'STRING', '年度填报截止的月-日（MM-dd），学院工作台 / 填报页 / 待办的截止时间展示', 201, 201);
