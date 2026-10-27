-- =====================================================================
-- 五年计划指标管理平台 · V2 基础数据（执行单元 / 字典 / 系统参数 / 开发测试账号）
-- 说明：
--   1. 本脚本为 Flyway 迁移，生产环境会执行；其中开发测试账号仅用于联调，
--      生产上线前必须删除或禁用（见文末注释），密码哈希不得外泄。
--   2. 测试账号密码统一为 Test@123456（BCrypt cost=10），仅用于开发测试环境。
-- =====================================================================

USE metrics_db;
SET NAMES utf8mb4;
SET time_zone = '+08:00';

-- =====================================================================
-- 1. 执行单元（PRD 2.1，共 11 个；编码用于 B+ 任务编号）
-- =====================================================================
INSERT INTO org_unit (id, code, name, type, sort, is_enabled, created_by, updated_by) VALUES
(101, 'C01', '智能产业学院',         'COLLEGE',              1, 1, 201, 201),
(102, 'C02', '数字金融产业学院',     'COLLEGE',              2, 1, 201, 201),
(103, 'C03', '数字影视传媒产业学院', 'COLLEGE',              3, 1, 201, 201),
(104, 'C04', '数据产业学院',         'COLLEGE',              4, 1, 201, 201),
(105, 'C05', '游戏产业学院',         'COLLEGE',              5, 1, 201, 201),
(106, 'C06', '智能建造产业学院',     'COLLEGE',              6, 1, 201, 201),
(107, 'C07', '现代通信产业学院',     'COLLEGE',              7, 1, 201, 201),
(108, 'C08', '未来技术产业学院',     'COLLEGE',              8, 1, 201, 201),
(109, 'C09', '国际学院',             'INTERNATIONAL_COLLEGE', 9, 1, 201, 201),
(110, 'C10', '马克思主义学院',       'MARXISM_COLLEGE',      10, 1, 201, 201),
(111, 'S01', '校办公室',             'SCHOOL_OFFICE',        11, 1, 201, 201);

-- =====================================================================
-- 2. 开发测试账号（密码统一 Test@123456，BCrypt cost=10）
--    【警告】生产环境上线前必须删除本段或重置密码！
--    BCrypt(Test@123456, cost=10) =
--    $2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK
-- =====================================================================
INSERT INTO sys_user (id, username, password_hash, name, phone, email, unit_id, is_enabled, created_by, updated_by) VALUES
(201, 'admin',  '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '系统管理员',   '13800000001', 'admin@fzu.edu.cn',   NULL, 1, 201, 201),
(202, 'leader', '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '校领导·陈明',  '13800000002', 'leader@fzu.edu.cn',  NULL, 1, 201, 201),
(203, 'owner1', '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '校级负责人·林华', '13800000003', 'owner1@fzu.edu.cn', NULL, 1, 201, 201),
(204, 'owner2', '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '校级负责人·赵芸', '13800000004', 'owner2@fzu.edu.cn', NULL, 1, 201, 201),
(211, 'c01',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '智能产业学院负责人',     '13800000011', 'c01@fzu.edu.cn', 101, 1, 201, 201),
(212, 'c02',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '数字金融产业学院负责人', '13800000012', 'c02@fzu.edu.cn', 102, 1, 201, 201),
(213, 'c03',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '数字影视传媒产业学院负责人', '13800000013', 'c03@fzu.edu.cn', 103, 1, 201, 201),
(214, 'c04',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '数据产业学院负责人',     '13800000014', 'c04@fzu.edu.cn', 104, 1, 201, 201),
(215, 'c05',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '游戏产业学院负责人',     '13800000015', 'c05@fzu.edu.cn', 105, 1, 201, 201),
(216, 'c06',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '智能建造产业学院负责人', '13800000016', 'c06@fzu.edu.cn', 106, 1, 201, 201),
(217, 'c07',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '现代通信产业学院负责人', '13800000017', 'c07@fzu.edu.cn', 107, 1, 201, 201),
(218, 'c08',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '未来技术产业学院负责人', '13800000018', 'c08@fzu.edu.cn', 108, 1, 201, 201),
(219, 'c09',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '国际学院负责人',         '13800000019', 'c09@fzu.edu.cn', 109, 1, 201, 201),
(220, 'c10',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '马克思主义学院负责人',   '13800000020', 'c10@fzu.edu.cn', 110, 1, 201, 201),
(221, 's01',    '$2b$10$Lx2vuGVJah/vWXn4os58FOnh99v3s2spd1TGuHbI86RqfK6IiQAGK', '校办公室负责人',         '13800000021', 's01@fzu.edu.cn', 111, 1, 201, 201);

-- 用户角色（一个用户可拥有多个角色；role 为 MySQL 8.0 保留字，列清单须加反引号）
INSERT INTO user_role (id, user_id, `role`, created_by) VALUES
(251, 201, 'ADMIN', 201),
(252, 202, 'LEADER', 201),
(253, 203, 'SCHOOL_OWNER', 201),
(254, 204, 'SCHOOL_OWNER', 201),
(261, 211, 'UNIT_OWNER', 201),
(262, 212, 'UNIT_OWNER', 201),
(263, 213, 'UNIT_OWNER', 201),
(264, 214, 'UNIT_OWNER', 201),
(265, 215, 'UNIT_OWNER', 201),
(266, 216, 'UNIT_OWNER', 201),
(267, 217, 'UNIT_OWNER', 201),
(268, 218, 'UNIT_OWNER', 201),
(269, 219, 'UNIT_OWNER', 201),
(270, 220, 'UNIT_OWNER', 201),
(271, 221, 'UNIT_OWNER', 201);

-- =====================================================================
-- 3. 字典：计量单位（indicator.unit_label 的可选值）
-- =====================================================================
INSERT INTO dict_item (id, dict_type, code, label, sort, is_enabled, created_by, updated_by) VALUES
(301, 'UNIT_LABEL', 'XIANG',    '项',   1, 1, 201, 201),
(302, 'UNIT_LABEL', 'GE',       '个',   2, 1, 201, 201),
(303, 'UNIT_LABEL', 'REN',      '人',   3, 1, 201, 201),
(304, 'UNIT_LABEL', 'RENCI',    '人次', 4, 1, 201, 201),
(305, 'UNIT_LABEL', 'MEN',      '门',   5, 1, 201, 201),
(306, 'UNIT_LABEL', 'PIAN',     '篇',   6, 1, 201, 201),
(307, 'UNIT_LABEL', 'BU',       '部',   7, 1, 201, 201),
(308, 'UNIT_LABEL', 'BEN',      '本',   8, 1, 201, 201),
(309, 'UNIT_LABEL', 'WANYUAN',  '万元', 9, 1, 201, 201),
(310, 'UNIT_LABEL', 'PERCENT',  '%',    10, 1, 201, 201);

-- =====================================================================
-- 4. 系统参数
-- =====================================================================
INSERT INTO sys_setting (id, setting_key, setting_value, value_type, description, created_by, updated_by) VALUES
-- 文件上传限制（对应 API 约定 8.5）
(401, 'file.maxSize.DOCUMENT',      '52428800',  'NUMBER', '文档类文件大小上限（字节，50MB）', 201, 201),
(402, 'file.maxSize.PDF',           '52428800',  'NUMBER', 'PDF 文件大小上限（字节，50MB）', 201, 201),
(403, 'file.maxSize.IMAGE',         '20971520',  'NUMBER', '图片文件大小上限（字节，20MB）', 201, 201),
(404, 'file.maxSize.VIDEO',         '524288000', 'NUMBER', '视频文件大小上限（字节，500MB）', 201, 201),
(405, 'file.maxSize.ARCHIVE',       '209715200', 'NUMBER', '压缩包大小上限（字节，200MB）', 201, 201),
(406, 'file.allowedExt.DOCUMENT',   '["doc","docx","xls","xlsx","ppt","pptx","txt"]', 'JSON', '文档类允许的扩展名', 201, 201),
(407, 'file.allowedExt.PDF',        '["pdf"]',   'JSON',   'PDF 类允许的扩展名', 201, 201),
(408, 'file.allowedExt.IMAGE',      '["jpg","jpeg","png","gif","webp","bmp"]', 'JSON', '图片类允许的扩展名', 201, 201),
(409, 'file.allowedExt.VIDEO',      '["mp4","mov","avi","wmv","flv","mkv"]', 'JSON', '视频类允许的扩展名', 201, 201),
(410, 'file.allowedExt.ARCHIVE',    '["zip","rar","7z"]', 'JSON', '压缩包允许的扩展名', 201, 201),
(411, 'file.temp.expireHours',      '24',        'NUMBER', '临时文件（TEMP 未绑定）保留小时数，超时自动清理', 201, 201),
-- 预警与提醒
(412, 'warning.lag.threshold',      '0.5',       'NUMBER', '进度滞后预警阈值：时间进度 - 完成进度 > 该值时预警', 201, 201),
(413, 'reminder.days.beforeDeadline', '7',       'NUMBER', '截止前提醒天数（默认 7 天）', 201, 201),
-- 核算
(414, 'plan.settleDeadline.default', '01-15',    'STRING', '年度核算截止的默认月-日', 201, 201),
-- 安全
(415, 'security.login.maxFailCount', '5',        'NUMBER', '连续登录失败锁定阈值', 201, 201),
(416, 'security.login.lockMinutes',  '30',       'NUMBER', '登录失败锁定时长（分钟）', 201, 201),
-- 令牌与幂等
(417, 'accessToken.expireMinutes',   '120',      'NUMBER', '访问令牌有效期（分钟）', 201, 201),
(418, 'refreshToken.expireDays',     '7',        'NUMBER', '刷新令牌有效期（天）', 201, 201),
(419, 'idempotency.expireHours',     '24',       'NUMBER', '幂等记录保留时长（小时）', 201, 201);
