package edu.fzrj.metrics.module.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.entity.Template;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.mapper.TemplateMapper;
import edu.fzrj.metrics.module.stats.service.support.StatsAggregator;
import edu.fzrj.metrics.module.system.entity.MajorGroup;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.MajorGroupMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计划（API 约定 15.2）：新建（空白 / 模板 / 复制）、修改、启动、归档、另存为模板
 */
@Service
public class PlanService {

    private static final String DEFAULT_SETTLE_DEADLINE = "01-15";
    private static final int MAX_PLAN_SPAN_YEARS = 9;

    private final PlanMapper planMapper;
    private final PlanYearMapper planYearMapper;
    private final TemplateMapper templateMapper;
    private final DirectionMapper directionMapper;
    private final IndicatorMapper indicatorMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final MajorGroupMapper majorGroupMapper;
    private final UserRefResolver userRefResolver;
    private final UserRoleMapper userRoleMapper;
    private final TaskMapper taskMapper;
    private final TaskYearMapper taskYearMapper;
    private final ObjectMapper objectMapper;

    public PlanService(PlanMapper planMapper, PlanYearMapper planYearMapper, TemplateMapper templateMapper,
                       DirectionMapper directionMapper, IndicatorMapper indicatorMapper,
                       IndicatorYearTargetMapper yearTargetMapper, MajorGroupMapper majorGroupMapper,
                       UserRefResolver userRefResolver, UserRoleMapper userRoleMapper,
                       TaskMapper taskMapper, TaskYearMapper taskYearMapper, ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.planYearMapper = planYearMapper;
        this.templateMapper = templateMapper;
        this.directionMapper = directionMapper;
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.majorGroupMapper = majorGroupMapper;
        this.userRefResolver = userRefResolver;
        this.userRoleMapper = userRoleMapper;
        this.taskMapper = taskMapper;
        this.taskYearMapper = taskYearMapper;
        this.objectMapper = objectMapper;
    }

    // ---------------- 查询 ----------------

    /** 计划列表（不分页，首页卡片；完成率 = 当前统计年度有正目标的已下发任务达标率，无任务时为 null） */
    public List<PlanDtos.ListItem> list(String status) {
        LambdaQueryWrapper<Plan> wrapper = new LambdaQueryWrapper<Plan>()
                .eq(Plan::getDeleted, 0L)
                .eq(StringUtils.hasText(status), Plan::getStatus, status)
                .orderByDesc(Plan::getCreatedAt);
        return planMapper.selectList(wrapper).stream()
                .map(p -> new PlanDtos.ListItem(p.getId(), p.getName(), p.getStartYear(), p.getEndYear(),
                        p.getBaselineYear(), p.getStatus(), p.getSource(), p.getFinalizedAt(),
                        completionRateOf(p), p.getCreatedAt()))
                .toList();
    }

    /** 整体完成率：当前激活年度（无激活取最近锁定年度，再无取起始年）达标任务数 ÷ 有正目标的任务数 */
    private java.math.BigDecimal completionRateOf(Plan plan) {
        List<PlanYear> planYears = planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, plan.getId())
                .eq(PlanYear::getDeleted, 0L));
        if (planYears.isEmpty()) {
            return null;
        }
        Integer year = planYears.stream().filter(y -> "ACTIVE".equals(y.getStatus()))
                .map(PlanYear::getYear).max(Integer::compareTo)
                .orElse(planYears.stream().filter(y -> "LOCKED".equals(y.getStatus()))
                        .map(PlanYear::getYear).max(Integer::compareTo)
                        .orElse(plan.getStartYear()));
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getPlanId, plan.getId())
                .ne(Task::getStatus, "PENDING_DISTRIBUTION")
                .eq(Task::getIsHistorical, false)
                .eq(Task::getDeleted, 0L)
                .select(Task::getId));
        if (tasks.isEmpty()) {
            return null;
        }
        List<TaskYear> rows = taskYearMapper.selectList(new LambdaQueryWrapper<TaskYear>()
                .in(TaskYear::getTaskId, tasks.stream().map(Task::getId).toList())
                .eq(TaskYear::getYear, year)
                .eq(TaskYear::getDeleted, 0L));
        List<TaskYear> countable = rows.stream()
                .filter(ty -> StatsAggregator.countable(ty.getTarget()))
                .toList();
        long achieved = countable.stream()
                .filter(ty -> StatsAggregator.isAchievedStatus(ty.getYearStatus()))
                .count();
        return StatsAggregator.rate(achieved, countable.size());
    }

    public Plan requirePlan(Long planId) {
        Plan plan = planMapper.selectOne(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getId, planId)
                .eq(Plan::getDeleted, 0L));
        if (plan == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        return plan;
    }

    public PlanDtos.Detail detail(Long planId) {
        return toDetail(requirePlan(planId));
    }

    // ---------------- 新建 ----------------

    @Transactional
    public PlanDtos.Detail create(PlanDtos.CreateRequest request) {
        validateYears(request.getStartYear(), request.getEndYear(), request.getBaselineYear());
        int baselineYear = request.getBaselineYear() == null
                ? request.getStartYear() - 1 : request.getBaselineYear();

        Plan plan = new Plan();
        plan.setName(request.getName());
        plan.setStartYear(request.getStartYear());
        plan.setEndYear(request.getEndYear());
        plan.setBaselineYear(baselineYear);
        plan.setSettleDeadline(StringUtils.hasText(request.getSettleDeadline())
                ? request.getSettleDeadline() : DEFAULT_SETTLE_DEADLINE);
        plan.setStatus("DRAFT");
        plan.setSource(request.getSource());
        plan.setIsReceiverConfirmRequired(request.getIsReceiverConfirmRequired() == null
                || request.getIsReceiverConfirmRequired());

        TemplateContent content = null;
        switch (request.getSource()) {
            case "BLANK" -> {
            }
            case "TEMPLATE" -> {
                if (request.getTemplateId() == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "从模板新建时必须提供 templateId");
                }
                Template template = templateMapper.selectOne(new LambdaQueryWrapper<Template>()
                        .eq(Template::getId, request.getTemplateId())
                        .eq(Template::getDeleted, 0L));
                if (template == null || Boolean.FALSE.equals(template.getIsEnabled())) {
                    throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND);
                }
                plan.setTemplateId(template.getId());
                content = parseContent(template.getContentJson());
            }
            case "COPY" -> {
                if (request.getSourcePlanId() == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "复制计划时必须提供 sourcePlanId");
                }
                Plan source = requirePlan(request.getSourcePlanId());
                plan.setSourcePlanId(source.getId());
                content = snapshot(source.getId());
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "source 只支持 BLANK / TEMPLATE / COPY");
        }

        Long userId = SecurityUtils.getUserId();
        plan.setCreatedBy(userId);
        plan.setUpdatedBy(userId);
        planMapper.insert(plan);

        if (content != null) {
            instantiate(plan, content);
        }
        // 重查以拿到 DB 默认的 version / createdAt
        return toDetail(requirePlan(plan.getId()));
    }

    // ---------------- 修改 / 状态动作 ----------------

    @Transactional
    public PlanDtos.Detail patch(Long planId, PatchHelper patch) {
        Plan plan = requirePlan(planId);
        requireNotArchived(plan);
        int version = patch.versionRequired();
        requireVersion(plan.getVersion(), version);

        boolean yearFieldsTouched = patch.has("startYear") || patch.has("endYear") || patch.has("baselineYear");
        if (yearFieldsTouched && !"DRAFT".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_STATUS_INVALID, "只有草稿状态的计划才能修改起止年份");
        }
        Integer startYear = patch.has("startYear") ? patch.integer("startYear") : plan.getStartYear();
        Integer endYear = patch.has("endYear") ? patch.integer("endYear") : plan.getEndYear();
        Integer baselineYear = patch.has("baselineYear") ? patch.integer("baselineYear") : plan.getBaselineYear();
        if (yearFieldsTouched) {
            validateYears(startYear, endYear, baselineYear);
            plan.setStartYear(startYear);
            plan.setEndYear(endYear);
            plan.setBaselineYear(baselineYear);
        }
        if (patch.has("name")) {
            String name = patch.text("name");
            if (!StringUtils.hasText(name) || name.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "名称必填且不超过 200 字");
            }
            plan.setName(name);
        }
        if (patch.has("settleDeadline")) {
            String deadline = patch.text("settleDeadline");
            if (deadline == null || !deadline.matches("^(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$")) {
                throw new BizException(ErrorCode.PARAM_INVALID, "核算截止日格式应为 MM-DD");
            }
            plan.setSettleDeadline(deadline);
        }
        if (patch.has("isReceiverConfirmRequired")) {
            plan.setIsReceiverConfirmRequired(patch.bool("isReceiverConfirmRequired"));
        }
        plan.setUpdatedBy(SecurityUtils.getUserId());
        if (planMapper.updateById(plan) == 0) {
            throw versionConflict(planId);
        }
        return toDetail(requirePlan(planId));
    }

    /** 启动执行：DRAFT → ACTIVE，并生成 plan_year 行 */
    @Transactional
    public PlanDtos.Detail activate(Long planId, Integer version) {
        Plan plan = requirePlan(planId);
        requireNotArchived(plan);
        requireVersion(plan.getVersion(), version);
        if (!"DRAFT".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_STATUS_INVALID, "只有草稿状态的计划才能启动执行");
        }
        Long userId = SecurityUtils.getUserId();
        plan.setStatus("ACTIVE");
        plan.setUpdatedBy(userId);
        if (planMapper.updateById(plan) == 0) {
            throw versionConflict(planId);
        }
        for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
            PlanYear planYear = new PlanYear();
            planYear.setPlanId(plan.getId());
            planYear.setYear(year);
            planYear.setStatus("NOT_ACTIVATED");
            planYear.setCreatedBy(userId);
            planYear.setUpdatedBy(userId);
            planYearMapper.insert(planYear);
        }
        return toDetail(requirePlan(planId));
    }

    /** 归档：归档后只读，写操作一律 30002 */
    @Transactional
    public PlanDtos.Detail archive(Long planId, Integer version) {
        Plan plan = requirePlan(planId);
        requireVersion(plan.getVersion(), version);
        if ("ARCHIVED".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_STATUS_INVALID, "计划已归档");
        }
        plan.setStatus("ARCHIVED");
        plan.setUpdatedBy(SecurityUtils.getUserId());
        if (planMapper.updateById(plan) == 0) {
            throw versionConflict(planId);
        }
        return toDetail(requirePlan(planId));
    }

    /** 另存为模板：结构快照（S 级归属 + 指标定义 + 年度目标），不含执行数据 */
    @Transactional
    public PlanDtos.TemplateDetail saveAsTemplate(Long planId, PlanDtos.SaveAsTemplateRequest request) {
        Plan plan = requirePlan(planId);
        TemplateContent content = snapshot(planId);
        content.setStartYear(plan.getStartYear());
        content.setEndYear(plan.getEndYear());
        Template template = new Template();
        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setSourcePlanId(plan.getId());
        template.setIsEnabled(true);
        template.setContentJson(writeContent(content));
        Long userId = SecurityUtils.getUserId();
        template.setCreatedBy(userId);
        template.setUpdatedBy(userId);
        templateMapper.insert(template);
        Template saved = templateMapper.selectById(template.getId());
        return new PlanDtos.TemplateDetail(saved.getId(), saved.getName(), saved.getDescription(),
                templateView(content), saved.getSourcePlanId(), saved.getIsEnabled(), saved.getVersion(),
                saved.getCreatedAt(), saved.getUpdatedAt());
    }

    // ---------------- 结构快照 / 实例化 ----------------

    /** 把计划的「S 级归属 + 指标 + 年度目标」导出为可移植结构快照 */
    public TemplateContent snapshot(Long planId) {
        List<Direction> directions = directionMapper.selectList(new LambdaQueryWrapper<Direction>()
                .eq(Direction::getPlanId, planId)
                .eq(Direction::getDeleted, 0L)
                .orderByAsc(Direction::getSort).orderByAsc(Direction::getId));
        List<Indicator> indicators = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, planId)
                .eq(Indicator::getDeleted, 0L)
                .orderByAsc(Indicator::getSort).orderByAsc(Indicator::getId));
        Map<Long, List<IndicatorYearTarget>> targetsByIndicator = new java.util.HashMap<>();
        if (!indicators.isEmpty()) {
            yearTargetMapper.selectList(new LambdaQueryWrapper<IndicatorYearTarget>()
                            .in(IndicatorYearTarget::getIndicatorId, indicators.stream().map(Indicator::getId).toList())
                            .eq(IndicatorYearTarget::getDeleted, 0L))
                    .forEach(t -> targetsByIndicator.computeIfAbsent(t.getIndicatorId(), k -> new ArrayList<>()).add(t));
        }
        Map<Long, MajorGroup> majorGroups = majorGroupMapper.selectList(new LambdaQueryWrapper<MajorGroup>()
                        .eq(MajorGroup::getDeleted, 0L))
                .stream().collect(java.util.stream.Collectors.toMap(MajorGroup::getId, g -> g));
        List<Long> userIds = new ArrayList<>(indicators.stream().map(Indicator::getOwnerId).toList());
        userIds.addAll(directions.stream().map(Direction::getLeaderId).toList());
        Map<Long, SysUser> users = userRefResolver.mapOf(userIds);

        TemplateContent content = new TemplateContent();
        for (Direction direction : directions) {
            TemplateContent.DirectionNode directionNode = new TemplateContent.DirectionNode();
            directionNode.setSubjectType(direction.getSubjectType());
            MajorGroup group = direction.getMajorGroupId() == null ? null : majorGroups.get(direction.getMajorGroupId());
            directionNode.setMajorGroupName(group == null ? null : group.getName());
            directionNode.setName(direction.getName());
            SysUser leader = direction.getLeaderId() == null ? null : users.get(direction.getLeaderId());
            directionNode.setLeaderUsername(leader == null ? null : leader.getUsername());
            directionNode.setSort(direction.getSort());
            content.getDirections().add(directionNode);
            for (Indicator a : indicators.stream()
                    .filter(i -> direction.getId().equals(i.getDirectionId()) && i.getParentId() == null).toList()) {
                TemplateContent.IndicatorNode aNode = toNode(a, targetsByIndicator, users);
                directionNode.getIndicators().add(aNode);
                for (Indicator ap : indicators.stream()
                        .filter(i -> a.getId().equals(i.getParentId())).toList()) {
                    aNode.getChildren().add(toNode(ap, targetsByIndicator, users));
                }
            }
        }
        for (Indicator a : indicators.stream()
                .filter(i -> i.getDirectionId() == null && i.getParentId() == null).toList()) {
            TemplateContent.IndicatorNode aNode = toNode(a, targetsByIndicator, users);
            content.getUnassignedIndicators().add(aNode);
            for (Indicator ap : indicators.stream().filter(i -> a.getId().equals(i.getParentId())).toList()) {
                aNode.getChildren().add(toNode(ap, targetsByIndicator, users));
            }
        }
        return content;
    }

    private TemplateContent.IndicatorNode toNode(Indicator indicator,
                                                 Map<Long, List<IndicatorYearTarget>> targetsByIndicator,
                                                 Map<Long, SysUser> users) {
        TemplateContent.IndicatorNode node = new TemplateContent.IndicatorNode();
        node.setLevel(indicator.getLevel());
        node.setCode(indicator.getCode());
        node.setName(indicator.getName());
        node.setNature(indicator.getNature());
        node.setMeasureType(indicator.getMeasureType());
        node.setStatCaliber(indicator.getStatCaliber());
        node.setUnitLabel(indicator.getUnitLabel());
        node.setTargetDirection(indicator.getTargetDirection());
        node.setRollupMode(indicator.getRollupMode());
        node.setIsQuantifiable(indicator.getIsQuantifiable());
        node.setDescription(indicator.getDescription());
        node.setEvidenceRequirement(indicator.getEvidenceRequirement());
        node.setMinFileCount(indicator.getMinFileCount());
        node.setBaselineValue(indicator.getBaselineValue());
        node.setTotalTarget(indicator.getTotalTarget());
        SysUser owner = indicator.getOwnerId() == null ? null : users.get(indicator.getOwnerId());
        node.setOwnerUsername(owner == null ? null : owner.getUsername());
        node.setSort(indicator.getSort());
        for (IndicatorYearTarget target : targetsByIndicator.getOrDefault(indicator.getId(), List.of())) {
            if (target.getTarget() != null) {
                node.getYearTargets().put(target.getYear(), target.getTarget());
            }
        }
        return node;
    }

    /** 把结构快照实例化到计划（S / 指标 / 年度目标；年度目标仅落在计划起止年内） */
    @Transactional
    public void instantiate(Plan plan, TemplateContent content) {
        Long userId = SecurityUtils.getUserId();
        for (TemplateContent.DirectionNode directionNode : content.getDirections()) {
            Direction direction = new Direction();
            direction.setPlanId(plan.getId());
            direction.setSubjectType(directionNode.getSubjectType());
            if ("MAJOR_GROUP".equals(directionNode.getSubjectType())) {
                MajorGroup group = majorGroupMapper.selectOne(new LambdaQueryWrapper<MajorGroup>()
                        .eq(MajorGroup::getName, directionNode.getMajorGroupName())
                        .eq(MajorGroup::getIsEnabled, true)
                        .eq(MajorGroup::getDeleted, 0L));
                if (group == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "专业群不存在或已停用：" + directionNode.getMajorGroupName());
                }
                direction.setMajorGroupId(group.getId());
            }
            direction.setName(directionNode.getName());
            if (StringUtils.hasText(directionNode.getLeaderUsername())) {
                SysUser leader = userRefResolver.findByUsername(directionNode.getLeaderUsername());
                if (leader != null && Boolean.TRUE.equals(leader.getIsEnabled())) {
                    Long leaderRoles = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getUserId, leader.getId())
                            .eq(UserRole::getRole, "LEADER"));
                    if (leaderRoles != null && leaderRoles > 0) {
                        direction.setLeaderId(leader.getId());
                    }
                }
            }
            direction.setSort(directionNode.getSort() == null ? 0 : directionNode.getSort());
            direction.setCreatedBy(userId);
            direction.setUpdatedBy(userId);
            directionMapper.insert(direction);
            for (TemplateContent.IndicatorNode aNode : directionNode.getIndicators()) {
                Indicator a = toIndicator(plan.getId(), direction.getId(), null, aNode, userId);
                indicatorMapper.insert(a);
                insertYearTargets(a.getId(), aNode.getYearTargets(), plan, userId);
                for (TemplateContent.IndicatorNode apNode : aNode.getChildren()) {
                    Indicator ap = toIndicator(plan.getId(), direction.getId(), a.getId(), apNode, userId);
                    indicatorMapper.insert(ap);
                    insertYearTargets(ap.getId(), apNode.getYearTargets(), plan, userId);
                }
            }
        }
        for (TemplateContent.IndicatorNode aNode : content.getUnassignedIndicators()) {
            Indicator a = toIndicator(plan.getId(), null, null, aNode, userId);
            indicatorMapper.insert(a);
            insertYearTargets(a.getId(), aNode.getYearTargets(), plan, userId);
            for (TemplateContent.IndicatorNode apNode : aNode.getChildren()) {
                Indicator ap = toIndicator(plan.getId(), null, a.getId(), apNode, userId);
                indicatorMapper.insert(ap);
                insertYearTargets(ap.getId(), apNode.getYearTargets(), plan, userId);
            }
        }
    }

    private Indicator toIndicator(Long planId, Long directionId, Long parentId,
                                  TemplateContent.IndicatorNode node, Long userId) {
        Indicator indicator = new Indicator();
        indicator.setPlanId(planId);
        indicator.setDirectionId(directionId);
        indicator.setParentId(parentId);
        indicator.setLevel(node.getLevel());
        indicator.setCode(node.getCode());
        indicator.setName(node.getName());
        indicator.setNature(node.getNature());
        indicator.setMeasureType(node.getMeasureType());
        indicator.setStatCaliber(node.getStatCaliber());
        indicator.setUnitLabel(node.getUnitLabel());
        indicator.setTargetDirection(node.getTargetDirection() == null ? "HIGHER_BETTER" : node.getTargetDirection());
        indicator.setRollupMode(node.getRollupMode());
        indicator.setIsQuantifiable(node.getIsQuantifiable());
        indicator.setDescription(node.getDescription());
        indicator.setEvidenceRequirement(node.getEvidenceRequirement());
        indicator.setMinFileCount(node.getMinFileCount() == null ? 0 : node.getMinFileCount());
        indicator.setBaselineValue(node.getBaselineValue());
        indicator.setTotalTarget(node.getTotalTarget());
        if (StringUtils.hasText(node.getOwnerUsername())) {
            SysUser owner = userRefResolver.findByUsername(node.getOwnerUsername());
            indicator.setOwnerId(owner == null ? null : owner.getId());
        }
        indicator.setSort(node.getSort() == null ? 0 : node.getSort());
        indicator.setStatus("DRAFT");
        indicator.setCreatedBy(userId);
        indicator.setUpdatedBy(userId);
        return indicator;
    }

    private void insertYearTargets(Long indicatorId, Map<Integer, java.math.BigDecimal> yearTargets,
                                   Plan plan, Long userId) {
        for (Map.Entry<Integer, java.math.BigDecimal> entry : yearTargets.entrySet()) {
            if (entry.getKey() < plan.getStartYear() || entry.getKey() > plan.getEndYear()) {
                continue;
            }
            IndicatorYearTarget row = new IndicatorYearTarget();
            row.setIndicatorId(indicatorId);
            row.setYear(entry.getKey());
            row.setTarget(entry.getValue());
            row.setCreatedBy(userId);
            row.setUpdatedBy(userId);
            yearTargetMapper.insert(row);
        }
    }

    // ---------------- 内部 ----------------

    /** 模板详情只暴露 A/A+ 结构，S 与 S 总负责人仅保留在内部快照中 */
    public Map<String, Object> templateView(TemplateContent content) {
        List<TemplateContent.IndicatorNode> indicators = new ArrayList<>(content.getUnassignedIndicators());
        for (TemplateContent.DirectionNode direction : content.getDirections()) {
            indicators.addAll(direction.getIndicators());
        }
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("startYear", content.getStartYear());
        view.put("endYear", content.getEndYear());
        view.put("indicators", indicators);
        return view;
    }

    public TemplateContent parseContent(String contentJson) {
        try {
            return objectMapper.readValue(contentJson, TemplateContent.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模板内容解析失败");
        }
    }

    public String writeContent(TemplateContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模板内容序列化失败");
        }
    }

    /** 计划写操作前置校验：归档后只读（30002） */
    public void requireNotArchived(Plan plan) {
        if ("ARCHIVED".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_ARCHIVED);
        }
    }

    /** 更新计划（MP 乐观锁）；返回 true 表示版本冲突 */
    public boolean updatePlan(Plan plan) {
        return planMapper.updateById(plan) == 0;
    }

    /** 定稿后指标体系锁定（40005） */
    public void requireNotFinalized(Plan plan) {
        if (plan.getFinalizedAt() != null) {
            throw new BizException(ErrorCode.INDICATOR_FINALIZED);
        }
    }

    public void requireVersion(Integer current, Integer requestVersion) {
        if (requestVersion == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少 version（乐观锁版本号）");
        }
        if (!current.equals(requestVersion)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + current);
        }
    }

    private BizException versionConflict(Long planId) {
        Plan latest = planMapper.selectById(planId);
        return new BizException(ErrorCode.VERSION_CONFLICT,
                "数据已被他人修改，当前最新版本号：" + (latest == null ? "?" : latest.getVersion()));
    }

    private void validateYears(Integer startYear, Integer endYear, Integer baselineYear) {
        if (startYear == null || endYear == null || startYear < 2000 || endYear < startYear
                || endYear - startYear > MAX_PLAN_SPAN_YEARS) {
            throw new BizException(ErrorCode.PLAN_YEARS_INVALID);
        }
        if (baselineYear != null && baselineYear != startYear - 1) {
            throw new BizException(ErrorCode.PLAN_YEARS_INVALID, "基线年应为规划起始年前一年");
        }
    }

    private PlanDtos.Detail toDetail(Plan plan) {
        List<PlanDtos.YearItem> years = planYearMapper.selectList(new LambdaQueryWrapper<PlanYear>()
                        .eq(PlanYear::getPlanId, plan.getId())
                        .eq(PlanYear::getDeleted, 0L)
                        .orderByAsc(PlanYear::getYear))
                .stream().map(y -> new PlanDtos.YearItem(y.getYear(), y.getStatus())).toList();
        Map<Long, SysUser> users = plan.getFinalizedBy() == null
                ? Map.of() : userRefResolver.mapOf(List.of(plan.getFinalizedBy()));
        return new PlanDtos.Detail(plan.getId(), plan.getName(), plan.getStartYear(), plan.getEndYear(),
                plan.getBaselineYear(), plan.getSettleDeadline(), plan.getStatus(), plan.getSource(),
                plan.getTemplateId(), plan.getSourcePlanId(), plan.getIsReceiverConfirmRequired(),
                plan.getFinalizedAt(), userRefResolver.refOf(plan.getFinalizedBy(), users),
                years, plan.getVersion(), plan.getCreatedAt(), plan.getUpdatedAt());
    }
}
