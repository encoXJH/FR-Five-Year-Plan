UPDATE notification
SET feishu_status = 'SKIPPED',
    feishu_next_retry_at = NULL,
    feishu_last_error = '按用户要求取消学院任务下发外发'
WHERE type = 'TASK_DISTRIBUTED'
  AND feishu_status = 'PENDING';

SET @v40_cancelled_count = ROW_COUNT();

INSERT INTO audit_log (
  id, occurred_at, operator_id, operator_name, roles, unit_id, plan_id,
  action, resource_type, resource_id, resource_code, before_json, after_json,
  stakeholder_ids, reason, request_id
) VALUES (
  2311114000000000000, NOW(3), 201, '平台管理员', JSON_ARRAY('ADMIN'), NULL, NULL,
  'UPDATE', 'notification', NULL, 'TASK_DISTRIBUTED',
  JSON_OBJECT('type', 'TASK_DISTRIBUTED', 'feishuStatus', 'PENDING'),
  JSON_OBJECT('feishuStatus', 'SKIPPED', 'cancelledCount', @v40_cancelled_count),
  JSON_ARRAY(), '按用户要求取消学院任务下发待发送记录，不再真实外发',
  'flyway-v40-cancel-task-distributed-notifications'
);
