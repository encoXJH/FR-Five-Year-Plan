-- =====================================================================
-- V24：全校组织架构（独立于执行单元 org_unit）
-- 说明：
--   1. org_unit 仍只维护承接任务的 11 个执行单元，本表仅承载全校组织树；
--   2. 节点分类 fixed 为 SCHOOL / LEADERSHIP / FUNCTIONAL / TEACHING /
--      ENTERPRISE / SUPERMARKET / PROPERTY / CLINIC / BUSINESS；
--   3. 源清单括号内人数暂不导入，后续如需统计再单独扩展字段。
-- =====================================================================

CREATE TABLE org_department (
  id          BIGINT       NOT NULL                COMMENT '主键，雪花 ID',
  parent_id   BIGINT       NOT NULL DEFAULT 0      COMMENT '上级节点，引用 org_department.id；根节点为 0',
  name        VARCHAR(200) NOT NULL                COMMENT '组织名称',
  category    VARCHAR(32)  NOT NULL                COMMENT '组织分类：SCHOOL 学校 / LEADERSHIP 学校领导 / FUNCTIONAL 职能部门 / TEACHING 教学单位 / ENTERPRISE 校企合作 / SUPERMARKET 学校超市 / PROPERTY 物业 / CLINIC 医务室 / BUSINESS 商务',
  sort        INT          NOT NULL DEFAULT 0      COMMENT '同级排序号',
  is_enabled  TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
  version     INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by  BIGINT       NULL                    COMMENT '创建人，引用 sys_user.id',
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by  BIGINT       NULL                    COMMENT '更新人，引用 sys_user.id',
  deleted     BIGINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_org_department_parent_name (parent_id, name, deleted),
  KEY idx_org_department_parent (parent_id),
  CONSTRAINT chk_org_department_category CHECK (category IN (
    'SCHOOL','LEADERSHIP','FUNCTIONAL','TEACHING','ENTERPRISE','SUPERMARKET','PROPERTY','CLINIC','BUSINESS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全校组织架构';

INSERT INTO org_department (id, parent_id, name, category, sort) VALUES
-- 根节点
(2001, 0,    '福州软件职业技术学院', 'SCHOOL', 1),
-- 一级分类
(2101, 2001, '学校领导', 'LEADERSHIP', 1),
(2102, 2001, '职能部门', 'FUNCTIONAL', 2),
(2103, 2001, '教学单位', 'TEACHING', 3),
(2104, 2001, '校企合作', 'ENTERPRISE', 4),
(2105, 2001, '学校超市', 'SUPERMARKET', 5),
(2106, 2001, '物业', 'PROPERTY', 6),
(2107, 2001, '医务室', 'CLINIC', 7),
(2108, 2001, '商务', 'BUSINESS', 8),
-- 学校领导
(2111, 2101, '学校党政', 'LEADERSHIP', 1),
-- 职能部门
(2201, 2102, '党政办公室', 'FUNCTIONAL', 1),
(2202, 2102, '党委组织部（党委宣传部/统战部）', 'FUNCTIONAL', 2),
(2203, 2102, '财务处', 'FUNCTIONAL', 3),
(2204, 2102, '人才发展与AI赋能中心', 'FUNCTIONAL', 4),
(2205, 2102, '教师发展中心（党委教师工作部）', 'FUNCTIONAL', 5),
(2206, 2102, '教学发展与AI研究中心', 'FUNCTIONAL', 6),
(2207, 2102, '创新创业学院', 'FUNCTIONAL', 7),
(2208, 2102, 'AI驱动教学质量保障中心', 'FUNCTIONAL', 8),
(2209, 2102, '学生发展与AI赋能中心', 'FUNCTIONAL', 9),
(2210, 2102, '保卫部（党委安全保卫部）', 'FUNCTIONAL', 10),
(2211, 2102, '招生工作处', 'FUNCTIONAL', 11),
(2212, 2102, 'AI就业与发展中心', 'FUNCTIONAL', 12),
(2213, 2102, '产业AI人才服务中心', 'FUNCTIONAL', 13),
(2214, 2102, '校团委', 'FUNCTIONAL', 14),
(2215, 2102, '工会', 'FUNCTIONAL', 15),
(2216, 2102, '校友会', 'FUNCTIONAL', 16),
(2217, 2102, '后勤与资产管理处', 'FUNCTIONAL', 17),
(2218, 2102, 'AI学习资源中心', 'FUNCTIONAL', 18),
(2219, 2102, 'AI设施与算力服务中心', 'FUNCTIONAL', 19),
(2220, 2102, 'AI与数字化赋能中心', 'FUNCTIONAL', 20),
(2221, 2102, 'AI资源制作中心', 'FUNCTIONAL', 21),
(2222, 2102, '纪委办公室', 'FUNCTIONAL', 22),
-- 教学单位
(2301, 2103, '马克思主义学院', 'TEACHING', 1),
(2302, 2103, '通识教育中心', 'TEACHING', 2),
(2303, 2103, '军体教研部', 'TEACHING', 3),
(2304, 2103, '游戏产业学院', 'TEACHING', 4),
(2305, 2103, '智能产业学院', 'TEACHING', 5),
(2306, 2103, '数字金融产业学院', 'TEACHING', 6),
(2307, 2103, '智能建造产业学院', 'TEACHING', 7),
(2308, 2103, '数字影视传媒产业学院', 'TEACHING', 8),
(2309, 2103, '现代通信产业学院', 'TEACHING', 9),
(2310, 2103, '数据产业学院', 'TEACHING', 10),
(2311, 2103, '未来技术产业学院', 'TEACHING', 11),
(2312, 2103, '国际学院', 'TEACHING', 12),
-- 校企合作
(2401, 2104, '数字影视传媒产业学院（企业）', 'ENTERPRISE', 1),
(2402, 2104, '现代通信产业学院（企业）', 'ENTERPRISE', 2),
(2403, 2104, '数据产业学院（企业）', 'ENTERPRISE', 3),
(2404, 2104, '未来技术产业学院（企业）', 'ENTERPRISE', 4),
(2405, 2104, '游戏产业学院（企业）', 'ENTERPRISE', 5),
(2406, 2104, '数字金融产业学院（企业）', 'ENTERPRISE', 6),
(2407, 2104, '智能产业学院（企业）', 'ENTERPRISE', 7),
-- 学校超市
(2501, 2105, '堂堂正正公司', 'SUPERMARKET', 1),
-- 物业
(2601, 2106, '文岭雅域物业', 'PROPERTY', 1),
(2602, 2106, '文武砂后勤部', 'PROPERTY', 2),
-- 医务室
(2701, 2107, '校医务室', 'CLINIC', 1),
-- 商务
(2801, 2108, '文岭商务', 'BUSINESS', 1),
(2802, 2108, '文武砂商务', 'BUSINESS', 2);
