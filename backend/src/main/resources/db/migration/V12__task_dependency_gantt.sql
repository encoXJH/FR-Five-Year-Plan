-- 甘特图任务依赖与计划里程碑查询索引
CREATE TABLE task_dependency (
  id                  BIGINT      NOT NULL COMMENT '主键，雪花 ID',
  plan_id             BIGINT      NOT NULL COMMENT '计划，引用 plan.id',
  predecessor_task_id BIGINT      NOT NULL COMMENT '前置 B 任务，引用 task.id',
  successor_task_id   BIGINT      NOT NULL COMMENT '后续 B 任务，引用 task.id',
  dependency_type     VARCHAR(16) NOT NULL DEFAULT 'FS' COMMENT '依赖类型：FS 完成到开始',
  version             INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  created_by          BIGINT      NULL COMMENT '创建人，引用 sys_user.id',
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  updated_by          BIGINT      NULL COMMENT '更新人，引用 sys_user.id',
  deleted             BIGINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，否则为本行 id',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_dependency_active (plan_id, predecessor_task_id, successor_task_id, deleted),
  KEY idx_task_dependency_plan (plan_id, deleted),
  KEY idx_task_dependency_predecessor (predecessor_task_id, deleted),
  KEY idx_task_dependency_successor (successor_task_id, deleted),
  CONSTRAINT chk_task_dependency_type CHECK (dependency_type = 'FS'),
  CONSTRAINT chk_task_dependency_not_self CHECK (predecessor_task_id <> successor_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='B 任务 FS 依赖（甘特图）';

CREATE INDEX idx_milestone_plan_gantt
    ON milestone (plan_id, deleted, due_date, id);
