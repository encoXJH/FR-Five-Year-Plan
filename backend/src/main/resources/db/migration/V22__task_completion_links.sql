CREATE TABLE task_completion_link (
  id                  BIGINT       NOT NULL COMMENT '主键，雪花 ID',
  task_a_id           BIGINT       NOT NULL COMMENT '关联任务 A，引用 task.id；与 task_b_id 按 ID 升序存储',
  task_b_id           BIGINT       NOT NULL COMMENT '关联任务 B，引用 task.id；与 task_a_id 按 ID 升序存储',
  source_note         VARCHAR(500) NULL COMMENT '关联来源说明',
  version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by          BIGINT       NULL COMMENT '创建人，引用 sys_user.id',
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by          BIGINT       NULL COMMENT '更新人，引用 sys_user.id',
  deleted             BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_completion_link_active (task_a_id, task_b_id, deleted),
  KEY idx_task_completion_link_a (task_a_id, deleted),
  KEY idx_task_completion_link_b (task_b_id, deleted),
  CONSTRAINT chk_task_completion_link_canonical CHECK (task_a_id < task_b_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跨计划任务完成状态关联';

ALTER TABLE task_year
  ADD COLUMN linked_achieved TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否由直接关联任务同步达标' AFTER completion_rate,
  ADD COLUMN linked_source_task_year_id BIGINT NULL COMMENT '同步达标的来源任务年度，引用 task_year.id' AFTER linked_achieved,
  ADD KEY idx_task_year_linked_source (linked_source_task_year_id);
