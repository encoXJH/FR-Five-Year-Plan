package edu.fzrj.metrics.module.indicator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.indicator.service.support.IndicatorRules;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 指标体系 A / A+（API 约定 15.3）：树查询、新增、修改、删除、年度目标、定稿
 */
@Service
public class IndicatorService {

    private final IndicatorMapper indicatorMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final DirectionMapper directionMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final TaskMapper taskMapper;
    private final PlanService planService;
    private final UserRefResolver userRefResolver;
    private final DataScopeService dataScopeService;

    public IndicatorService(IndicatorMapper indicatorMapper, IndicatorYearTargetMapper yearTargetMapper,
                            DirectionMapper directionMapper, OrgUnitMapper orgUnitMapper,
                            TaskMapper taskMapper, PlanService planService, UserRefResolver userRefResolver,
                            DataScopeService dataScopeService) {
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.directionMapper = directionMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.taskMapper = taskMapper;
        this.planService = planService;
        this.userRefResolver = userRefResolver;
        this.dataScopeService = dataScopeService;
    }

    // ---------------- 查询 ----------------

    /** 指标列表 / 树（按数据范围过滤：SCHOOL_OWNER 只看自己负责的；UNIT_OWNER 阶段 2 看不到） */
    public List<IndicatorDtos.TreeNode> tree(Long planId, String level, Long directionId,
                                             Long ownerId, String keyword, String view) {
        planService.requirePlan(planId);
        Long scopeOwner = scopeOwnerFilter();
        if (scopeOwner != null && scopeOwner < 0) {
            return List.of();
        }
        List<Indicator> all = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, planId)
                .eq(Indicator::getDeleted, 0L)
                .orderByAsc(Indicator::getSort).orderByAsc(Indicator::getCode));
        Map<Long, Long> effectiveOwners = effectiveOwners(all);
        Map<Long, Direction> directions = directionMap(all);

        List<Indicator> scoped = all.stream()
                .filter(i -> scopeOwner == null || scopeOwner.equals(effectiveOwners.get(i.getId())))
                .toList();
        List<Indicator> matched = scoped.stream()
                .filter(i -> !StringUtils.hasText(level) || level.equals(i.getLevel()))
                .filter(i -> directionId == null || directionId.equals(i.getDirectionId()))
                .filter(i -> ownerId == null || ownerId.equals(effectiveOwners.get(i.getId())))
                .filter(i -> !StringUtils.hasText(keyword)
                        || i.getName().contains(keyword) || i.getCode().contains(keyword))
                .toList();

        Map<Long, SysUser> users = userRefResolver.mapOf(effectiveOwners.values());
        Map<Long, List<IndicatorDtos.YearTargetView>> targetViews = targetViewsOf(all);
        boolean treeView = "tree".equalsIgnoreCase(view == null ? "" : view);
        if (!treeView) {
            return matched.stream()
                    .map(i -> toNode(i, directions, effectiveOwners, users, targetViews, List.of()))
                    .toList();
        }
        Set<Long> matchedIds = matched.stream().map(Indicator::getId).collect(Collectors.toSet());
        List<IndicatorDtos.TreeNode> roots = new ArrayList<>();
        for (Indicator a : scoped.stream().filter(i -> i.getParentId() == null).toList()) {
            List<Indicator> children = matched.stream()
                    .filter(i -> a.getId().equals(i.getParentId()))
                    .toList();
            if (!matchedIds.contains(a.getId()) && children.isEmpty()) {
                continue;
            }
            List<IndicatorDtos.TreeNode> childNodes = children.stream()
                    .map(c -> toNode(c, directions, effectiveOwners, users, targetViews, List.of()))
                    .toList();
            roots.add(toNode(a, directions, effectiveOwners, users, targetViews, childNodes));
        }
        return roots;
    }

    /** 批量加载年度目标并转为接口视图（BOOLEAN：1/NULL → true/null） */
    private Map<Long, List<IndicatorDtos.YearTargetView>> targetViewsOf(List<Indicator> indicators) {
        List<Indicator> quantifiable = indicators.stream()
                .filter(i -> IndicatorRules.isQuantifiable(i.getLevel(), i.getIsQuantifiable()))
                .toList();
        if (quantifiable.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> calibers = new java.util.HashMap<>();
        quantifiable.forEach(i -> calibers.put(i.getId(), i.getStatCaliber()));
        Map<Long, List<IndicatorDtos.YearTargetView>> result = new java.util.HashMap<>();
        loadTargets(quantifiable.stream().map(Indicator::getId).toList())
                .forEach((indicatorId, targets) -> result.put(indicatorId, targets.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> new IndicatorDtos.YearTargetView(e.getKey(),
                                toTargetView(calibers.get(indicatorId), e.getValue())))
                        .toList()));
        return result;
    }

    public IndicatorDtos.Detail detail(Long indicatorId) {
        Indicator indicator = requireIndicator(indicatorId);
        requireDetailAccess(indicator);
        return toDetail(indicator);
    }

    // ---------------- 新增 ----------------

    @Transactional
    public IndicatorDtos.Detail create(Long planId, IndicatorDtos.CreateRequest request) {
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireNotFinalized(plan);
        validateEnums(request.getLevel(), request.getNature(), request.getMeasureType(),
                request.getStatCaliber(), request.getTargetDirection(), request.getRollupMode());

        Indicator indicator = new Indicator();
        indicator.setPlanId(planId);
        indicator.setLevel(request.getLevel());
        indicator.setName(request.getName());
        indicator.setNature(request.getNature());
        indicator.setDescription(request.getDescription());
        indicator.setSort(request.getSort() == null ? 0 : request.getSort());
        indicator.setStatus("DRAFT");

        Set<String> existingCodes = existingCodes(planId);
        if (IndicatorRules.LEVEL_A.equals(request.getLevel())) {
            if (request.getParentId() != null) {
                throw new BizException(ErrorCode.LEVEL_INVALID, "A 类指标不能设置上级");
            }
            if (request.getIsQuantifiable() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "A 类指标必须指定是否可量化");
            }
            indicator.setIsQuantifiable(request.getIsQuantifiable());
            if (request.getDirectionId() != null) {
                requireAdmin();
                indicator.setDirectionId(requireDirectionInPlan(request.getDirectionId(), planId).getId());
            }
            // 校级负责人创建 A 时未指定负责人，默认本人（草稿也受数据范围约束）
            Long ownerId = request.getOwnerId();
            if (ownerId == null) {
                LoginUser loginUser = SecurityUtils.getLoginUser();
                if (!dataScopeService.isAllData(loginUser) && dataScopeService.isOwnerScope(loginUser)) {
                    ownerId = loginUser.getUserId();
                }
            }
            indicator.setOwnerId(ownerId);
            requireUserIfPresent(ownerId);
            indicator.setCode(StringUtils.hasText(request.getCode())
                    ? request.getCode().trim() : IndicatorRules.nextACode(existingCodes));
            IndicatorRules.requireCodeFormat(IndicatorRules.LEVEL_A, indicator.getCode());
        } else {
            if (request.getParentId() == null) {
                throw new BizException(ErrorCode.LEVEL_INVALID, "A+ 必须挂在可量化的 A 下面");
            }
            Indicator parent = requireIndicator(request.getParentId());
            if (!parent.getPlanId().equals(planId)) {
                throw new BizException(ErrorCode.LEVEL_INVALID, "上级指标不属于本计划");
            }
            IndicatorRules.requireAPlusAttachable(parent.getLevel(), parent.getIsQuantifiable());
            dataScopeService.canAccessIndicatorOwner(parent.getOwnerId());
            indicator.setDirectionId(parent.getDirectionId());
            indicator.setParentId(parent.getId());
            indicator.setOwnerId(request.getOwnerId());
            requireUserIfPresent(request.getOwnerId());
            indicator.setCode(StringUtils.hasText(request.getCode())
                    ? request.getCode().trim()
                    : IndicatorRules.nextAPlusCode(parent.getCode(), existingCodes));
            IndicatorRules.requireCodeFormat(IndicatorRules.LEVEL_A_PLUS, indicator.getCode());
            if (!indicator.getCode().startsWith(parent.getCode() + "-")) {
                throw new BizException(ErrorCode.PARAM_INVALID, "A+ 编号应以父指标编号开头");
            }
        }
        if (existingCodes.contains(indicator.getCode())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标编号已存在：" + indicator.getCode());
        }
        if (IndicatorRules.LEVEL_A.equals(indicator.getLevel())) {
            dataScopeService.canAccessIndicatorOwner(indicator.getOwnerId());
        }

        boolean quantifiable = IndicatorRules.isQuantifiable(indicator.getLevel(), indicator.getIsQuantifiable());
        if (quantifiable) {
            String targetDirection = StringUtils.hasText(request.getTargetDirection())
                    ? request.getTargetDirection() : "HIGHER_BETTER";
            IndicatorRules.requireQuantitativeDefinition(indicator.getLevel(), indicator.getIsQuantifiable(),
                    request.getMeasureType(), request.getStatCaliber(), request.getUnitLabel(),
                    targetDirection, request.getRollupMode());
            indicator.setMeasureType(request.getMeasureType());
            indicator.setStatCaliber(request.getStatCaliber());
            indicator.setUnitLabel(request.getUnitLabel());
            indicator.setTargetDirection(targetDirection);
            indicator.setRollupMode(request.getRollupMode());
            indicator.setEvidenceRequirement(request.getEvidenceRequirement());
            indicator.setMinFileCount(request.getMinFileCount() == null ? 0 : request.getMinFileCount());
            indicator.setBaselineValue(request.getBaselineValue());
            indicator.setTotalTarget("BOOLEAN".equals(request.getStatCaliber())
                    ? BigDecimal.ONE : request.getTotalTarget());
        } else {
            if (request.getYearTargets() != null && !request.getYearTargets().isEmpty()) {
                IndicatorRules.requireYearTargetsAllowed(indicator.getLevel(), indicator.getIsQuantifiable());
            }
            clearQuantitativeFields(indicator);
            indicator.setEvidenceRequirement(null);
            indicator.setMinFileCount(0);
        }

        Long userId = SecurityUtils.getUserId();
        indicator.setCreatedBy(userId);
        indicator.setUpdatedBy(userId);
        indicatorMapper.insert(indicator);

        if (quantifiable && request.getYearTargets() != null && !request.getYearTargets().isEmpty()) {
            Map<Integer, BigDecimal> targets = validateYearTargets(plan, indicator.getStatCaliber(),
                    indicator.getTotalTarget(), indicator.getBaselineValue(), request.getYearTargets());
            insertYearTargetRows(indicator.getId(), targets, userId);
        }
        return toDetail(requireIndicator(indicator.getId()));
    }

    // ---------------- 修改 ----------------

    @Transactional
    public IndicatorDtos.Detail patch(Long indicatorId, PatchHelper patch) {
        Indicator indicator = requireIndicator(indicatorId);
        Plan plan = planService.requirePlan(indicator.getPlanId());
        planService.requireNotArchived(plan);
        planService.requireNotFinalized(plan);
        dataScopeService.canAccessIndicatorOwner(effectiveOwnerOf(indicator));
        int version = patch.versionRequired();
        planService.requireVersion(indicator.getVersion(), version);
        boolean wasQuantifiable = IndicatorRules.isQuantifiable(indicator.getLevel(), indicator.getIsQuantifiable());

        for (String readonly : List.of("level", "parentId", "directionId", "planId", "code", "status")) {
            if (patch.has(readonly)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "字段 " + readonly + " 不能修改");
            }
        }
        if (patch.has("name")) {
            String name = patch.text("name");
            if (!StringUtils.hasText(name) || name.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "名称必填且不超过 200 字");
            }
            indicator.setName(name);
        }
        if (patch.has("nature")) {
            String nature = patch.text("nature");
            requireEnum(nature, Set.of("CONSTRAINT", "DEVELOPMENT"), "指标性质");
            indicator.setNature(nature);
        }
        if (patch.has("measureType")) {
            indicator.setMeasureType(patch.isNull("measureType") ? null : patch.text("measureType"));
        }
        if (patch.has("statCaliber")) {
            indicator.setStatCaliber(patch.isNull("statCaliber") ? null : patch.text("statCaliber"));
        }
        if (patch.has("unitLabel") || patch.has("unit")) {
            String field = patch.has("unitLabel") ? "unitLabel" : "unit";
            indicator.setUnitLabel(patch.isNull(field) ? null : patch.text(field));
        }
        if (patch.has("targetDirection")) {
            String dir = patch.isNull("targetDirection") ? null : patch.text("targetDirection");
            if (dir != null) {
                requireEnum(dir, Set.of("HIGHER_BETTER", "LOWER_BETTER"), "达成方向");
            }
            indicator.setTargetDirection(dir);
        }
        if (patch.has("description")) {
            indicator.setDescription(patch.isNull("description") ? null : patch.text("description"));
        }
        if (patch.has("evidenceRequirement")) {
            indicator.setEvidenceRequirement(patch.isNull("evidenceRequirement") ? null : patch.text("evidenceRequirement"));
        }
        if (patch.has("minFileCount")) {
            indicator.setMinFileCount(patch.integer("minFileCount"));
        }
        if (patch.has("baselineValue")) {
            indicator.setBaselineValue(patch.isNull("baselineValue") ? null : patch.decimal("baselineValue"));
        }
        if (patch.has("ownerId")) {
            Long ownerId = patch.isNull("ownerId") ? null : patch.longId("ownerId");
            requireUserIfPresent(ownerId);
            indicator.setOwnerId(ownerId);
        }
        if (patch.has("sort")) {
            indicator.setSort(patch.integer("sort"));
        }
        if (IndicatorRules.LEVEL_A.equals(indicator.getLevel())) {
            if (patch.has("isQuantifiable")) {
                indicator.setIsQuantifiable(patch.bool("isQuantifiable"));
            }
            if (patch.has("rollupMode")) {
                String rollup = patch.isNull("rollupMode") ? null : patch.text("rollupMode");
                if (rollup != null) {
                    requireEnum(rollup, Set.of("AUTO_SUM", "MANUAL"), "汇总方式");
                }
                indicator.setRollupMode(rollup);
            }
        } else if (patch.has("isQuantifiable") || patch.has("rollupMode")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "isQuantifiable 和 rollupMode 仅用于 A 类指标");
        }

        boolean quantifiable = IndicatorRules.isQuantifiable(indicator.getLevel(), indicator.getIsQuantifiable());
        if (wasQuantifiable && !quantifiable) {
            Long children = indicatorMapper.selectCount(new LambdaQueryWrapper<Indicator>()
                    .eq(Indicator::getParentId, indicatorId)
                    .eq(Indicator::getDeleted, 0L));
            if (children != null && children > 0) {
                throw new BizException(ErrorCode.LEVEL_INVALID, "存在下属 A+，不能改为不可量化 A");
            }
        }
        if (quantifiable) {
            IndicatorRules.requireQuantitativeDefinition(indicator.getLevel(), indicator.getIsQuantifiable(),
                    indicator.getMeasureType(), indicator.getStatCaliber(), indicator.getUnitLabel(),
                    indicator.getTargetDirection(), indicator.getRollupMode());
        } else {
            clearQuantitativeFields(indicator);
            indicator.setEvidenceRequirement(null);
            indicator.setMinFileCount(0);
        }

        Long userId = SecurityUtils.getUserId();
        indicator.setUpdatedBy(userId);
        if (indicatorMapper.updateById(indicator) == 0) {
            Indicator latest = requireIndicator(indicatorId);
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + latest.getVersion());
        }
        if (wasQuantifiable && !quantifiable) {
            softDeleteYearTargets(indicatorId, userId);
        }
        return toDetail(requireIndicator(indicatorId));
    }

    @Transactional
    public IndicatorDtos.Detail assignDirection(Long indicatorId, IndicatorDtos.DirectionAssignRequest request) {
        requireAdmin();
        Indicator indicator = requireIndicator(indicatorId);
        if (!IndicatorRules.LEVEL_A.equals(indicator.getLevel()) || indicator.getParentId() != null) {
            throw new BizException(ErrorCode.LEVEL_INVALID, "只能配置 A 类指标的 S 归属");
        }
        Plan plan = planService.requirePlan(indicator.getPlanId());
        planService.requireNotArchived(plan);
        planService.requireNotFinalized(plan);
        if (!"DRAFT".equals(indicator.getStatus())) {
            throw new BizException(ErrorCode.INDICATOR_FINALIZED, "只能修改草稿 A 的 S 归属");
        }
        planService.requireVersion(indicator.getVersion(), request.getVersion());
        if (request.getDirectionId() != null) {
            requireDirectionInPlan(request.getDirectionId(), indicator.getPlanId());
        }

        Long userId = SecurityUtils.getUserId();
        indicator.setDirectionId(request.getDirectionId());
        indicator.setUpdatedBy(userId);
        if (indicatorMapper.updateById(indicator) == 0) {
            Indicator latest = requireIndicator(indicatorId);
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + latest.getVersion());
        }
        List<Indicator> children = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getParentId, indicatorId)
                .eq(Indicator::getDeleted, 0L));
        for (Indicator child : children) {
            child.setDirectionId(request.getDirectionId());
            child.setUpdatedBy(userId);
            if (indicatorMapper.updateById(child) == 0) {
                throw new BizException(ErrorCode.VERSION_CONFLICT, "下属 A+ 已被他人修改，请刷新后重试");
            }
        }
        return toDetail(requireIndicator(indicatorId));
    }

    // ---------------- 删除 ----------------

    @Transactional
    public void delete(Long indicatorId) {
        Indicator indicator = requireIndicator(indicatorId);
        Plan plan = planService.requirePlan(indicator.getPlanId());
        planService.requireNotArchived(plan);
        dataScopeService.canAccessIndicatorOwner(effectiveOwnerOf(indicator));
        // 阶段 2 判据：定稿（plan.finalizedAt 非空）即锁定；已下发（DISTRIBUTED）同样禁删
        if (plan.getFinalizedAt() != null || !"DRAFT".equals(indicator.getStatus())) {
            throw new BizException(ErrorCode.INDICATOR_DISTRIBUTED);
        }
        Long children = indicatorMapper.selectCount(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getParentId, indicatorId)
                .eq(Indicator::getDeleted, 0L));
        if (children != null && children > 0) {
            throw new BizException(ErrorCode.HAS_CHILDREN, "存在下属 A+ 指标，请先删除子指标");
        }
        Long userId = SecurityUtils.getUserId();
        softDeleteYearTargets(indicatorId, userId);
        indicator.setDeleted(indicator.getId());
        indicator.setUpdatedBy(userId);
        indicatorMapper.updateById(indicator);
    }

    // ---------------- 年度目标 ----------------

    @Transactional
    public IndicatorDtos.Detail putYearTargets(Long indicatorId, IndicatorDtos.YearTargetsPutRequest request) {
        Indicator indicator = requireIndicator(indicatorId);
        Plan plan = planService.requirePlan(indicator.getPlanId());
        planService.requireNotArchived(plan);
        planService.requireNotFinalized(plan);
        dataScopeService.canAccessIndicatorOwner(effectiveOwnerOf(indicator));
        planService.requireVersion(indicator.getVersion(), request.getVersion());
        IndicatorRules.requireYearTargetsAllowed(indicator.getLevel(), indicator.getIsQuantifiable());

        BigDecimal baseline = request.getBaselineValue() != null
                ? request.getBaselineValue() : indicator.getBaselineValue();
        BigDecimal totalTarget = "BOOLEAN".equals(indicator.getStatCaliber())
                ? BigDecimal.ONE : request.getTotalTarget();
        Map<Integer, BigDecimal> targets = validateYearTargets(plan, indicator.getStatCaliber(),
                totalTarget, baseline, request.getYearTargets());

        indicator.setTotalTarget(totalTarget);
        if (request.getBaselineValue() != null) {
            indicator.setBaselineValue(request.getBaselineValue());
        }
        Long userId = SecurityUtils.getUserId();
        indicator.setUpdatedBy(userId);
        if (indicatorMapper.updateById(indicator) == 0) {
            Indicator latest = requireIndicator(indicatorId);
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + latest.getVersion());
        }
        upsertYearTargetRows(indicatorId, targets, userId);
        return toDetail(requireIndicator(indicatorId));
    }

    /** 校验并规范化年度目标（创建 / PUT / 定稿共用） */
    public Map<Integer, BigDecimal> validateYearTargets(Plan plan, String statCaliber, BigDecimal totalTarget,
                                                        BigDecimal baselineValue,
                                                        List<IndicatorDtos.YearTargetItem> items) {
        IndicatorRules.requirePlanYears(items.stream().map(IndicatorDtos.YearTargetItem::getYear).toList(),
                plan.getStartYear(), plan.getEndYear());
        Map<Integer, BigDecimal> targets = new LinkedHashMap<>();
        for (IndicatorDtos.YearTargetItem item : items) {
            targets.put(item.getYear(), IndicatorRules.normalizeTarget(item.getTarget(), statCaliber));
        }
        if ("BOOLEAN".equals(statCaliber)) {
            IndicatorRules.requireBooleanSingleYear(targets);
            return targets;
        }
        for (Map.Entry<Integer, BigDecimal> entry : targets.entrySet()) {
            if (entry.getValue() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, entry.getKey() + " 年目标必填");
            }
        }
        if (totalTarget == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "五年目标总量必填");
        }
        if (("GROWTH".equals(statCaliber) || "STOCK".equals(statCaliber)) && baselineValue == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "增长率型 / 存量时点型指标基线值必填",
                    List.of(new FieldError("baselineValue", "基线值必填")));
        }
        IndicatorRules.requireIncrementSum(statCaliber, totalTarget, targets.values());
        return targets;
    }

    // ---------------- 定稿 ----------------

    @Transactional
    public PlanDtos.Detail finalizePlan(Long planId, Integer version) {
        Plan plan = planService.requirePlan(planId);
        planService.requireNotArchived(plan);
        planService.requireVersion(plan.getVersion(), version);
        if (plan.getFinalizedAt() != null) {
            throw new BizException(ErrorCode.PLAN_STATUS_INVALID, "指标体系已定稿");
        }
        List<Indicator> all = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, planId)
                .eq(Indicator::getDeleted, 0L));
        if (all.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标体系为空，不能定稿");
        }
        Map<Long, Long> effectiveOwners = effectiveOwners(all);
        Map<Long, Map<Integer, BigDecimal>> targetsByIndicator = loadTargets(
                all.stream().map(Indicator::getId).toList());

        List<FieldError> errors = new ArrayList<>();
        List<FieldError> sumErrors = new ArrayList<>();
        List<FieldError> conservationErrors = new ArrayList<>();
        Set<Long> validDirectionIds = directionMapper.selectList(new LambdaQueryWrapper<Direction>()
                        .eq(Direction::getPlanId, planId)
                        .eq(Direction::getDeleted, 0L)
                        .select(Direction::getId))
                .stream().map(Direction::getId).collect(Collectors.toSet());
        for (Indicator indicator : all) {
            String prefix = indicator.getCode();
            if (IndicatorRules.LEVEL_A.equals(indicator.getLevel())
                    && (indicator.getDirectionId() == null || !validDirectionIds.contains(indicator.getDirectionId()))) {
                errors.add(new FieldError(prefix + ".directionId", prefix + " 未归属本计划的 S"));
            }
            if (effectiveOwners.get(indicator.getId()) == null) {
                errors.add(new FieldError(prefix + ".ownerId", prefix + " 未指定校级项目负责人"));
            }
            if (!IndicatorRules.isQuantifiable(indicator.getLevel(), indicator.getIsQuantifiable())) {
                continue;
            }
            try {
                IndicatorRules.requireQuantitativeDefinition(indicator.getLevel(), indicator.getIsQuantifiable(),
                        indicator.getMeasureType(), indicator.getStatCaliber(), indicator.getUnitLabel(),
                        indicator.getTargetDirection(), indicator.getRollupMode());
            } catch (BizException e) {
                errors.add(new FieldError(prefix + ".quantitativeDefinition", prefix + "：" + e.getMessage()));
                continue;
            }
            Map<Integer, BigDecimal> targets = targetsByIndicator.getOrDefault(indicator.getId(), Map.of());
            if ("BOOLEAN".equals(indicator.getStatCaliber())) {
                long marked = targets.values().stream()
                        .filter(v -> v != null && v.compareTo(BigDecimal.ONE) == 0).count();
                if (marked != 1) {
                    errors.add(new FieldError(prefix + ".yearTargets", prefix + " 必须且只能指定一个计划完成年份"));
                }
            } else {
                for (int year = plan.getStartYear(); year <= plan.getEndYear(); year++) {
                    if (targets.get(year) == null) {
                        errors.add(new FieldError(prefix + ".yearTargets[" + year + "]",
                                prefix + " 缺少 " + year + " 年目标"));
                    }
                }
                if (indicator.getTotalTarget() == null) {
                    errors.add(new FieldError(prefix + ".totalTarget", prefix + " 未设置五年目标总量"));
                }
                if (("GROWTH".equals(indicator.getStatCaliber()) || "STOCK".equals(indicator.getStatCaliber()))
                        && indicator.getBaselineValue() == null) {
                    errors.add(new FieldError(prefix + ".baselineValue", prefix + " 基线值必填"));
                }
            }
            if (indicator.getTotalTarget() != null && IndicatorRules.requiresYearSum(indicator.getStatCaliber())) {
                BigDecimal sum = targets.values().stream().filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (sum.compareTo(indicator.getTotalTarget()) != 0) {
                    sumErrors.add(new FieldError(prefix + ".yearTargets",
                            prefix + " 各年度目标之和不等于五年总量"));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标体系不完整，不能定稿", errors);
        }
        if (!sumErrors.isEmpty()) {
            throw new BizException(ErrorCode.YEAR_TARGETS_SUM_INVALID,
                    ErrorCode.YEAR_TARGETS_SUM_INVALID.getMessage(), sumErrors);
        }
        // A → A+ 拆分守恒（AUTO_SUM 且非比率类口径）
        for (Indicator a : all.stream()
                .filter(i -> i.getParentId() == null
                        && IndicatorRules.isQuantifiable(i.getLevel(), i.getIsQuantifiable()))
                .toList()) {
            List<BigDecimal> childrenTotals = all.stream()
                    .filter(i -> a.getId().equals(i.getParentId()))
                    .map(Indicator::getTotalTarget).toList();
            try {
                IndicatorRules.requireAutoSumConservation(a.getStatCaliber(), a.getRollupMode(),
                        a.getTotalTarget(), childrenTotals);
            } catch (BizException e) {
                conservationErrors.add(new FieldError(a.getCode() + ".totalTarget", e.getMessage()));
            }
        }
        if (!conservationErrors.isEmpty()) {
            throw new BizException(ErrorCode.A_PLUS_SUM_INVALID,
                    ErrorCode.A_PLUS_SUM_INVALID.getMessage(), conservationErrors);
        }

        Long userId = SecurityUtils.getUserId();
        LocalDateTime now = LocalDateTime.now();
        plan.setFinalizedAt(now);
        plan.setFinalizedBy(userId);
        plan.setUpdatedBy(userId);
        if (planService.updatePlan(plan)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试");
        }
        indicatorMapper.update(null, new LambdaUpdateWrapper<Indicator>()
                .eq(Indicator::getPlanId, planId)
                .eq(Indicator::getStatus, "DRAFT")
                .eq(Indicator::getDeleted, 0L)
                .set(Indicator::getStatus, "FINALIZED")
                .set(Indicator::getUpdatedBy, userId));
        return planService.detail(planId);
    }

    // ---------------- 公共工具（供导入复用） ----------------

    public Indicator requireIndicator(Long indicatorId) {
        Indicator indicator = indicatorMapper.selectOne(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getId, indicatorId)
                .eq(Indicator::getDeleted, 0L));
        if (indicator == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "指标不存在");
        }
        return indicator;
    }

    public Set<String> existingCodes(Long planId) {
        return indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getPlanId, planId)
                        .eq(Indicator::getDeleted, 0L)
                        .select(Indicator::getCode))
                .stream().map(Indicator::getCode).collect(Collectors.toSet());
    }

    public Map<Long, Map<Integer, BigDecimal>> loadTargets(List<Long> indicatorIds) {
        Map<Long, Map<Integer, BigDecimal>> result = new java.util.HashMap<>();
        if (indicatorIds.isEmpty()) {
            return result;
        }
        yearTargetMapper.selectList(new LambdaQueryWrapper<IndicatorYearTarget>()
                        .in(IndicatorYearTarget::getIndicatorId, indicatorIds)
                        .eq(IndicatorYearTarget::getDeleted, 0L))
                .forEach(t -> result.computeIfAbsent(t.getIndicatorId(), k -> new java.util.HashMap<>())
                        .put(t.getYear(), t.getTarget()));
        return result;
    }

    public void insertYearTargetRows(Long indicatorId, Map<Integer, BigDecimal> targets, Long userId) {
        for (Map.Entry<Integer, BigDecimal> entry : targets.entrySet()) {
            IndicatorYearTarget row = new IndicatorYearTarget();
            row.setIndicatorId(indicatorId);
            row.setYear(entry.getKey());
            row.setTarget(entry.getValue());
            row.setCreatedBy(userId);
            row.setUpdatedBy(userId);
            yearTargetMapper.insert(row);
        }
    }

    public void upsertYearTargetRows(Long indicatorId, Map<Integer, BigDecimal> targets, Long userId) {
        Map<Integer, IndicatorYearTarget> existing = yearTargetMapper.selectList(
                        new LambdaQueryWrapper<IndicatorYearTarget>()
                                .eq(IndicatorYearTarget::getIndicatorId, indicatorId)
                                .eq(IndicatorYearTarget::getDeleted, 0L))
                .stream().collect(Collectors.toMap(IndicatorYearTarget::getYear, Function.identity()));
        List<IndicatorYearTarget> toInsert = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> entry : targets.entrySet()) {
            IndicatorYearTarget row = existing.get(entry.getKey());
            if (row == null) {
                row = new IndicatorYearTarget();
                row.setIndicatorId(indicatorId);
                row.setYear(entry.getKey());
                row.setTarget(entry.getValue());
                row.setCreatedBy(userId);
                row.setUpdatedBy(userId);
                toInsert.add(row);
            } else if (row.getTarget() == null ? entry.getValue() != null
                    : entry.getValue() == null || row.getTarget().compareTo(entry.getValue()) != 0) {
                row.setTarget(entry.getValue());
                row.setUpdatedBy(userId);
                yearTargetMapper.updateById(row);
            }
        }
        toInsert.forEach(yearTargetMapper::insert);
    }

    private void softDeleteYearTargets(Long indicatorId, Long userId) {
        yearTargetMapper.selectList(new LambdaQueryWrapper<IndicatorYearTarget>()
                        .eq(IndicatorYearTarget::getIndicatorId, indicatorId)
                        .eq(IndicatorYearTarget::getDeleted, 0L))
                .forEach(target -> {
                    target.setDeleted(target.getId());
                    target.setUpdatedBy(userId);
                    yearTargetMapper.updateById(target);
                });
    }

    public OrgUnit requireUnit(Long unitId) {
        OrgUnit unit = orgUnitMapper.selectOne(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getId, unitId)
                .eq(OrgUnit::getIsEnabled, true)
                .eq(OrgUnit::getDeleted, 0L));
        if (unit == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "执行单元不存在或已停用");
        }
        return unit;
    }

    /** 更新指标（MP 乐观锁，返回受影响行数；0 即版本冲突）——供分解矩阵等模块推进版本号 */
    public int updateIndicator(Indicator indicator) {
        return indicatorMapper.updateById(indicator);
    }

    /** 批量按 id 查指标（供填报等模块装配引用对象） */
    public List<Indicator> findByIds(List<Long> indicatorIds) {
        if (indicatorIds == null || indicatorIds.isEmpty()) {
            return List.of();
        }
        return indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .in(Indicator::getId, indicatorIds)
                .eq(Indicator::getDeleted, 0L));
    }

    /** 有效负责人：A+ 未设置时沿用上级 A */
    public Long effectiveOwnerOf(Indicator indicator) {
        if (indicator.getOwnerId() != null || indicator.getParentId() == null) {
            return indicator.getOwnerId();
        }
        Indicator parent = indicatorMapper.selectOne(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getId, indicator.getParentId())
                .eq(Indicator::getDeleted, 0L));
        return parent == null ? null : parent.getOwnerId();
    }

    // ---------------- 内部 ----------------

    /** 数据范围过滤：全校 → null；SCHOOL_OWNER → 本人 id；仅 UNIT_OWNER → -1（阶段 2 不可见） */
    private Long scopeOwnerFilter() {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isAllData(user)) {
            return null;
        }
        if (dataScopeService.isOwnerScope(user)) {
            return user.getUserId();
        }
        return -1L;
    }

    private Map<Long, Long> effectiveOwners(List<Indicator> all) {
        Map<Long, Indicator> byId = all.stream().collect(Collectors.toMap(Indicator::getId, Function.identity()));
        Map<Long, Long> result = new java.util.HashMap<>();
        for (Indicator indicator : all) {
            Long owner = indicator.getOwnerId();
            if (owner == null && indicator.getParentId() != null) {
                Indicator parent = byId.get(indicator.getParentId());
                owner = parent == null ? null : parent.getOwnerId();
            }
            result.put(indicator.getId(), owner);
        }
        return result;
    }

    private void requireDetailAccess(Indicator indicator) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isAllData(user)
                || (dataScopeService.isOwnerScope(user)
                && user.getUserId().equals(effectiveOwnerOf(indicator)))) {
            return;
        }
        if (dataScopeService.isUnitScope(user) && user.getUnitId() != null) {
            Long count = taskMapper.selectCount(taskRelationQuery(indicator)
                    .eq(Task::getUnitId, user.getUnitId()));
            if (count != null && count > 0) {
                return;
            }
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    private List<Task> visibleTasks(Indicator indicator) {
        LoginUser user = SecurityUtils.getLoginUser();
        LambdaQueryWrapper<Task> query = taskRelationQuery(indicator);
        boolean ownerAccess = dataScopeService.isAllData(user)
                || (dataScopeService.isOwnerScope(user)
                && user.getUserId().equals(effectiveOwnerOf(indicator)));
        if (!ownerAccess) {
            if (!dataScopeService.isUnitScope(user) || user.getUnitId() == null) {
                return List.of();
            }
            query.eq(Task::getUnitId, user.getUnitId());
        }
        return taskMapper.selectList(query.orderByAsc(Task::getCode));
    }

    private LambdaQueryWrapper<Task> taskRelationQuery(Indicator indicator) {
        LambdaQueryWrapper<Task> query = new LambdaQueryWrapper<Task>()
                .eq(Task::getDeleted, 0L);
        if (IndicatorRules.LEVEL_A_PLUS.equals(indicator.getLevel())) {
            return query.eq(Task::getIndicatorId, indicator.getId());
        }
        List<Long> childIds = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                        .eq(Indicator::getParentId, indicator.getId())
                        .eq(Indicator::getDeleted, 0L)
                        .select(Indicator::getId))
                .stream().map(Indicator::getId).toList();
        return query.and(wrapper -> {
            wrapper.eq(Task::getIndicatorId, indicator.getId())
                    .or().eq(Task::getTopIndicatorId, indicator.getId());
            if (!childIds.isEmpty()) {
                wrapper.or().in(Task::getIndicatorId, childIds);
            }
        });
    }

    private void clearQuantitativeFields(Indicator indicator) {
        indicator.setMeasureType(null);
        indicator.setStatCaliber(null);
        indicator.setUnitLabel(null);
        indicator.setTargetDirection(null);
        indicator.setRollupMode(null);
        indicator.setBaselineValue(null);
        indicator.setTotalTarget(null);
    }

    private Map<Long, Direction> directionMap(List<Indicator> indicators) {
        Set<Long> directionIds = indicators.stream().map(Indicator::getDirectionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (directionIds.isEmpty()) {
            return Map.of();
        }
        return directionMapper.selectList(new LambdaQueryWrapper<Direction>()
                        .in(Direction::getId, directionIds)
                        .eq(Direction::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Direction::getId, Function.identity()));
    }

    private Direction requireDirectionInPlan(Long directionId, Long planId) {
        if (directionId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "A 类指标必须指定主要建设方向");
        }
        Direction direction = directionMapper.selectOne(new LambdaQueryWrapper<Direction>()
                .eq(Direction::getId, directionId)
                .eq(Direction::getDeleted, 0L));
        if (direction == null || !direction.getPlanId().equals(planId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "建设方向不存在或不属于本计划");
        }
        return direction;
    }

    private void requireUserIfPresent(Long userId) {
        if (userId != null && !userRefResolver.mapOf(List.of(userId)).containsKey(userId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "负责人不存在");
        }
    }

    private void requireAdmin() {
        if (!SecurityUtils.hasRole("ADMIN")) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有 ADMIN 能配置 A 的 S 归属");
        }
    }

    private void validateEnums(String level, String nature, String measureType, String statCaliber,
                               String targetDirection, String rollupMode) {
        requireEnum(level, Set.of("A", "A_PLUS"), "层级");
        requireEnum(nature, Set.of("CONSTRAINT", "DEVELOPMENT"), "指标性质");
        if (StringUtils.hasText(measureType)) {
            requireEnum(measureType, Set.of("NUMBER", "BOOLEAN", "PERCENT_QUANTIFIABLE", "PERCENT_DIRECT"), "计量类型");
        }
        if (StringUtils.hasText(statCaliber)) {
            requireEnum(statCaliber, Set.of("INCREMENT", "STOCK", "GROWTH", "RATIO", "DIRECT_PERCENT", "BOOLEAN"), "统计口径");
        }
        if (StringUtils.hasText(targetDirection)) {
            requireEnum(targetDirection, Set.of("HIGHER_BETTER", "LOWER_BETTER"), "达成方向");
        }
        if (StringUtils.hasText(rollupMode)) {
            requireEnum(rollupMode, Set.of("AUTO_SUM", "MANUAL"), "汇总方式");
        }
    }

    private void requireEnum(String value, Set<String> allowed, String label) {
        if (value == null || !allowed.contains(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, label + "取值不合法：" + value);
        }
    }

    private IndicatorDtos.TreeNode toNode(Indicator indicator, Map<Long, Direction> directions,
                                          Map<Long, Long> effectiveOwners, Map<Long, SysUser> users,
                                          Map<Long, List<IndicatorDtos.YearTargetView>> targetViews,
                                          List<IndicatorDtos.TreeNode> children) {
        Direction direction = indicator.getDirectionId() == null ? null : directions.get(indicator.getDirectionId());
        boolean quantifiable = IndicatorRules.isQuantifiable(indicator.getLevel(), indicator.getIsQuantifiable());
        return new IndicatorDtos.TreeNode(indicator.getId(), indicator.getDirectionId(),
                direction == null ? null : direction.getName(),
                indicator.getParentId(), indicator.getLevel(), indicator.getCode(), indicator.getName(),
                indicator.getNature(), quantifiable ? indicator.getMeasureType() : null,
                quantifiable ? indicator.getStatCaliber() : null,
                quantifiable ? indicator.getUnitLabel() : null,
                quantifiable ? indicator.getTargetDirection() : null,
                quantifiable ? indicator.getRollupMode() : null,
                indicator.getIsQuantifiable(),
                userRefResolver.refOf(effectiveOwners.get(indicator.getId()), users),
                quantifiable ? indicator.getBaselineValue() : null,
                quantifiable ? indicator.getTotalTarget() : null,
                quantifiable ? targetViews.getOrDefault(indicator.getId(), List.of()) : List.of(),
                indicator.getStatus(), indicator.getSort(), indicator.getVersion(), children);
    }

    private IndicatorDtos.Detail toDetail(Indicator indicator) {
        List<Indicator> siblingsContext = indicatorMapper.selectList(new LambdaQueryWrapper<Indicator>()
                .eq(Indicator::getPlanId, indicator.getPlanId())
                .eq(Indicator::getDeleted, 0L));
        Map<Long, Indicator> byId = siblingsContext.stream()
                .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        Map<Long, Long> effectiveOwners = effectiveOwners(siblingsContext);
        List<Task> tasks = visibleTasks(indicator);
        Set<Long> userIds = effectiveOwners.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        tasks.stream().map(Task::getOwnerId).filter(Objects::nonNull).forEach(userIds::add);
        Map<Long, SysUser> users = userRefResolver.mapOf(userIds);
        Direction direction = indicator.getDirectionId() == null ? null
                : directionMapper.selectById(indicator.getDirectionId());

        Indicator parent = indicator.getParentId() == null ? null : byId.get(indicator.getParentId());
        IndicatorDtos.IndicatorRef parentRef = parent == null ? null
                : new IndicatorDtos.IndicatorRef(parent.getId(), parent.getCode(), parent.getName());
        Set<Long> unitIds = tasks.stream().map(Task::getUnitId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, OrgUnit> units = unitIds.isEmpty() ? Map.of() : orgUnitMapper.selectList(
                        new LambdaQueryWrapper<OrgUnit>().in(OrgUnit::getId, unitIds))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
        List<IndicatorDtos.BSummary> bSummaries = tasks.stream()
                .map(task -> {
                    OrgUnit unit = task.getUnitId() == null ? null : units.get(task.getUnitId());
                    IndicatorDtos.UnitRef unitRef = task.getUnitId() == null ? null
                            : new IndicatorDtos.UnitRef(task.getUnitId(), unit == null ? null : unit.getCode(),
                            unit == null ? null : unit.getName());
                    return new IndicatorDtos.BSummary(task.getId(), "B", task.getCode(), unitRef,
                            userRefResolver.refOf(task.getOwnerId(), users), task.getStatus(),
                            Boolean.TRUE.equals(task.getIsHistorical()));
                })
                .toList();

        boolean quantifiable = IndicatorRules.isQuantifiable(indicator.getLevel(), indicator.getIsQuantifiable());
        Map<Integer, BigDecimal> targets = quantifiable
                ? loadTargets(List.of(indicator.getId())).getOrDefault(indicator.getId(), Map.of()) : Map.of();
        List<IndicatorDtos.YearTargetView> yearTargets = targets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new IndicatorDtos.YearTargetView(e.getKey(), toTargetView(indicator.getStatCaliber(), e.getValue())))
                .toList();
        List<IndicatorDtos.APlusSummary> children = siblingsContext.stream()
                .filter(i -> indicator.getId().equals(i.getParentId()))
                .sorted(Comparator.comparing(Indicator::getSort).thenComparing(Indicator::getCode))
                .map(c -> new IndicatorDtos.APlusSummary(c.getId(), c.getCode(), c.getName(), c.getStatCaliber(),
                        userRefResolver.refOf(effectiveOwners.get(c.getId()), users),
                        c.getTotalTarget(), c.getStatus()))
                .toList();
        return new IndicatorDtos.Detail(indicator.getId(), indicator.getPlanId(), indicator.getDirectionId(),
                direction == null ? null : direction.getName(),
                indicator.getParentId(), parentRef, indicator.getLevel(), indicator.getCode(), indicator.getName(),
                indicator.getNature(), quantifiable ? indicator.getMeasureType() : null,
                quantifiable ? indicator.getStatCaliber() : null,
                quantifiable ? indicator.getUnitLabel() : null,
                quantifiable ? indicator.getTargetDirection() : null,
                quantifiable ? indicator.getRollupMode() : null,
                indicator.getIsQuantifiable(), indicator.getDescription(),
                quantifiable ? indicator.getEvidenceRequirement() : null,
                quantifiable ? indicator.getMinFileCount() : null,
                quantifiable ? indicator.getBaselineValue() : null,
                quantifiable ? indicator.getTotalTarget() : null,
                userRefResolver.refOf(effectiveOwners.get(indicator.getId()), users),
                indicator.getSort(), indicator.getStatus(), yearTargets, children, bSummaries,
                indicator.getVersion(), indicator.getCreatedAt(), indicator.getUpdatedAt());
    }

    /** BOOLEAN 口径：库存 1/NULL，接口出参 true/null（API 约定 3.6） */
    private Object toTargetView(String statCaliber, BigDecimal target) {
        if ("BOOLEAN".equals(statCaliber)) {
            return target != null && target.compareTo(BigDecimal.ONE) == 0 ? Boolean.TRUE : null;
        }
        return target;
    }
}
