INSERT INTO sys_setting (id, setting_key, setting_value, value_type, description, created_by, updated_by) VALUES
(423, 'feishu.logClearedAt', DATE_FORMAT(NOW(3), '%Y-%m-%d %H:%i:%s.%f'), 'STRING', '机器人推送日志清空时点；仅隐藏该时点及之前的日志，不删除站内通知，后续新日志正常显示', 201, 201);
