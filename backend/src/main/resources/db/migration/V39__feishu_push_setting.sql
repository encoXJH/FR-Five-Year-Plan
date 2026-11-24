-- 双高管理平台机器人：可持久化消息推送开关，初始安全关闭
INSERT INTO sys_setting (id, setting_key, setting_value, value_type, description, created_by, updated_by) VALUES
(421, 'feishu.pushEnabled', 'false', 'BOOLEAN', '双高管理平台机器人消息推送开关；关闭时保留待推送消息并停止全部飞书外发', 201, 201);
