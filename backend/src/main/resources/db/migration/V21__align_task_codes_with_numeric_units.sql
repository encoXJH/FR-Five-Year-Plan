-- 新 A→B 任务编号统一为「指标编码-数字单元编码」，如 A01-01
-- 仅处理 indicator_id 指向 A 级指标的任务；历史 A+ 下的旧 B 编号保持原样
-- 两阶段更新，避免编码交换时触发 (plan_id, code, deleted) 唯一键冲突

UPDATE task t
JOIN indicator i ON i.id = t.indicator_id AND i.deleted = 0 AND i.level = 'A'
JOIN org_unit u ON u.id = t.unit_id AND u.deleted = 0
SET t.code = CONCAT('__TMP_TASK_', t.id)
WHERE t.deleted = 0;

UPDATE task t
JOIN indicator i ON i.id = t.indicator_id AND i.deleted = 0 AND i.level = 'A'
JOIN org_unit u ON u.id = t.unit_id AND u.deleted = 0
SET t.code = CONCAT(i.code, '-', u.code)
WHERE t.deleted = 0 AND t.code LIKE '__TMP_TASK_%';
