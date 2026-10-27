-- S / A / A+ / B 四层任务体系；保留旧任务及编号，历史执行数据转为只读
USE metrics_db;
SET NAMES utf8mb4;
SET time_zone = '+08:00';

ALTER TABLE direction
  ADD COLUMN leader_id BIGINT NULL COMMENT 'S 级总负责人，引用 sys_user.id，须具有 LEADER 角色' AFTER major_group_id,
  ADD KEY idx_direction_leader (leader_id);

ALTER TABLE org_unit
  ADD COLUMN is_b_assignable TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否可承接新 B 任务' AFTER is_enabled;
UPDATE org_unit SET is_b_assignable = 1 WHERE code IN ('C01','C02','C03','C04','C05','C06','C07','C08');

ALTER TABLE task
  ADD COLUMN is_historical TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否体系改造前的只读历史任务' AFTER source_adjustment_id,
  ADD KEY idx_task_history (plan_id, is_historical);

ALTER TABLE indicator
  MODIFY COLUMN direction_id BIGINT NULL COMMENT '所属 S，草稿 A 可空，定稿前必填',
  MODIFY COLUMN measure_type VARCHAR(32) NULL COMMENT '计量类型；不可量化 A 可为空',
  MODIFY COLUMN stat_caliber VARCHAR(32) NULL COMMENT '统计口径；不可量化 A 可为空',
  MODIFY COLUMN unit_label VARCHAR(32) NULL COMMENT '计量单位；不可量化 A 可为空',
  MODIFY COLUMN target_direction VARCHAR(32) NULL DEFAULT 'HIGHER_BETTER' COMMENT '达成方向；不可量化 A 可为空',
  RENAME COLUMN is_divisible TO is_quantifiable,
  DROP COLUMN undertake_unit_id;

-- 旧模板快照同步改用 isQuantifiable，并移除已废弃的 A 直承单元字段
UPDATE template
SET content_json = CAST(
    REPLACE(
        REGEXP_REPLACE(
            REGEXP_REPLACE(CAST(content_json AS CHAR),
                ',[[:space:]]*"undertakeUnitCode"[[:space:]]*:[[:space:]]*("[^"]*"|null)', ''),
            '"undertakeUnitCode"[[:space:]]*:[[:space:]]*("[^"]*"|null)[[:space:]]*,[[:space:]]*', ''),
        '"isDivisible"', '"isQuantifiable"') AS JSON)
WHERE deleted = 0
  AND (CAST(content_json AS CHAR) LIKE '%"isDivisible"%'
       OR CAST(content_json AS CHAR) LIKE '%"undertakeUnitCode"%');

UPDATE task t
JOIN org_unit u ON u.id = t.unit_id
JOIN indicator i ON i.id = t.indicator_id
SET t.is_historical = 1
WHERE u.code NOT IN ('C01','C02','C03','C04','C05','C06','C07','C08')
   OR i.level = 'A';

-- 旧“不可下分 A”具有量化目标和直承任务，不等同于新体系的非量化 A
UPDATE indicator i
SET i.is_quantifiable = 1
WHERE i.level = 'A'
  AND EXISTS (SELECT 1 FROM task t WHERE t.indicator_id = i.id AND t.deleted = 0);

-- 在途填报逐笔终止，保留原提交和审核快照，不自动审核通过
UPDATE submission s
JOIN task t ON t.id = s.task_id
SET s.status = 'WITHDRAWN',
    s.remark = CONCAT_WS('；', NULLIF(s.remark, ''), '任务层级体系调整：历史任务转为只读，本填报终止'),
    s.updated_by = 201,
    s.version = s.version + 1
WHERE t.is_historical = 1
  AND s.status IN ('DRAFT','PENDING_REVIEW','REJECTED')
  AND s.deleted = 0;

-- 在途调整逐笔驳回并记录体系调整原因
UPDATE adjustment a
SET a.status = 'REJECTED',
    a.current_node = NULL,
    a.reason = CONCAT_WS('；', NULLIF(a.reason, ''), '任务层级体系调整：历史任务转为只读，本申请终止'),
    a.updated_by = 201,
    a.version = a.version + 1
WHERE a.deleted = 0
  AND a.status IN ('PENDING_OWNER_REVIEW','PENDING_RECEIVER_CONFIRM','PENDING_LEADER_APPROVAL')
  AND (EXISTS (SELECT 1 FROM task t WHERE t.id = a.task_id AND t.is_historical = 1)
       OR EXISTS (SELECT 1 FROM adjustment_item ai
                  JOIN task t ON t.id = ai.from_task_id
                  WHERE ai.adjustment_id = a.id AND ai.deleted = 0 AND t.is_historical = 1));

UPDATE approval_step st
JOIN adjustment a ON a.id = st.adjustment_id
SET st.decision = 'REJECT',
    st.comment = '任务层级体系调整：历史任务转为只读，本申请终止',
    st.decided_at = CURRENT_TIMESTAMP(3),
    st.decided_by = 201
WHERE a.status = 'REJECTED'
  AND a.reason LIKE '%任务层级体系调整：历史任务转为只读，本申请终止%'
  AND st.decision IS NULL;

UPDATE task_year ty
JOIN task t ON t.id = ty.task_id
SET ty.frozen_amount = 0,
    ty.year_status = CASE WHEN ty.year_status = 'ADJUSTING'
                          THEN COALESCE(ty.status_before_adjusting, 'IN_PROGRESS')
                          ELSE ty.year_status END,
    ty.status_before_adjusting = NULL,
    ty.version = ty.version + 1
WHERE t.is_historical = 1
  AND ty.deleted = 0
  AND (ty.frozen_amount <> 0 OR ty.year_status = 'ADJUSTING');
