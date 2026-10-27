-- =====================================================================
-- 五年计划指标管理平台 · V1 初始化建表脚本
-- MySQL 8.0 / InnoDB / utf8mb4 / utf8mb4_0900_ai_ci / 时区 +08:00
-- 说明：
--   1. 主键 id 为雪花 ID（应用生成），不使用 AUTO_INCREMENT；
--   2. 不建物理外键，逻辑外键由应用保证，引用字段均已建索引；
--   3. 枚举用 VARCHAR(32) + CHECK 约束，不使用 MySQL ENUM 类型；
--   4. 业务表通用字段：version / created_at / created_by / updated_at / updated_by / deleted；
--      日志与记录类（audit_log、idempotency_record、feishu_event、notification、
--      submission_history、review、refresh_token、file_upload_session、file_ref、user_role）
--      为追加型或关联型数据，按需取舍，不带 version / deleted；
--   5. deleted：0 = 未删除；删除时写入本行 id，使带上 deleted 的唯一索引不与旧数据冲突。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS metrics_db
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE metrics_db;

SET NAMES utf8mb4;
SET time_zone = '+08:00';

-- =====================================================================
-- 模块一：组织与用户
-- =====================================================================

-- 执行单元（8 个二级学院 + 国际学院 + 马院 + 校办公室）
CREATE TABLE org_unit (
  id          BIGINT       NOT NULL                COMMENT '主键，雪花 ID',
  code        VARCHAR(16)  NOT NULL                COMMENT '单元编码，如 C01、S01，用于 B+ 任务编号',
  name        VARCHAR(200) NOT NULL                COMMENT '单元名称',
  type        VARCHAR(32)  NOT NULL                COMMENT '单元类型：COLLEGE 二级学院 / INTERNATIONAL_COLLEGE 国际学院 / MARXISM_COLLEGE 马克思主义学院 / SCHOOL_OFFICE 校办公室',
  sort        INT          NOT NULL DEFAULT 0      COMMENT '排序号',
  is_enabled  TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
  version     INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by  BIGINT       NULL                    COMMENT '创建人，引用 sys_user.id',
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by  BIGINT       NULL                    COMMENT '更新人，引用 sys_user.id',
  deleted     BIGINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_org_unit_code (code, deleted),
  CONSTRAINT chk_org_unit_type CHECK (type IN ('COLLEGE','INTERNATIONAL_COLLEGE','MARXISM_COLLEGE','SCHOOL_OFFICE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='执行单元';

-- 专业群
CREATE TABLE major_group (
  id          BIGINT       NOT NULL                COMMENT '主键，雪花 ID',
  name        VARCHAR(200) NOT NULL                COMMENT '专业群名称',
  unit_id     BIGINT       NOT NULL                COMMENT '所属学院，引用 org_unit.id',
  owner_id    BIGINT       NULL                    COMMENT '专业群负责人，引用 sys_user.id',
  is_enabled  TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
  version     INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by  BIGINT       NULL                    COMMENT '创建人，引用 sys_user.id',
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by  BIGINT       NULL                    COMMENT '更新人，引用 sys_user.id',
  deleted     BIGINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_major_group_unit (unit_id),
  KEY idx_major_group_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='专业群';

-- 用户
CREATE TABLE sys_user (
  id               BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  username         VARCHAR(64)  NOT NULL           COMMENT '登录名',
  password_hash    VARCHAR(100) NOT NULL           COMMENT '密码哈希（BCrypt）',
  name             VARCHAR(64)  NOT NULL           COMMENT '姓名',
  phone            VARCHAR(32)  NULL               COMMENT '手机号（响应与日志中脱敏）',
  email            VARCHAR(128) NULL               COMMENT '邮箱',
  unit_id          BIGINT       NULL               COMMENT '所属执行单元，引用 org_unit.id；校级角色为空',
  feishu_open_id   VARCHAR(64)  NULL               COMMENT '飞书 open_id，绑定后唯一',
  feishu_union_id  VARCHAR(64)  NULL               COMMENT '飞书 union_id',
  is_enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  login_fail_count INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
  locked_until     DATETIME(3)  NULL               COMMENT '账号锁定截止时间',
  last_login_at    DATETIME(3)  NULL               COMMENT '最近登录时间',
  version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by       BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by       BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted          BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_user_username (username, deleted),
  UNIQUE KEY uk_sys_user_feishu_open_id (feishu_open_id, deleted),
  KEY idx_sys_user_unit (unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

-- 用户角色（一个用户可拥有多个角色，纯关联表：不带 version / deleted）
CREATE TABLE user_role (
  id          BIGINT      NOT NULL             COMMENT '主键，雪花 ID',
  user_id     BIGINT      NOT NULL             COMMENT '用户，引用 sys_user.id',
  role        VARCHAR(32) NOT NULL             COMMENT '角色：ADMIN 平台管理员 / LEADER 校领导 / SCHOOL_OWNER 校级项目负责人 / UNIT_OWNER 学院项目负责人',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by  BIGINT      NULL                 COMMENT '创建人，引用 sys_user.id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role),
  KEY idx_user_role_role (role),
  CONSTRAINT chk_user_role_role CHECK (role IN ('ADMIN','LEADER','SCHOOL_OWNER','UNIT_OWNER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色';

-- =====================================================================
-- 模块二：计划与模板
-- =====================================================================

-- 模板（结构快照，不含执行数据）
CREATE TABLE template (
  id             BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  name           VARCHAR(200) NOT NULL           COMMENT '模板名称',
  description    VARCHAR(2000) NULL              COMMENT '模板说明',
  content_json   JSON         NOT NULL           COMMENT '结构快照：建设方向、指标定义、默认分解规则',
  source_plan_id BIGINT       NULL               COMMENT '来源计划，引用 plan.id',
  is_enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by     BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by     BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted        BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计划模板';

-- 计划
CREATE TABLE plan (
  id                            BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  name                          VARCHAR(200) NOT NULL           COMMENT '计划名称，如 2026-2030 五年发展规划',
  start_year                    SMALLINT     NOT NULL           COMMENT '起始年份',
  end_year                      SMALLINT     NOT NULL           COMMENT '结束年份',
  baseline_year                 SMALLINT     NOT NULL           COMMENT '基线年（规划起始前一年）',
  settle_deadline               CHAR(5)      NOT NULL DEFAULT '01-15' COMMENT '年度核算截止的月-日，如 01-15',
  status                        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '计划状态：DRAFT 草稿 / ACTIVE 执行中 / ARCHIVED 已归档',
  source                        VARCHAR(32)  NOT NULL DEFAULT 'BLANK' COMMENT '新建方式：BLANK 空白 / TEMPLATE 从模板 / COPY 复制计划',
  template_id                   BIGINT       NULL               COMMENT '来源模板，引用 template.id',
  source_plan_id                BIGINT       NULL               COMMENT '复制来源计划，引用 plan.id',
  is_receiver_confirm_required  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '转移时是否需要接收方确认（FR-M6-07）',
  finalized_at                  DATETIME(3)  NULL               COMMENT '指标体系定稿时间',
  finalized_by                  BIGINT       NULL               COMMENT '定稿操作人，引用 sys_user.id',
  version                       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at                    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by                    BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at                    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by                    BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted                       BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_plan_status (status),
  CONSTRAINT chk_plan_years CHECK (end_year >= start_year),
  CONSTRAINT chk_plan_status CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
  CONSTRAINT chk_plan_source CHECK (source IN ('BLANK','TEMPLATE','COPY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计划';

-- 计划年度（年度激活 / 锁定）
CREATE TABLE plan_year (
  id                 BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  plan_id            BIGINT       NOT NULL           COMMENT '计划，引用 plan.id',
  year               SMALLINT     NOT NULL           COMMENT '年度（自然年）',
  status             VARCHAR(32)  NOT NULL DEFAULT 'NOT_ACTIVATED' COMMENT '年度状态：NOT_ACTIVATED 未激活 / ACTIVE 已激活 / LOCKED 已核算锁定',
  activated_at       DATETIME(3)  NULL               COMMENT '激活时间',
  activated_by       BIGINT       NULL               COMMENT '激活操作人，引用 sys_user.id',
  settled_at         DATETIME(3)  NULL               COMMENT '年度核算锁定时间',
  settled_by         BIGINT       NULL               COMMENT '核算操作人，引用 sys_user.id',
  last_unlock_reason VARCHAR(500) NULL               COMMENT '最近一次解锁原因（敏感操作必填）',
  version            INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by         BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by         BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted            BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_plan_year (plan_id, year, deleted),
  CONSTRAINT chk_plan_year_status CHECK (status IN ('NOT_ACTIVATED','ACTIVE','LOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计划年度';

-- =====================================================================
-- 模块三：指标体系（A / A+）
-- =====================================================================

-- 主要建设方向
CREATE TABLE direction (
  id             BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  plan_id        BIGINT       NOT NULL           COMMENT '计划，引用 plan.id',
  subject_type   VARCHAR(32)  NOT NULL           COMMENT '主体：SCHOOL 校级 / MAJOR_GROUP 专业群',
  major_group_id BIGINT       NULL               COMMENT '主体为专业群时必填，引用 major_group.id',
  name           VARCHAR(200) NOT NULL           COMMENT '建设方向（建设任务）名称',
  sort           INT          NOT NULL DEFAULT 0 COMMENT '排序号',
  version        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by     BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by     BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted        BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_direction_plan (plan_id),
  CONSTRAINT chk_direction_subject CHECK (subject_type IN ('SCHOOL','MAJOR_GROUP')),
  CONSTRAINT chk_direction_major_group CHECK (subject_type = 'SCHOOL' OR major_group_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主要建设方向';

-- 指标（A 类和 A+ 类同表，parent_id 区分；A+ 不能再有下级，由应用保证）
CREATE TABLE indicator (
  id                 BIGINT        NOT NULL           COMMENT '主键，雪花 ID',
  plan_id            BIGINT        NOT NULL           COMMENT '计划，引用 plan.id',
  direction_id       BIGINT        NOT NULL           COMMENT '所属建设方向，引用 direction.id',
  parent_id          BIGINT        NULL               COMMENT 'A+ 指向所属 A，引用 indicator.id；A 为空',
  level              VARCHAR(32)   NOT NULL           COMMENT '层级：A / A_PLUS',
  code               VARCHAR(32)   NOT NULL           COMMENT '指标编号，同一计划内唯一，如 A01、A01-2',
  name               VARCHAR(200)  NOT NULL           COMMENT '关键产出指标名称',
  nature             VARCHAR(32)   NOT NULL           COMMENT '指标性质：CONSTRAINT 约束性 / DEVELOPMENT 发展性',
  measure_type       VARCHAR(32)   NOT NULL           COMMENT '计量类型：NUMBER / BOOLEAN / PERCENT_QUANTIFIABLE / PERCENT_DIRECT',
  stat_caliber       VARCHAR(32)   NOT NULL           COMMENT '统计口径：INCREMENT / STOCK / GROWTH / RATIO / DIRECT_PERCENT / BOOLEAN',
  unit_label         VARCHAR(32)   NOT NULL           COMMENT '计量单位：项、个、人、% 等（字典维护）',
  target_direction   VARCHAR(32)   NOT NULL DEFAULT 'HIGHER_BETTER' COMMENT '达成方向：HIGHER_BETTER 越大越好 / LOWER_BETTER 越小越好',
  rollup_mode        VARCHAR(32)   NULL               COMMENT '汇总方式（仅 A 有效）：AUTO_SUM 自动汇总 / MANUAL 手工认定',
  is_divisible       TINYINT(1)    NULL               COMMENT '是否可下分（仅 A 有效）',
  undertake_unit_id  BIGINT        NULL               COMMENT '不可下分 A 的承接单位，引用 org_unit.id，默认 S01',
  description        VARCHAR(2000) NULL               COMMENT '指标填写说明（统计口径、认定标准）',
  evidence_requirement VARCHAR(2000) NULL             COMMENT '佐证材料要求说明',
  min_file_count     INT           NOT NULL DEFAULT 0 COMMENT '必须上传的材料最少数量',
  baseline_value     DECIMAL(20,4) NULL               COMMENT '基线值（基线年实际值，GROWTH / STOCK 必填）',
  total_target       DECIMAL(20,6) NULL               COMMENT '五年目标总量（GROWTH / STOCK 口径为末年应达总量；BOOLEAN 为 1）',
  owner_id           BIGINT        NULL               COMMENT '校级项目负责人，引用 sys_user.id；A+ 为空时沿用上级 A',
  sort               INT           NOT NULL DEFAULT 0 COMMENT '排序号',
  status             VARCHAR(32)   NOT NULL DEFAULT 'DRAFT' COMMENT '指标状态：DRAFT 草稿 / FINALIZED 已定稿 / DISTRIBUTED 已下发',
  version            INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by         BIGINT        NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by         BIGINT        NULL               COMMENT '更新人，引用 sys_user.id',
  deleted            BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_indicator_code (plan_id, code, deleted),
  KEY idx_indicator_parent (parent_id),
  KEY idx_indicator_direction (direction_id),
  KEY idx_indicator_owner (owner_id),
  KEY idx_indicator_plan_level (plan_id, level),
  CONSTRAINT chk_indicator_level CHECK (level IN ('A','A_PLUS')),
  CONSTRAINT chk_indicator_parent CHECK ((level = 'A' AND parent_id IS NULL) OR (level = 'A_PLUS' AND parent_id IS NOT NULL)),
  CONSTRAINT chk_indicator_nature CHECK (nature IN ('CONSTRAINT','DEVELOPMENT')),
  CONSTRAINT chk_indicator_measure CHECK (measure_type IN ('NUMBER','BOOLEAN','PERCENT_QUANTIFIABLE','PERCENT_DIRECT')),
  CONSTRAINT chk_indicator_caliber CHECK (stat_caliber IN ('INCREMENT','STOCK','GROWTH','RATIO','DIRECT_PERCENT','BOOLEAN')),
  CONSTRAINT chk_indicator_measure_caliber CHECK (
    (measure_type = 'NUMBER' AND stat_caliber IN ('INCREMENT','STOCK')) OR
    (measure_type = 'BOOLEAN' AND stat_caliber = 'BOOLEAN') OR
    (measure_type = 'PERCENT_QUANTIFIABLE' AND stat_caliber IN ('GROWTH','RATIO')) OR
    (measure_type = 'PERCENT_DIRECT' AND stat_caliber = 'DIRECT_PERCENT')
  ),
  CONSTRAINT chk_indicator_direction CHECK (target_direction IN ('HIGHER_BETTER','LOWER_BETTER')),
  CONSTRAINT chk_indicator_rollup CHECK (rollup_mode IS NULL OR rollup_mode IN ('AUTO_SUM','MANUAL')),
  CONSTRAINT chk_indicator_status CHECK (status IN ('DRAFT','FINALIZED','DISTRIBUTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标（A / A+）';

-- 指标年度目标与完成情况缓存
CREATE TABLE indicator_year_target (
  id                   BIGINT        NOT NULL           COMMENT '主键，雪花 ID',
  indicator_id         BIGINT        NOT NULL           COMMENT '指标，引用 indicator.id',
  year                 SMALLINT      NOT NULL           COMMENT '年度',
  target               DECIMAL(20,6) NULL               COMMENT '年度目标（BOOLEAN 口径：计划完成年存 1，其他年份 NULL）',
  actual_value         DECIMAL(20,6) NULL               COMMENT '缓存：年度完成值（审核通过口径，按统计口径计算）',
  actual_numerator     DECIMAL(20,4) NULL               COMMENT '缓存：RATIO 口径汇总分子',
  actual_denominator   DECIMAL(20,4) NULL               COMMENT '缓存：RATIO 口径汇总分母',
  actual_total         DECIMAL(20,4) NULL               COMMENT '缓存：STOCK / GROWTH 口径的年末总量',
  completion_rate      DECIMAL(20,6) NULL               COMMENT '缓存：完成率（小数）',
  achieve_status       VARCHAR(32)   NULL               COMMENT '缓存：达成状态：NOT_STARTED / IN_PROGRESS / ACHIEVED / EXCEEDED / NOT_ACHIEVED / LAGGING',
  calculated_at        DATETIME(3)   NULL               COMMENT '缓存最近计算时间',
  manual_actual_value  DECIMAL(20,6) NULL               COMMENT 'A 汇总方式为 MANUAL 时的人工认定完成值',
  manual_note          VARCHAR(2000) NULL               COMMENT '人工认定说明',
  version              INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by           BIGINT        NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by           BIGINT        NULL               COMMENT '更新人，引用 sys_user.id',
  deleted              BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_iyt (indicator_id, year, deleted),
  CONSTRAINT chk_iyt_achieve CHECK (achieve_status IS NULL OR achieve_status IN ('NOT_STARTED','IN_PROGRESS','ACHIEVED','EXCEEDED','NOT_ACHIEVED','LAGGING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标年度目标与完成情况缓存';

-- =====================================================================
-- 模块四：B+ 任务
-- =====================================================================

-- B+ 任务（A+ × 执行单元；不可下分的 A 生成的执行任务也按 B+ 管理；
--  分解矩阵草稿即 status = PENDING_DISTRIBUTION 的本表数据，不另建草稿表）
CREATE TABLE task (
  id                   BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  plan_id              BIGINT       NOT NULL           COMMENT '计划，引用 plan.id',
  indicator_id         BIGINT       NOT NULL           COMMENT '所属指标（A+ 或不可下分的 A），引用 indicator.id',
  top_indicator_id     BIGINT       NOT NULL           COMMENT '冗余：所属 A 类指标，引用 indicator.id，便于汇总与数据权限',
  unit_id              BIGINT       NOT NULL           COMMENT '承接执行单元，引用 org_unit.id',
  code                 VARCHAR(48)  NOT NULL           COMMENT '任务编号，同一计划内唯一，格式 {指标编号}-{单元编码}，如 A01-2-C03',
  owner_id             BIGINT       NULL               COMMENT '学院项目负责人，引用 sys_user.id（下发前必填，50002）',
  status               VARCHAR(32)  NOT NULL DEFAULT 'PENDING_DISTRIBUTION' COMMENT '任务状态：PENDING_DISTRIBUTION 待下发 / DISTRIBUTED 已下发 / IN_PROGRESS 执行中 / TRANSFERRED_OUT 已全部转出 / CLOSED 已结束',
  distributed_at       DATETIME(3)  NULL               COMMENT '下发时间',
  distributed_by       BIGINT       NULL               COMMENT '下发操作人，引用 sys_user.id',
  accepted_at          DATETIME(3)  NULL               COMMENT '学院确认接收时间',
  accepted_by          BIGINT       NULL               COMMENT '确认接收人，引用 sys_user.id',
  source_adjustment_id BIGINT       NULL               COMMENT '转移时自动新建的任务记录来源调整单，引用 adjustment.id',
  version              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by           BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by           BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted              BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_indicator_unit (indicator_id, unit_id, deleted),
  UNIQUE KEY uk_task_code (plan_id, code, deleted),
  KEY idx_task_unit_status (unit_id, status),
  KEY idx_task_top_indicator (top_indicator_id),
  KEY idx_task_owner (owner_id),
  CONSTRAINT chk_task_status CHECK (status IN ('PENDING_DISTRIBUTION','DISTRIBUTED','IN_PROGRESS','TRANSFERRED_OUT','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='B+ 任务';

-- B+ 任务年度（年度目标、完成值缓存、年度状态）
CREATE TABLE task_year (
  id                      BIGINT        NOT NULL           COMMENT '主键，雪花 ID',
  task_id                 BIGINT        NOT NULL           COMMENT 'B+ 任务，引用 task.id',
  year                    SMALLINT      NOT NULL           COMMENT '年度',
  plan_id                 BIGINT        NOT NULL           COMMENT '冗余：计划，引用 plan.id',
  indicator_id            BIGINT        NOT NULL           COMMENT '冗余：指标，引用 indicator.id',
  unit_id                 BIGINT        NOT NULL           COMMENT '冗余：执行单元，引用 org_unit.id',
  target                  DECIMAL(20,6) NULL               COMMENT '当前生效的年度目标（BOOLEAN 口径：计划完成年存 1）',
  original_target         DECIMAL(20,6) NULL               COMMENT '下发时的原始年度目标（调整后不变，便于追溯）',
  target_version          INT           NOT NULL DEFAULT 1 COMMENT '目标版本号，每次调整生效后 +1',
  frozen_amount           DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT '调整审批中冻结的额度（防止同一额度重复申请）',
  actual_value            DECIMAL(20,6) NULL               COMMENT '缓存：年度完成值（审核通过口径）',
  actual_numerator        DECIMAL(20,4) NULL               COMMENT '缓存：RATIO 口径分子',
  actual_denominator      DECIMAL(20,4) NULL               COMMENT '缓存：RATIO 口径分母',
  actual_total            DECIMAL(20,4) NULL               COMMENT '缓存：STOCK / GROWTH 口径最新审核通过总量',
  is_achieved             TINYINT(1)    NULL               COMMENT '缓存：是否达标',
  completion_rate         DECIMAL(20,6) NULL               COMMENT '缓存：完成率（小数）',
  year_status             VARCHAR(32)   NOT NULL DEFAULT 'NOT_ACTIVATED' COMMENT '年度状态：NOT_ACTIVATED 未激活 / IN_PROGRESS 进行中 / PENDING_REVIEW 待审核 / ADJUSTING 调整审批中 / ACHIEVED 已达标 / NOT_ACHIEVED 未达标 / MAKEUP_PENDING 待补做 / MADE_UP 已补做',
  status_before_adjusting VARCHAR(32)   NULL               COMMENT '进入 ADJUSTING 前的状态，审批结束后恢复用',
  settled_at              DATETIME(3)   NULL               COMMENT '年度核算锁定时间',
  version                 INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at              DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by              BIGINT        NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at              DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by              BIGINT        NULL               COMMENT '更新人，引用 sys_user.id',
  deleted                 BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_year (task_id, year, deleted),
  KEY idx_task_year_unit_year (unit_id, year),
  KEY idx_task_year_indicator_year (indicator_id, year),
  KEY idx_task_year_plan_year (plan_id, year),
  KEY idx_task_year_status (year_status),
  CONSTRAINT chk_task_year_status CHECK (year_status IN ('NOT_ACTIVATED','IN_PROGRESS','PENDING_REVIEW','ADJUSTING','ACHIEVED','NOT_ACHIEVED','MAKEUP_PENDING','MADE_UP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='B+ 任务年度';

-- =====================================================================
-- 模块五：填报与审核
-- =====================================================================

-- 填报（MetricValue 用独立字段存储，不用 JSON，方便汇总；区分填报值与认定值）
CREATE TABLE submission (
  id                   BIGINT        NOT NULL           COMMENT '主键，雪花 ID',
  task_id              BIGINT        NOT NULL           COMMENT 'B+ 任务，引用 task.id',
  task_year_id         BIGINT        NOT NULL           COMMENT 'B+ 年度，引用 task_year.id',
  year                 SMALLINT      NOT NULL           COMMENT '填报年度',
  plan_id              BIGINT        NOT NULL           COMMENT '冗余：计划，引用 plan.id',
  indicator_id         BIGINT        NOT NULL           COMMENT '冗余：指标，引用 indicator.id',
  unit_id              BIGINT        NOT NULL           COMMENT '冗余：执行单元，引用 org_unit.id',
  stat_caliber         VARCHAR(32)   NOT NULL           COMMENT '统计口径快照（填报时指标的口径）',
  amount               DECIMAL(20,4) NULL               COMMENT '填报值：INCREMENT 本次新增数量',
  total                DECIMAL(20,4) NULL               COMMENT '填报值：STOCK / GROWTH 当前总量',
  numerator            DECIMAL(20,4) NULL               COMMENT '填报值：RATIO 分子',
  denominator          DECIMAL(20,4) NULL               COMMENT '填报值：RATIO 分母',
  percent              DECIMAL(20,6) NULL               COMMENT '填报值：DIRECT_PERCENT 直填百分比（小数）',
  achieved             TINYINT(1)    NULL               COMMENT '填报值：BOOLEAN 是否完成',
  approved_amount      DECIMAL(20,4) NULL               COMMENT '认定值：审核认定的增量（核减后）',
  approved_total       DECIMAL(20,4) NULL               COMMENT '认定值：审核认定的总量',
  approved_numerator   DECIMAL(20,4) NULL               COMMENT '认定值：审核认定的分子',
  approved_denominator DECIMAL(20,4) NULL               COMMENT '认定值：审核认定的分母',
  approved_percent     DECIMAL(20,6) NULL               COMMENT '认定值：审核认定的百分比',
  approved_achieved    TINYINT(1)    NULL               COMMENT '认定值：审核认定的是否完成',
  remark               VARCHAR(2000) NULL               COMMENT '完成情况说明',
  source_note          VARCHAR(2000) NULL               COMMENT '数据来源说明（直填百分比型必填）',
  status               VARCHAR(32)   NOT NULL DEFAULT 'DRAFT' COMMENT '填报状态：DRAFT 草稿 / PENDING_REVIEW 待审核 / APPROVED 已通过 / REJECTED 已驳回 / WITHDRAWN 已撤回',
  revision_no          INT           NOT NULL DEFAULT 0 COMMENT '提交次数（每次提交 +1，对应 submission_history / review）',
  submitted_at         DATETIME(3)   NULL               COMMENT '最近提交时间',
  submitted_by         BIGINT        NULL               COMMENT '最近提交人，引用 sys_user.id',
  reviewed_at          DATETIME(3)   NULL               COMMENT '最近审核时间',
  reviewer_id          BIGINT        NULL               COMMENT '最近审核人，引用 sys_user.id',
  is_proxy             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否管理员代填',
  proxy_reason         VARCHAR(500)  NULL               COMMENT '代填原因（敏感操作必填）',
  version              INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by           BIGINT        NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by           BIGINT        NULL               COMMENT '更新人，引用 sys_user.id',
  deleted              BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_submission_task_year (task_id, year),
  KEY idx_submission_status (status),
  KEY idx_submission_unit (unit_id),
  KEY idx_submission_indicator (indicator_id),
  KEY idx_submission_task_year_id (task_year_id),
  CONSTRAINT chk_submission_status CHECK (status IN ('DRAFT','PENDING_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT chk_submission_caliber CHECK (stat_caliber IN ('INCREMENT','STOCK','GROWTH','RATIO','DIRECT_PERCENT','BOOLEAN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='填报';

-- 填报历史（每次提交一份快照，驳回重报不丢旧版本；记录类：不带 version / deleted）
CREATE TABLE submission_history (
  id             BIGINT      NOT NULL             COMMENT '主键，雪花 ID',
  submission_id  BIGINT      NOT NULL             COMMENT '填报，引用 submission.id',
  revision_no    INT         NOT NULL             COMMENT '提交序号',
  value_json     JSON        NOT NULL             COMMENT '提交时的填报值快照（MetricValue）',
  file_ids_json  JSON        NOT NULL             COMMENT '提交时引用的文件 id 数组',
  submitted_at   DATETIME(3) NOT NULL             COMMENT '提交时间',
  submitted_by   BIGINT      NOT NULL             COMMENT '提交人，引用 sys_user.id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_submission_history (submission_id, revision_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='填报历史快照';

-- 审核记录（记录类：不带 version / deleted）
CREATE TABLE review (
  id                  BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  submission_id       BIGINT       NOT NULL           COMMENT '填报，引用 submission.id',
  revision_no         INT          NOT NULL           COMMENT '对应的提交序号',
  reviewer_id         BIGINT       NOT NULL           COMMENT '审核人，引用 sys_user.id',
  result              VARCHAR(32)  NOT NULL           COMMENT '审核结果：APPROVED 通过 / REJECTED 驳回',
  comment             VARCHAR(2000) NULL              COMMENT '审核意见（驳回必填，60004）',
  approved_value_json JSON         NULL               COMMENT '认定值快照（核减时记录原值与认定值）',
  is_proxy            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否管理员代审',
  proxy_reason        VARCHAR(500) NULL               COMMENT '代审原因（敏感操作必填）',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审核时间',
  PRIMARY KEY (id),
  KEY idx_review_submission (submission_id),
  KEY idx_review_reviewer (reviewer_id),
  CONSTRAINT chk_review_result CHECK (result IN ('APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核记录';

-- =====================================================================
-- 模块六：指标调整与审批
-- =====================================================================

-- 调整申请
CREATE TABLE adjustment (
  id              BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  plan_id         BIGINT       NOT NULL           COMMENT '计划，引用 plan.id',
  type            VARCHAR(32)  NOT NULL           COMMENT '调整类型：POSTPONE 延期 / TRANSFER 转移 / POSTPONE_TRANSFER 延期+转移 / TARGET_CHANGE 目标调整',
  task_id         BIGINT       NULL               COMMENT '转出的 B+ 任务，引用 task.id；TARGET_CHANGE 时可为空',
  indicator_id    BIGINT       NOT NULL           COMMENT '涉及的 A+（或目标调整的 A），引用 indicator.id',
  from_unit_id    BIGINT       NULL               COMMENT '转出执行单元，引用 org_unit.id',
  to_unit_id      BIGINT       NULL               COMMENT '接收执行单元，引用 org_unit.id',
  status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING_OWNER_REVIEW' COMMENT '调整状态：PENDING_OWNER_REVIEW 待初审 / PENDING_RECEIVER_CONFIRM 待接收方确认 / PENDING_LEADER_APPROVAL 待校领导审批 / APPROVED 已生效 / REJECTED 已驳回 / WITHDRAWN 已撤回',
  current_node    VARCHAR(32)  NULL               COMMENT '当前审批环节：OWNER_REVIEW / RECEIVER_CONFIRM / LEADER_APPROVAL；结束后为空',
  applicant_id    BIGINT       NOT NULL           COMMENT '申请人，引用 sys_user.id',
  reason          VARCHAR(2000) NOT NULL          COMMENT '调整原因',
  snapshot_before JSON         NULL               COMMENT '调整前涉及的全部 B+ 年度目标快照（原指标）',
  snapshot_after  JSON         NULL               COMMENT '调整后涉及的全部 B+ 年度目标快照（变更后指标）',
  effective_at    DATETIME(3)  NULL               COMMENT '生效时间',
  withdrawn_at    DATETIME(3)  NULL               COMMENT '撤回时间',
  version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by      BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by      BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted         BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_adjustment_task (task_id),
  KEY idx_adjustment_status (status),
  KEY idx_adjustment_applicant (applicant_id),
  KEY idx_adjustment_plan (plan_id),
  KEY idx_adjustment_indicator (indicator_id),
  CONSTRAINT chk_adjustment_type CHECK (type IN ('POSTPONE','TRANSFER','POSTPONE_TRANSFER','TARGET_CHANGE')),
  CONSTRAINT chk_adjustment_status CHECK (status IN ('PENDING_OWNER_REVIEW','PENDING_RECEIVER_CONFIRM','PENDING_LEADER_APPROVAL','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT chk_adjustment_node CHECK (current_node IS NULL OR current_node IN ('OWNER_REVIEW','RECEIVER_CONFIRM','LEADER_APPROVAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标调整申请';

-- 调整明细
CREATE TABLE adjustment_item (
  id                  BIGINT        NOT NULL           COMMENT '主键，雪花 ID',
  adjustment_id       BIGINT        NOT NULL           COMMENT '调整申请，引用 adjustment.id',
  from_task_id        BIGINT        NULL               COMMENT '转出 B+ 任务，引用 task.id；TARGET_CHANGE 时为空',
  from_year           SMALLINT      NULL               COMMENT '转出年度',
  to_task_id          BIGINT        NULL               COMMENT '接收 B+ 任务（生效时回填；接收方原无该 A+ 任务时为新建任务），引用 task.id',
  to_unit_id          BIGINT        NULL               COMMENT '接收执行单元，引用 org_unit.id',
  to_year             SMALLINT      NULL               COMMENT '接收年度',
  amount              DECIMAL(20,4) NULL               COMMENT '调整数量（POSTPONE / TRANSFER 类）',
  new_target          DECIMAL(20,6) NULL               COMMENT '新目标值（TARGET_CHANGE 使用）',
  target_indicator_id BIGINT        NULL               COMMENT '目标调整的指标，引用 indicator.id（TARGET_CHANGE 使用）',
  version             INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by          BIGINT        NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by          BIGINT        NULL               COMMENT '更新人，引用 sys_user.id',
  deleted             BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_adjustment_item_adjustment (adjustment_id),
  KEY idx_adjustment_item_from_task (from_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调整明细';

-- 审批环节（记录类：不带 deleted；adjustment_id + seq 联合唯一）
CREATE TABLE approval_step (
  id                   BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  adjustment_id        BIGINT       NOT NULL           COMMENT '调整申请，引用 adjustment.id',
  seq                  INT          NOT NULL           COMMENT '环节顺序号（从 1 开始）',
  node                 VARCHAR(32)  NOT NULL           COMMENT '审批环节：OWNER_REVIEW 校级负责人初审 / RECEIVER_CONFIRM 接收方确认 / LEADER_APPROVAL 校领导审批',
  expected_approver_id BIGINT       NULL               COMMENT '期望处理人，引用 sys_user.id；RECEIVER_CONFIRM 环节可为空（按接收单元负责人判断）',
  expected_unit_id     BIGINT       NULL               COMMENT '期望处理单元（RECEIVER_CONFIRM 环节），引用 org_unit.id',
  decision             VARCHAR(32)  NULL               COMMENT '审批决定：APPROVE 同意 / REJECT 驳回；未处理为空',
  comment              VARCHAR(2000) NULL              COMMENT '审批意见',
  decided_at           DATETIME(3)  NULL               COMMENT '处理时间',
  decided_by           BIGINT       NULL               COMMENT '处理人，引用 sys_user.id',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_approval_step (adjustment_id, seq),
  KEY idx_approval_step_todo (expected_approver_id, decision),
  KEY idx_approval_step_unit_todo (expected_unit_id, decision),
  CONSTRAINT chk_approval_node CHECK (node IN ('OWNER_REVIEW','RECEIVER_CONFIRM','LEADER_APPROVAL')),
  CONSTRAINT chk_approval_decision CHECK (decision IS NULL OR decision IN ('APPROVE','REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调整审批环节';

-- =====================================================================
-- 模块七：里程碑与补做
-- =====================================================================

-- 里程碑（挂在指标或任务上，二选一）
CREATE TABLE milestone (
  id           BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  plan_id      BIGINT       NOT NULL           COMMENT '计划，引用 plan.id',
  indicator_id BIGINT       NULL               COMMENT '关联指标，引用 indicator.id（与 task_id 二选一）',
  task_id      BIGINT       NULL               COMMENT '关联任务，引用 task.id（与 indicator_id 二选一）',
  name         VARCHAR(200) NOT NULL           COMMENT '里程碑名称',
  due_date     DATE         NOT NULL           COMMENT '计划完成日期',
  deliverable  VARCHAR(2000) NULL              COMMENT '交付物要求',
  related_year SMALLINT     NULL               COMMENT '关联的年度目标',
  status       VARCHAR(32)  NOT NULL DEFAULT 'NOT_STARTED' COMMENT '状态：NOT_STARTED 未开始 / IN_PROGRESS 进行中 / COMPLETED 已完成 / OVERDUE 逾期 / POSTPONED 已延期',
  completed_at DATETIME(3)  NULL               COMMENT '完成时间',
  version      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by   BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by   BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted      BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_milestone_indicator (indicator_id),
  KEY idx_milestone_task (task_id),
  CONSTRAINT chk_milestone_owner CHECK ((indicator_id IS NOT NULL) <> (task_id IS NOT NULL)),
  CONSTRAINT chk_milestone_status CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED','OVERDUE','POSTPONED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='里程碑';

-- 待补做（年度未达标缺口 → 后续年度补做）
CREATE TABLE makeup (
  id                BIGINT        NOT NULL           COMMENT '主键，雪花 ID',
  task_id           BIGINT        NOT NULL           COMMENT 'B+ 任务，引用 task.id',
  from_task_year_id BIGINT        NOT NULL           COMMENT '未达标的 B+ 年度，引用 task_year.id',
  from_year         SMALLINT      NOT NULL           COMMENT '未达标年度',
  to_year           SMALLINT      NOT NULL           COMMENT '补做年度',
  gap_amount        DECIMAL(20,4) NOT NULL           COMMENT '缺口数量',
  made_up_amount    DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT '已补做数量',
  status            VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待补做 / DONE 已补做 / CANCELLED 已取消',
  adjustment_id     BIGINT        NULL               COMMENT '关联的延期调整单，引用 adjustment.id',
  version           INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by        BIGINT        NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by        BIGINT        NULL               COMMENT '更新人，引用 sys_user.id',
  deleted           BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_makeup_task (task_id),
  KEY idx_makeup_status (status),
  CONSTRAINT chk_makeup_status CHECK (status IN ('PENDING','DONE','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='待补做';

-- =====================================================================
-- 模块八：文件
-- =====================================================================

-- 文件
CREATE TABLE file_info (
  id               BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  file_name        VARCHAR(255) NOT NULL           COMMENT '原始文件名',
  ext              VARCHAR(16)  NOT NULL           COMMENT '扩展名（小写，不含点）',
  size             BIGINT       NOT NULL           COMMENT '文件大小（字节）',
  mime_type        VARCHAR(128) NOT NULL           COMMENT 'MIME 类型',
  category         VARCHAR(32)  NOT NULL           COMMENT '分类：DOCUMENT 文档 / PDF / IMAGE 图片 / VIDEO 视频 / ARCHIVE 压缩包',
  biz_type         VARCHAR(32)  NOT NULL           COMMENT '用途：SUBMISSION_ATTACHMENT 填报材料 / ADJUSTMENT_ATTACHMENT 调整附件 / MILESTONE_ATTACHMENT 里程碑附件 / IMPORT_FILE 导入文件',
  status           VARCHAR(32)  NOT NULL DEFAULT 'TEMP' COMMENT '状态：TEMP 临时（超 24h 未绑定自动清理）/ BOUND 已绑定',
  storage_type     VARCHAR(32)  NOT NULL DEFAULT 'LOCAL' COMMENT '存储类型：LOCAL 本地 / MINIO 对象存储',
  storage_key      VARCHAR(512) NOT NULL           COMMENT '存储键（路径或对象键）',
  sha256           CHAR(64)     NOT NULL           COMMENT 'SHA-256（秒传与完整性校验）',
  transcode_status VARCHAR(32)  NULL               COMMENT '视频转码状态：PROCESSING 转码中 / SUCCEEDED 成功 / FAILED 失败',
  preview_key      VARCHAR(512) NULL               COMMENT '预览转换产物的存储键',
  uploaded_by      BIGINT       NOT NULL           COMMENT '上传人，引用 sys_user.id',
  uploaded_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
  bound_at         DATETIME(3)  NULL               COMMENT '绑定时间',
  version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by       BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by       BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted          BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  KEY idx_file_sha256 (sha256),
  KEY idx_file_status (status),
  KEY idx_file_uploaded_by (uploaded_by),
  CONSTRAINT chk_file_category CHECK (category IN ('DOCUMENT','PDF','IMAGE','VIDEO','ARCHIVE')),
  CONSTRAINT chk_file_biz_type CHECK (biz_type IN ('SUBMISSION_ATTACHMENT','ADJUSTMENT_ATTACHMENT','MILESTONE_ATTACHMENT','IMPORT_FILE')),
  CONSTRAINT chk_file_status CHECK (status IN ('TEMP','BOUND')),
  CONSTRAINT chk_file_storage CHECK (storage_type IN ('LOCAL','MINIO')),
  CONSTRAINT chk_file_transcode CHECK (transcode_status IS NULL OR transcode_status IN ('PROCESSING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件';

-- 分片上传会话（记录类：不带 version / deleted）
CREATE TABLE file_upload_session (
  id             BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  upload_id      VARCHAR(64)  NOT NULL           COMMENT '上传会话标识（UUID）',
  file_name      VARCHAR(255) NOT NULL           COMMENT '文件名',
  size           BIGINT       NOT NULL           COMMENT '文件总大小（字节）',
  mime_type      VARCHAR(128) NOT NULL           COMMENT 'MIME 类型',
  sha256         CHAR(64)     NOT NULL           COMMENT '整文件 SHA-256',
  part_size      INT          NOT NULL           COMMENT '分片大小（字节，默认 5MB）',
  part_count     INT          NOT NULL           COMMENT '分片总数',
  uploaded_parts JSON         NOT NULL           COMMENT '已上传分片编号数组（断点续传）',
  status         VARCHAR(32)  NOT NULL DEFAULT 'UPLOADING' COMMENT '会话状态：UPLOADING 上传中 / COMPLETED 已完成 / EXPIRED 已过期',
  expires_at     DATETIME(3)  NOT NULL           COMMENT '会话过期时间',
  created_by     BIGINT       NOT NULL           COMMENT '发起人，引用 sys_user.id',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_upload_session (upload_id),
  KEY idx_upload_session_sha256 (sha256),
  CONSTRAINT chk_upload_session_status CHECK (status IN ('UPLOADING','COMPLETED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分片上传会话';

-- 业务与文件的关联（填报材料、调整附件、里程碑附件统一关联；纯关联表）
CREATE TABLE file_ref (
  id         BIGINT      NOT NULL             COMMENT '主键，雪花 ID',
  biz_type   VARCHAR(32) NOT NULL             COMMENT '业务类型：SUBMISSION / ADJUSTMENT / MILESTONE',
  biz_id     BIGINT      NOT NULL             COMMENT '业务数据 id',
  file_id    BIGINT      NOT NULL             COMMENT '文件，引用 file_info.id',
  sort       INT         NOT NULL DEFAULT 0   COMMENT '排序号',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_ref (biz_type, biz_id, file_id),
  KEY idx_file_ref_file (file_id),
  CONSTRAINT chk_file_ref_biz CHECK (biz_type IN ('SUBMISSION','ADJUSTMENT','MILESTONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务文件关联';

-- =====================================================================
-- 模块九：系统与支撑
-- =====================================================================

-- 审计日志（只能追加：触发器禁止 UPDATE / DELETE；应用账号只授 INSERT + SELECT）
CREATE TABLE audit_log (
  id              BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  occurred_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间',
  operator_id     BIGINT       NULL               COMMENT '操作人，引用 sys_user.id；系统操作为空',
  operator_name   VARCHAR(64)  NOT NULL           COMMENT '操作人姓名快照',
  roles           JSON         NULL               COMMENT '操作人当时的角色数组',
  unit_id         BIGINT       NULL               COMMENT '操作人所属单元，引用 org_unit.id',
  plan_id         BIGINT       NULL               COMMENT '相关计划，引用 plan.id',
  action          VARCHAR(32)  NOT NULL           COMMENT '动作：LOGIN / CREATE / UPDATE / DELETE / IMPORT / EXPORT / FINALIZE / DISTRIBUTE / ACCEPT / SUBMIT / WITHDRAW / REVIEW / ADJUST_APPLY / ADJUST_DECIDE / SETTLE / UNLOCK / PERMISSION_CHANGE / FILE_DOWNLOAD',
  resource_type   VARCHAR(64)  NOT NULL           COMMENT '资源类型，如 indicator / task / submission / adjustment',
  resource_id     BIGINT       NULL               COMMENT '资源 id',
  resource_code   VARCHAR(64)  NULL               COMMENT '资源编号，如 A01-2-C03',
  before_json     JSON         NULL               COMMENT '变更前数据',
  after_json      JSON         NULL               COMMENT '变更后数据',
  stakeholder_ids JSON         NULL               COMMENT '相关干系人 user id 数组',
  reason          VARCHAR(500) NULL               COMMENT '操作原因（敏感操作必填）',
  client_type     VARCHAR(32)  NULL               COMMENT '客户端：WEB / FEISHU_PC / FEISHU_MOBILE / SCREEN',
  ip              VARCHAR(64)  NULL               COMMENT '客户端 IP',
  user_agent      VARCHAR(512) NULL               COMMENT '客户端 UA',
  request_id      VARCHAR(64)  NULL               COMMENT '请求追踪 ID（X-Request-Id）',
  PRIMARY KEY (id),
  KEY idx_audit_occurred_at (occurred_at),
  KEY idx_audit_action (action),
  KEY idx_audit_unit (unit_id),
  KEY idx_audit_plan (plan_id),
  KEY idx_audit_resource (resource_type, resource_id),
  KEY idx_audit_request (request_id),
  CONSTRAINT chk_audit_action CHECK (action IN ('LOGIN','CREATE','UPDATE','DELETE','IMPORT','EXPORT','FINALIZE','DISTRIBUTE','ACCEPT','SUBMIT','WITHDRAW','REVIEW','ADJUST_APPLY','ADJUST_DECIDE','SETTLE','UNLOCK','PERMISSION_CHANGE','FILE_DOWNLOAD')),
  CONSTRAINT chk_audit_client CHECK (client_type IS NULL OR client_type IN ('WEB','FEISHU_PC','FEISHU_MOBILE','SCREEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计日志（只追加）';

-- 审计日志保护触发器：禁止修改与删除
CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only: UPDATE not allowed';

CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only: DELETE not allowed';

-- 幂等记录（Idempotency-Key 保存 24 小时；记录类：不带 version / deleted）
CREATE TABLE idempotency_record (
  id              BIGINT      NOT NULL             COMMENT '主键，雪花 ID',
  user_id         BIGINT      NOT NULL             COMMENT '提交人，引用 sys_user.id',
  idem_key        VARCHAR(64) NOT NULL             COMMENT '幂等键（UUID）',
  method_path     VARCHAR(255) NOT NULL            COMMENT '请求方法与路径，如 POST /api/v1/plans/1/distribute',
  request_hash    CHAR(64)    NOT NULL             COMMENT '请求体 SHA-256（同 Key 不同请求体返回 409+10004）',
  response_status INT         NOT NULL             COMMENT '首次响应的 HTTP 状态码',
  response_body   JSON        NULL                 COMMENT '首次响应体（重复请求直接返回）',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  expires_at      DATETIME(3) NOT NULL             COMMENT '过期时间（创建后 24 小时）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotency (user_id, idem_key),
  KEY idx_idempotency_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='幂等记录';

-- 刷新令牌（记录类：不带 version / deleted；存哈希不存明文）
CREATE TABLE refresh_token (
  id             BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  user_id        BIGINT       NOT NULL           COMMENT '用户，引用 sys_user.id',
  token_hash     CHAR(64)     NOT NULL           COMMENT 'refreshToken 的 SHA-256',
  expires_at     DATETIME(3)  NOT NULL           COMMENT '过期时间（签发后 7 天）',
  revoked_at     DATETIME(3)  NULL               COMMENT '吊销时间（退出登录 / 被替换时）',
  replaced_by_id BIGINT       NULL               COMMENT '替换它的新令牌 id（旋转刷新），引用 refresh_token.id',
  client_type    VARCHAR(32)  NULL               COMMENT '客户端类型',
  ip             VARCHAR(64)  NULL               COMMENT '签发时 IP',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '签发时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_token_hash (token_hash),
  KEY idx_refresh_token_user (user_id),
  CONSTRAINT chk_refresh_token_client CHECK (client_type IS NULL OR client_type IN ('WEB','FEISHU_PC','FEISHU_MOBILE','SCREEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='刷新令牌';

-- 站内消息（记录类：不带 version / deleted）
CREATE TABLE notification (
  id                 BIGINT      NOT NULL             COMMENT '主键，雪花 ID',
  user_id            BIGINT      NOT NULL             COMMENT '接收人，引用 sys_user.id',
  type               VARCHAR(32) NOT NULL             COMMENT '通知类型：TASK_DISTRIBUTED 任务下发 / DEADLINE_REMINDER 截止提醒 / REVIEW_RESULT 审核结果 / ADJUSTMENT_TODO 调整待办 / ADJUSTMENT_RESULT 调整结果 / WARNING 预警',
  title              VARCHAR(200) NOT NULL            COMMENT '标题',
  content            VARCHAR(2000) NOT NULL           COMMENT '内容',
  biz_type           VARCHAR(64) NULL                 COMMENT '关联业务类型',
  biz_id             BIGINT      NULL                 COMMENT '关联业务 id',
  link               VARCHAR(512) NULL                COMMENT '跳转链接（飞书 AppLink / 站内路由）',
  read_at            DATETIME(3) NULL                 COMMENT '阅读时间；未读为空',
  feishu_status      VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '飞书推送状态：PENDING 待推送 / SENT 已推送 / FAILED 推送失败 / SKIPPED 无需推送',
  feishu_retry_count INT         NOT NULL DEFAULT 0   COMMENT '飞书推送已重试次数（指数退避最多 3 次）',
  created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_notification_user_read (user_id, read_at),
  KEY idx_notification_feishu_status (feishu_status),
  CONSTRAINT chk_notification_type CHECK (type IN ('TASK_DISTRIBUTED','DEADLINE_REMINDER','REVIEW_RESULT','ADJUSTMENT_TODO','ADJUSTMENT_RESULT','WARNING')),
  CONSTRAINT chk_notification_feishu CHECK (feishu_status IN ('PENDING','SENT','FAILED','SKIPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内消息';

-- 异步导出任务
CREATE TABLE export_job (
  id            BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  type          VARCHAR(32)  NOT NULL           COMMENT '导出类型：INDICATORS 指标体系 / TASKS B+任务 / STATS_REPORT 统计报表 / SETTLEMENT 核算结果 / AUDIT_LOGS 操作日志',
  params        JSON         NOT NULL           COMMENT '导出参数（过滤条件）',
  status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING 排队中 / RUNNING 执行中 / SUCCEEDED 成功 / FAILED 失败',
  file_id       BIGINT       NULL               COMMENT '结果文件，引用 file_info.id',
  error_message VARCHAR(2000) NULL              COMMENT '失败原因',
  created_by    BIGINT       NOT NULL           COMMENT '创建人，引用 sys_user.id',
  started_at    DATETIME(3)  NULL               COMMENT '开始执行时间',
  finished_at   DATETIME(3)  NULL               COMMENT '完成时间',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_export_job_creator (created_by),
  KEY idx_export_job_status (status),
  CONSTRAINT chk_export_type CHECK (type IN ('INDICATORS','TASKS','STATS_REPORT','SETTLEMENT','AUDIT_LOGS')),
  CONSTRAINT chk_export_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异步导出任务';

-- 飞书事件去重（记录类：不带 version / deleted）
CREATE TABLE feishu_event (
  id           BIGINT      NOT NULL             COMMENT '主键，雪花 ID',
  event_id     VARCHAR(64) NOT NULL             COMMENT '飞书事件 id（用于去重）',
  event_type   VARCHAR(128) NOT NULL            COMMENT '事件类型',
  payload      JSON        NOT NULL             COMMENT '事件报文',
  received_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '接收时间',
  processed_at DATETIME(3) NULL                 COMMENT '处理完成时间',
  status       VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT '处理状态：RECEIVED 已接收 / PROCESSED 已处理 / FAILED 处理失败',
  PRIMARY KEY (id),
  UNIQUE KEY uk_feishu_event (event_id),
  CONSTRAINT chk_feishu_event_status CHECK (status IN ('RECEIVED','PROCESSED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书事件去重';

-- 字典
CREATE TABLE dict_item (
  id         BIGINT       NOT NULL           COMMENT '主键，雪花 ID',
  dict_type  VARCHAR(64)  NOT NULL           COMMENT '字典类型，如 UNIT_LABEL 计量单位 / MATERIAL_TYPE 材料类型',
  code       VARCHAR(64)  NOT NULL           COMMENT '字典编码',
  label      VARCHAR(200) NOT NULL           COMMENT '显示名',
  sort       INT          NOT NULL DEFAULT 0 COMMENT '排序号',
  is_enabled TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  version    INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by BIGINT       NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by BIGINT       NULL               COMMENT '更新人，引用 sys_user.id',
  deleted    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dict (dict_type, code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典';

-- 系统参数
CREATE TABLE sys_setting (
  id            BIGINT        NOT NULL           COMMENT '主键，雪花 ID',
  setting_key   VARCHAR(128)  NOT NULL           COMMENT '参数键',
  setting_value VARCHAR(2000) NOT NULL           COMMENT '参数值',
  value_type    VARCHAR(32)   NOT NULL DEFAULT 'STRING' COMMENT '值类型：STRING / NUMBER / BOOLEAN / JSON',
  description   VARCHAR(500)  NULL               COMMENT '参数说明',
  version       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by    BIGINT        NULL               COMMENT '创建人，引用 sys_user.id',
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by    BIGINT        NULL               COMMENT '更新人，引用 sys_user.id',
  deleted       BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_setting (setting_key, deleted),
  CONSTRAINT chk_setting_value_type CHECK (value_type IN ('STRING','NUMBER','BOOLEAN','JSON'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统参数';
