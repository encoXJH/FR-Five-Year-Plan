# 五年计划指标管理平台 · 数据库设计 Prompt

> 用法：
> 1. 把三份文档放到项目的 `docs/` 目录下：`五年计划指标管理平台-需求文档.md`、`API统一约定文档.md`、`界面设计规范.md`。
> 2. 把下面整段提示词（从“你是资深数据库架构师”开始）粘贴给 AI。
> 3. AI 完成后，按文末的“验收清单”检查。之后用《开发Prompt.md》进入阶段 1 时，会直接使用这份数据库设计，不会重新设计。

---

````text
你是资深数据库架构师。请为“福州软件职业技术学院 · 五年计划指标管理平台”完成【数据库详细设计】，并在 MySQL 8.0 上实际执行验证。

## 一、开始前必须阅读
- docs/五年计划指标管理平台-需求文档.md（PRD）：重点看第 2 章（术语、2.1 执行单元清单）、第 3 章（角色与数据权限）、第 4 章（A→A+→B+ 层级、计量类型、统计口径、计算与汇总规则、4.5 守恒校验）、第 5 章（功能需求，尤其 5.3 分解下发、5.4 填报、5.5 审核、5.6 延期/转移、5.7 里程碑、5.8 核算、5.10 日志）、第 6 章（状态机）、第 8 章（概念数据模型）。
- docs/API统一约定文档.md：重点看第 3 章（数据类型、MetricValue、YearTarget）、第 5.6–5.7 节（幂等、乐观锁）、第 8 章（文件）、第 9 章（全部枚举）、第 10 章（审计日志字段）。
文档之间有冲突时，以 PRD 为准。文档没写到的地方，自行做合理决定，并写进设计文档的“设计决策”一节。

## 二、技术前提
- MySQL 8.0；引擎 InnoDB；字符集 utf8mb4，排序规则 utf8mb4_0900_ai_ci；时区 +08:00。
- 后端是 Spring Boot 3 + MyBatis-Plus，用 Flyway 管理数据库迁移。
- 系统规模：全校教职工约 1000 人；每个计划约 300 条 A/A+ 指标；B+ 任务每个计划约 3000 条；五年累计填报约 5 万条；审计日志约 100 万条。

## 三、交付物（全部要产出）
1. docs/数据库设计.md，包含：
   - 设计原则与命名规范；
   - 设计决策（每条写明：问题 / 方案 / 理由）；
   - ER 图（Mermaid erDiagram，可以按模块拆成多张）；
   - 表清单（表名、中文名、所属模块、预估数据量）；
   - 每张表的字段字典（字段、类型、是否可空、默认值、说明、对应的枚举）；
   - 索引说明（每个索引服务于哪个查询）；
   - 业务规则在数据层的落实方式（见第六节）；
   - 典型查询 SQL；
   - 数据库账号权限与备份建议；
   - 新增枚举清单（见第七节）。
2. backend/src/main/resources/db/migration/V1__init.sql：全部建表语句。
3. backend/src/main/resources/db/migration/V2__seed.sql：基础数据（执行单元、字典、系统参数、开发测试账号）。
4. db/demo/demo_data.sql：一套完整的演示数据。**这个文件不是 Flyway 迁移，生产环境不执行。**
5. db/demo/typical_queries.sql：第六节、第八节要求的典型查询，并在每条查询前用注释写明预期结果。
如果 backend/ 目录还不存在，直接按上面的路径创建目录。

## 四、通用规范
1. 表名和字段名都用小写下划线（snake_case），表名用单数；不加前缀，只有系统表用 sys_ 前缀（sys_user、sys_setting）。
2. 主键 id 用 BIGINT（雪花 ID，由应用生成），**不使用 AUTO_INCREMENT**。
3. 业务表都要有以下通用字段：
   - version INT NOT NULL DEFAULT 0（乐观锁）
   - created_at DATETIME(3)、created_by BIGINT、updated_at DATETIME(3)、updated_by BIGINT
   - deleted BIGINT NOT NULL DEFAULT 0（逻辑删除。未删除时为 0，删除时写入本行 id。这样唯一索引带上 deleted 后，删除的数据不会和新数据冲突）
   以下几类表按需取舍，并在文档中说明：日志类、记录类（audit_log、idempotency_record、feishu_event 等）、纯关联表。
4. 字段类型：
   - 数量类（amount、分子、分母、总量）：DECIMAL(20,4)
   - 目标值和比率类（target、rate、percent，可能存放 0.15 这类小数）：DECIMAL(20,6)
   - 布尔：TINYINT(1)，字段名用 is_ / has_ 开头
   - 枚举：VARCHAR(32)，取值与 API 约定第 9 章完全一致（全大写加下划线）。**不使用 MySQL 的 ENUM 类型**；关键枚举字段加 CHECK 约束。
   - 年度：SMALLINT；日期：DATE；时间：DATETIME(3)
   - JSON 数据用 MySQL JSON 类型
   - 长文本：说明类 VARCHAR(2000)，名称类 VARCHAR(200)
5. **不建物理外键**（逻辑外键，由应用保证），但每个逻辑外键字段都必须建索引，并在字段字典中写明引用哪张表。
6. 每张表、每个字段都必须写中文 COMMENT。
7. 派生数据（完成值、完成率等缓存字段）必须能从原始数据重新计算出来。文档中要写明它的计算来源，以及在什么时机刷新。

## 五、表设计要求
下面是需要的表和关键字段。你可以补充字段或拆分表，但不能删减下面列出的能力；对设计的调整写进“设计决策”。

### 组织与用户
- org_unit 执行单元：code（唯一）、name、type（OrgUnitType）、sort、is_enabled。
- major_group 专业群：name、unit_id（所属学院）、owner_id、is_enabled。
- sys_user 用户：username（唯一）、password_hash、name、phone、email、unit_id、feishu_open_id（唯一，可空）、feishu_union_id、is_enabled、login_fail_count、locked_until、last_login_at。
- user_role 用户角色：user_id + role（Role 枚举），两者联合唯一。一个用户可以有多个角色。

### 计划与模板
- template 模板：name、description、content_json（结构快照：建设方向、指标定义、默认分解规则）、source_plan_id、is_enabled。
- plan 计划：name、start_year、end_year、baseline_year、settle_deadline（年度核算截止的月-日，如 01-15）、status（PlanStatus）、source（PlanSource）、template_id、source_plan_id、is_receiver_confirm_required（转移时是否需要接收方确认，PRD FR-M6-07）、finalized_at、finalized_by。CHECK：end_year >= start_year。
- plan_year 计划年度：plan_id + year 联合唯一；status（新增枚举 PlanYearStatus：NOT_ACTIVATED / ACTIVE / LOCKED）、activated_at/by、settled_at/by、last_unlock_reason。

### 指标体系（A / A+）
- direction 主要建设方向：plan_id、subject_type（SubjectType）、major_group_id（主体为专业群时必填）、name、sort。
- indicator 指标（A 和 A+ 存在同一张表）：
  - plan_id、direction_id、parent_id（A+ 指向所属的 A；A 为空）、level（A / A_PLUS）、code（同一计划内唯一，如 A01、A01-2）
  - name、nature、measure_type、stat_caliber、unit_label、target_direction
  - rollup_mode（只对 A 有效）、is_divisible（只对 A 有效）、undertake_unit_id（不可下分的 A 的承接单位，默认 S01）
  - description、evidence_requirement、min_file_count、baseline_value、total_target、owner_id、sort
  - status（新增枚举 IndicatorStatus：DRAFT / FINALIZED / DISTRIBUTED）
  约束（尽量用 CHECK 实现，做不到的写在文档中由应用保证）：level=A_PLUS 时 parent_id 不能为空；level=A 时 parent_id 必须为空；A+ 不能再有下级；measure_type 与 stat_caliber 的组合必须合法（API 约定第 9 章）。
- indicator_year_target 指标年度目标与完成情况缓存：indicator_id + year 联合唯一；target（BOOLEAN 口径时，计划完成的那一年存 1，其他年份为 NULL）；缓存字段 actual_value、actual_numerator、actual_denominator、actual_total、completion_rate、achieve_status、calculated_at；manual_actual_value 和 manual_note（A 的汇总方式为 MANUAL 时由人工认定）。

### B+ 任务
- task B+ 任务：
  - plan_id、indicator_id（A+，或不可下分的 A）、top_indicator_id（冗余所属 A，方便汇总和数据权限查询）、unit_id、code（同一计划内唯一，格式 {指标编号}-{单元编码}）
  - owner_id（学院项目负责人）、status（TaskStatus）
  - distributed_at/by、accepted_at/by、source_adjustment_id（转移时自动新建的任务，记录来源）
  - indicator_id + unit_id + deleted 联合唯一
  - **分解矩阵的草稿也存在 task 和 task_year 里，此时状态为 PENDING_DISTRIBUTION**，不另建草稿表。
- task_year B+ 年度：
  - task_id + year 联合唯一；冗余 plan_id、indicator_id、unit_id，方便汇总
  - target、original_target（下发时的原始目标）、target_version（每次调整生效后 +1）、frozen_amount（调整审批中冻结的额度）
  - 缓存：actual_value、actual_numerator、actual_denominator、actual_total、is_achieved、completion_rate
  - year_status（TaskYearStatus）、status_before_adjusting（进入 ADJUSTING 前的状态，审批结束后恢复用）、settled_at

### 填报与审核
- submission 填报：
  - task_id、task_year_id、year；冗余 plan_id、indicator_id、unit_id
  - stat_caliber（快照）
  - 填报值：amount、total、numerator、denominator、percent、achieved（对应 API 约定 3.5 节的 MetricValue，**用独立字段存储，不存 JSON**，方便汇总）
  - 认定值：approved_amount、approved_total、approved_numerator、approved_denominator、approved_percent、approved_achieved（审核时可以核减）
  - remark、source_note、status（SubmissionStatus）、revision_no、submitted_at/by、reviewed_at、reviewer_id、is_proxy（管理员代填）、proxy_reason
- submission_history 填报历史：每次提交时保存一份快照，包括 submission_id、revision_no、value_json、file_ids_json、submitted_at/by。被驳回后修改再提交，旧版本不会丢失。
- review 审核记录：submission_id、revision_no、reviewer_id、result（ReviewResult）、comment、approved_value_json、is_proxy、proxy_reason、created_at。

### 指标调整与审批
- adjustment 调整申请：
  - plan_id、type（AdjustmentType）、task_id（转出的 B+；TARGET_CHANGE 时可以为空）、indicator_id、from_unit_id、to_unit_id
  - status（AdjustmentStatus）、current_node（ApprovalNode）、applicant_id、reason
  - snapshot_before JSON、snapshot_after JSON（调整前后涉及的全部年度目标，满足“原指标 / 变更后指标两组数据”的要求）
  - effective_at、withdrawn_at
- adjustment_item 调整明细：adjustment_id、from_task_id、from_year、to_task_id（生效时回填）、to_unit_id、to_year、amount、new_target（TARGET_CHANGE 使用）、target_indicator_id（TARGET_CHANGE 使用）。
- approval_step 审批环节：adjustment_id、seq、node（ApprovalNode）、expected_approver_id（RECEIVER_CONFIRM 环节可以为空，改为按接收单元的负责人判断）、expected_unit_id、decision（Decision）、comment、decided_at、decided_by。adjustment_id + seq 联合唯一。

### 里程碑与补做
- milestone 里程碑：plan_id、indicator_id 或 task_id（二选一，用 CHECK 约束）、name、due_date、deliverable、related_year、status（MilestoneStatus）、completed_at。
- makeup 待补做：task_id、from_task_year_id、from_year、to_year、gap_amount、made_up_amount、status（新增枚举 MakeupStatus：PENDING / DONE / CANCELLED）、adjustment_id。

### 文件
- file_info 文件：file_name、ext、size、mime_type、category（FileCategory）、biz_type（FileBizType）、status（新增枚举 FileStatus：TEMP / BOUND）、storage_type（新增枚举 StorageType：LOCAL / MINIO）、storage_key、sha256（建索引，用于秒传）、transcode_status、preview_key、uploaded_by、uploaded_at、bound_at。
- file_upload_session 分片上传会话：upload_id（唯一）、file_name、size、mime_type、sha256、part_size、part_count、uploaded_parts JSON、status、expires_at、created_by。
- file_ref 业务与文件的关联：biz_type、biz_id、file_id、sort；biz_type + biz_id + file_id 联合唯一。填报材料、调整附件、里程碑附件统一用这张表关联。

### 系统与支撑
- audit_log 审计日志（只能追加）：occurred_at、operator_id、operator_name、roles、unit_id、plan_id、action（AuditAction）、resource_type、resource_id、resource_code、before_json、after_json、stakeholder_ids JSON、reason、client_type、ip、user_agent、request_id。
  - 要求 1：创建 BEFORE UPDATE 和 BEFORE DELETE 触发器，用 SIGNAL 报错，禁止修改和删除。
  - 要求 2：在文档中给出“应用账号对 audit_log 只授予 INSERT 和 SELECT 权限”的 GRANT 语句。
  - 要求 3：评估是否需要按年分区，并写出结论。
- idempotency_record 幂等记录：user_id + idem_key 联合唯一；method_path、request_hash、response_status、response_body JSON、created_at、expires_at。
- refresh_token：user_id、token_hash（唯一）、expires_at、revoked_at、replaced_by_id、client_type、ip。
- notification 站内消息：user_id、type（NotificationType）、title、content、biz_type、biz_id、link、read_at、feishu_status（新增枚举 PushStatus：PENDING / SENT / FAILED / SKIPPED）、feishu_retry_count、created_at。
- export_job 导出任务：type（ExportType）、params JSON、status（JobStatus）、file_id、error_message、created_by、started_at、finished_at。
- feishu_event 飞书事件去重：event_id（唯一）、event_type、payload JSON、received_at、processed_at、status。
- dict_item 字典：dict_type + code 联合唯一；label、sort、is_enabled。
- sys_setting 系统参数：setting_key（唯一）、setting_value、value_type、description。
- 待办不建表，由各业务表查询得出。在文档中给出“我的待办”的查询方案和需要的索引。

## 六、业务规则在数据层的落实（文档中每条都要写明“由数据库保证”还是“由应用保证”，以及具体怎么做）
1. 增量累计型（INCREMENT）的各年度目标之和 = total_target。
2. 同一 A+ 下各 B+ 每年的目标之和 = A+ 当年目标：草稿可以不平衡，下发时校验。写出平衡校验的 SQL。
3. A 的汇总方式为 AUTO_SUM 时，各 A+ 目标之和 = A 目标（定稿时校验）。
4. 完成值汇总 B+ → A+ → A：
   - INCREMENT：求和
   - STOCK：取当年最后一次审核通过的总量
   - GROWTH：今年总量 ÷ 去年总量 − 1（第一年的“去年”是基线值）
   - RATIO：Σ分子 ÷ Σ分母，**不能**平均各单元的百分比
   - BOOLEAN：是否完成
   每种口径都写出汇总 SQL，并说明哪些缓存字段在什么时机刷新（审核通过、调整生效、年度核算）。
5. 调整申请冻结额度：用 task_year.frozen_amount 加乐观锁，防止同一额度被重复申请。说明并发场景下如何保证正确。
6. 调整生效：在一个事务里完成哪些写操作（修改目标、target_version + 1、必要时新建接收方 task、写快照、写审计日志），按什么顺序加锁以避免死锁。
7. 年度锁定后禁止写入：如何判断（plan_year.status = LOCKED），由应用保证。
8. 审计日志不可修改：触发器 + 账号权限。

## 七、新增枚举
上面标注“新增枚举”的有：PlanYearStatus、IndicatorStatus、MakeupStatus、FileStatus、StorageType、PushStatus。如果你在设计中还新增了其他枚举，也一并汇总到 docs/数据库设计.md 的“新增枚举清单”中，并**追加到 docs/API统一约定文档.md 第 9 章的枚举表末尾**，格式与原表一致。除此之外，不要修改 API 约定的其他内容。

## 八、种子数据与演示数据
1. V2__seed.sql：
   - 11 个执行单元：C01 智能产业学院、C02 数字金融产业学院、C03 数字影视传媒产业学院、C04 数据产业学院、C05 游戏产业学院、C06 智能建造产业学院、C07 现代通信产业学院、C08 未来技术产业学院（以上 type=COLLEGE）、C09 国际学院（INTERNATIONAL_COLLEGE）、C10 马克思主义学院（MARXISM_COLLEGE）、S01 校办公室（SCHOOL_OFFICE）
   - 计量单位字典：项、个、人、人次、门、篇、部、本、万元、%
   - 系统参数：文件格式和大小上限（按 API 约定 8.5 节）、预警阈值、截止提醒天数（默认 7）、年度核算截止日（默认 01-15）
   - 开发测试账号：admin（ADMIN）、leader（LEADER）、owner1 和 owner2（SCHOOL_OWNER）、c01–c10 和 s01（UNIT_OWNER，分别属于对应单元）。密码都是 Test@123456，用 BCrypt（cost=10）加密，在 SQL 中直接写入哈希值，并在注释中写明明文。在文档中注明：生产环境必须删除这些账号或修改密码。
2. demo_data.sql：一个“2026–2030 五年发展规划”计划，包括：
   - 2 个建设方向（1 个校级、1 个专业群）
   - A01“新增省级及以上教学成果奖”（INCREMENT，可下分，五年 5 项）→ A01-1 国家级（1 项）、A01-2 省级（4 项）；A01-2 分解给 C01、C03、C05
   - A02“双师型教师占比”（RATIO，可下分）→ A02-1，分解给全部 8 个二级学院
   - A03“科研经费”（GROWTH，五年增长 15%，基线值 2025 年 1000 万元）→ A03-1，分解给若干学院
   - A04“建成省级高水平专业群产教融合基地”（BOOLEAN，不可下分，由 S01 承接，计划 2028 年完成）
   - 2026 年已激活；若干条填报，状态覆盖草稿、待审核、已通过、已驳回
   - 1 笔已生效的“延期 + 转移”：A01-2-C03 把 2026 年的 1 项转给 C05，到 2027 年完成，包括全部审批环节和快照
   - 1 笔正在审批中的延期申请（有冻结额度）
   - 若干条审计日志
   演示数据的 id 可以用固定的小数值，方便阅读（例如 1001、1002）。

## 九、验证（必须实际执行，不能只写不跑）
1. 在 MySQL 8.0 上按顺序执行 V1 → V2 → demo_data.sql，不能有任何报错。如果本机没有 MySQL，按以下顺序处理：先用 Docker 启动 mysql:8.0；没有 Docker 时，可以用 npm 包 mysql-memory-server 临时启动一个实例；都不行时，给出安装步骤并停下来告诉我。
2. 执行 typical_queries.sql，并把实际结果写进 docs/数据库设计.md 的“验证记录”一节。至少包含以下查询：
   a. 学院工作台：C05 在 2026、2027 年的 B+ 列表，包括目标、完成值、完成率、状态（应能看到转移进来的那 1 项）
   b. owner1 的待审核列表
   c. A01-2 的 B+ 分解平衡校验（转移后 A+ 总量仍然守恒）
   d. A02-1 占比型汇总（Σ分子 ÷ Σ分母），并对比“错误的平均算法”结果有什么不同
   e. A03 增长率计算
   f. 某条 B+ 的调整历史时间线
   g. 按时间范围查询审计日志
   h. 大屏：各执行单元的完成率排名
   i. “我的待办”查询（待填报、待审核、待确认接收、待审批）
3. 对 a、b、h、i 执行 EXPLAIN，确认用到了索引，并把结论写进文档。
4. 验证触发器：执行 UPDATE audit_log 和 DELETE FROM audit_log，应该报错。
5. 用 CHECK 约束做一次反例测试：插入 level=A_PLUS 但 parent_id 为空的指标，应该被拒绝。
6. 验证完成后，清理临时启动的数据库实例和临时文件。

## 十、完成后回复
用简短的列表告诉我：
- 产出的文件路径；
- 表的数量和分组；
- 关键设计决策（不超过 10 条）；
- 新增的枚举；
- 验证结果（各条查询是否符合预期、EXPLAIN 结论、触发器和 CHECK 反例的结果）；
- 需要我确认的问题。
````

---

## 验收清单（你自己检查）

- [ ] `docs/数据库设计.md` 有 ER 图、字段字典、索引说明、典型 SQL、验证记录
- [ ] V1、V2、demo_data 三个脚本在 MySQL 8 上执行没有报错（以 AI 回复中的验证记录为准）
- [ ] 每张表、每个字段都有中文注释；没有使用 AUTO_INCREMENT 和 ENUM 类型
- [ ] indicator 表能表达 A → A+，task / task_year 能表达 B+ 及其年度目标
- [ ] submission 的填报值用独立字段存储（不是 JSON），能区分填报值和认定值
- [ ] 调整申请有明细、审批环节、调整前后快照、冻结额度
- [ ] 审计日志禁止 UPDATE 和 DELETE（触发器验证通过）
- [ ] 占比型汇总用的是 Σ分子 ÷ Σ分母
- [ ] 转移之后，A01-2 的 A+ 总量仍然守恒
- [ ] 新增的枚举已追加到 API 约定第 9 章
