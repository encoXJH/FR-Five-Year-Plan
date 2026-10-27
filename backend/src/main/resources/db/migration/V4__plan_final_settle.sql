-- V4：五年核算标记（阶段 6）。plan 增加五年核算时间与操作人，不改 V1/V2/V3 已有结构。
ALTER TABLE plan
  ADD COLUMN final_settled_at DATETIME(3) NULL COMMENT '五年核算时间（规划期结束后统一核算）' AFTER finalized_by,
  ADD COLUMN final_settled_by BIGINT       NULL COMMENT '五年核算操作人，引用 sys_user.id' AFTER final_settled_at;
