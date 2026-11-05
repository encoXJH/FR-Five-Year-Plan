-- 移除统计报表下发配置功能，统计报表对所有 stats:view 角色开放
DROP TABLE IF EXISTS stats_report_grant;
