package edu.fzrj.metrics.module.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.entity.UserUnit;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.mapper.UserUnitMapper;
import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import edu.fzrj.metrics.module.training.entity.TrainingProject;
import edu.fzrj.metrics.module.training.entity.TrainingReview;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.mapper.TrainingProjectMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingReviewMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TrainingService {

    private static final int MAX_PLAN_SPAN_YEARS = 9;
    private static final Set<String> EDITABLE_STATUSES = Set.of("DRAFT", "REJECTED");

    private final PlanMapper planMapper;
    private final TrainingSheetMapper sheetMapper;
    private final TrainingProjectMapper projectMapper;
    private final TrainingReviewMapper reviewMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserUnitMapper userUnitMapper;
    private final ObjectMapper objectMapper;
    private final TrainingExcelService excelService;

    public TrainingService(PlanMapper planMapper, TrainingSheetMapper sheetMapper,
                           TrainingProjectMapper projectMapper, TrainingReviewMapper reviewMapper,
                           OrgUnitMapper orgUnitMapper, SysUserMapper sysUserMapper,
                           UserRoleMapper userRoleMapper, UserUnitMapper userUnitMapper,
                           ObjectMapper objectMapper, TrainingExcelService excelService) {
        this.planMapper = planMapper;
        this.sheetMapper = sheetMapper;
        this.projectMapper = projectMapper;
        this.reviewMapper = reviewMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.userUnitMapper = userUnitMapper;
        this.objectMapper = objectMapper;
        this.excelService = excelService;
    }

    @Transactional
    public TrainingDtos.Detail createPlan(TrainingDtos.CreatePlanRequest request) {
        String name = request.getName().trim();
        Plan existing = planMapper.selectOne(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getName, name)
                .eq(Plan::getPlanType, "TRAINING")
                .eq(Plan::getDeleted, 0L)
                .orderByAsc(Plan::getId)
                .last("LIMIT 1"));
        if (existing != null) {
            TrainingSheet sheet = sheetMapper.selectOne(new LambdaQueryWrapper<TrainingSheet>()
                    .eq(TrainingSheet::getPlanId, existing.getId())
                    .eq(TrainingSheet::getDeleted, 0L));
            if (sheet == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "培训计划缺少采集表");
            }
            return detail(sheet.getId());
        }

        validateYears(request.getStartYear(), request.getEndYear(), request.getBaselineYear());
        validateUnitOwner(request.getUnitId(), request.getOwnerId());
        Long userId = SecurityUtils.getUserId();
        Plan plan = new Plan();
        plan.setName(name);
        plan.setPlanType("TRAINING");
        plan.setStartYear(request.getStartYear());
        plan.setEndYear(request.getEndYear());
        plan.setBaselineYear(request.getBaselineYear() == null
                ? request.getStartYear() - 1 : request.getBaselineYear());
        plan.setSettleDeadline("01-15");
        plan.setStatus("DRAFT");
        plan.setSource("BLANK");
        plan.setIsReceiverConfirmRequired(false);
        plan.setCreatedBy(userId);
        plan.setUpdatedBy(userId);
        planMapper.insert(plan);

        TrainingSheet sheet = new TrainingSheet();
        sheet.setPlanId(plan.getId());
        sheet.setUnitId(request.getUnitId());
        sheet.setOwnerId(request.getOwnerId());
        sheet.setStatus("DRAFT");
        sheet.setRevisionNo(0);
        sheet.setCreatedBy(userId);
        sheet.setUpdatedBy(userId);
        sheetMapper.insert(sheet);
        return detail(sheet.getId());
    }

    public List<TrainingDtos.SheetSummary> list(Long planId) {
        LoginUser user = SecurityUtils.getLoginUser();
        boolean admin = user.getRoles().contains("ADMIN");
        boolean leader = user.getRoles().contains("LEADER")
                && user.getPermissions().contains("submission:review");
        boolean unitOwner = user.getRoles().contains("UNIT_OWNER");
        boolean staff = user.getRoles().contains("STAFF");
        if (!admin && !leader && !unitOwner && !staff) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        if (!admin && !leader && user.getUnitIds().isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<TrainingSheet> query = new LambdaQueryWrapper<TrainingSheet>()
                .eq(TrainingSheet::getDeleted, 0L)
                .eq(planId != null, TrainingSheet::getPlanId, planId)
                .orderByDesc(TrainingSheet::getUpdatedAt)
                .orderByDesc(TrainingSheet::getId);
        if (!admin && !leader) {
            query.in(TrainingSheet::getUnitId, user.getUnitIds());
            if (!staff) {
                query.eq(TrainingSheet::getOwnerId, user.getUserId());
            }
        }
        return summaries(sheetMapper.selectList(query));
    }

    public List<TrainingDtos.SheetSummary> summaries(List<TrainingSheet> sheets) {
        if (sheets.isEmpty()) {
            return List.of();
        }
        List<Long> planIds = sheets.stream().map(TrainingSheet::getPlanId).distinct().toList();
        Map<Long, Plan> plans = planMapper.selectBatchIds(planIds).stream()
                .collect(Collectors.toMap(Plan::getId, Function.identity()));
        List<Long> sheetIds = sheets.stream().map(TrainingSheet::getId).toList();
        Map<Long, List<TrainingProject>> projects = projectMapper.selectList(new LambdaQueryWrapper<TrainingProject>()
                        .in(TrainingProject::getSheetId, sheetIds)
                        .eq(TrainingProject::getDeleted, 0L))
                .stream().collect(Collectors.groupingBy(TrainingProject::getSheetId));
        List<Long> unitIds = sheets.stream().map(TrainingSheet::getUnitId).distinct().toList();
        Map<Long, String> unitNames = orgUnitMapper.selectBatchIds(unitIds).stream()
                .collect(Collectors.toMap(OrgUnit::getId, OrgUnit::getName));
        List<Long> userIds = sheets.stream()
                .flatMap(sheet -> Stream.of(sheet.getOwnerId(), sheet.getSubmittedBy()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getName));
        LoginUser user = SecurityUtils.getLoginUser();
        return sheets.stream().map(sheet -> {
            Plan plan = plans.get(sheet.getPlanId());
            if (plan == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "培训计划不存在");
            }
            List<TrainingProject> sheetProjects = projects.getOrDefault(sheet.getId(), List.of());
            boolean archived = "ARCHIVED".equals(plan.getStatus());
            boolean canReview = !archived
                    && sheetProjects.stream().anyMatch(project -> canReview(sheet, project, user));
            return new TrainingDtos.SheetSummary(sheet.getId(), sheet.getPlanId(), plan.getName(),
                    plan.getStartYear(), plan.getEndYear(), plan.getBaselineYear(), sheet.getUnitId(),
                    sheet.getOwnerId(), sheet.getStatus(), sheetProjects.size(),
                    sheet.getVersion(), sheet.getUpdatedAt(), unitNames.get(sheet.getUnitId()),
                    userNames.get(sheet.getOwnerId()), sheet.getSubmitterRole(), sheet.getCurrentReviewNode(),
                    sheet.getRevisionNo(), sheet.getSubmittedBy(), userNames.get(sheet.getSubmittedBy()),
                    sheet.getSubmittedAt(), !archived && canEdit(sheet, user), canReview);
        }).toList();
    }

    public TrainingDtos.Detail detail(Long sheetId) {
        TrainingSheet sheet = requireSheet(sheetId);
        requireReadAccess(sheet);
        return buildDetail(sheet, loadProjects(sheetId));
    }

    @Transactional
    public TrainingDtos.Detail saveProjects(Long sheetId, TrainingDtos.SaveProjectsRequest request) {
        TrainingSheet sheet = requireSheetForUpdate(sheetId);
        requireEditAccess(sheet);
        requireVersion(sheet, request.getVersion());
        LoginUser user = SecurityUtils.getLoginUser();
        List<TrainingProject> existing = loadProjects(sheetId);
        Map<Long, TrainingProject> existingById = existing.stream()
                .collect(Collectors.toMap(TrainingProject::getId, Function.identity()));
        List<FieldError> errors = new ArrayList<>();
        Set<Long> requestedIds = new HashSet<>();
        Set<Integer> rowNumbers = new HashSet<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        List<String> jsonRows = new ArrayList<>();
        for (int i = 0; i < request.getProjects().size(); i++) {
            TrainingDtos.ProjectInput input = request.getProjects().get(i);
            TrainingProject row = input.getId() == null ? null : existingById.get(input.getId());
            if (input.getId() != null) {
                if (!requestedIds.add(input.getId())) {
                    errors.add(new FieldError("projects[" + i + "].id", "项目 id 不能重复"));
                } else if (row == null) {
                    errors.add(new FieldError("projects[" + i + "].id", "项目不属于当前采集表"));
                }
            }
            if (input.getRowNo() == null || input.getRowNo() < 1 || !rowNumbers.add(input.getRowNo())) {
                errors.add(new FieldError("projects[" + i + "].rowNo", "项目行号必须大于 0 且不能重复"));
            }
            boolean editable = row == null || EDITABLE_STATUSES.contains(row.getStatus());
            String reporter = editable ? user.getName()
                    : readData(row).get("c57") instanceof String name ? name : null;
            TrainingFieldSchema.ValidationResult result = TrainingFieldSchema.validate(
                    input.getData(), reporter, false, "projects[" + i + "].data");
            errors.addAll(result.errors());
            normalized.add(result.data());
            jsonRows.add(writeData(result.data()));
            if (row != null && !editable && (!Objects.equals(row.getRowNo(), input.getRowNo())
                    || !sameData(readData(row), result.data()))) {
                errors.add(new FieldError("projects[" + i + "].data", "待审核或已通过项目不能修改"));
            }
        }
        for (TrainingProject row : existing) {
            if (!requestedIds.contains(row.getId()) && !EDITABLE_STATUSES.contains(row.getStatus())) {
                errors.add(new FieldError("projects", "待审核或已通过项目不能删除"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "培训项目数据校验失败", errors);
        }

        boolean changed = false;
        for (TrainingProject row : existing) {
            if (!requestedIds.contains(row.getId())) {
                row.setDeleted(row.getId());
                row.setUpdatedBy(user.getUserId());
                projectMapper.updateById(row);
                changed = true;
            }
        }
        for (int i = 0; i < request.getProjects().size(); i++) {
            TrainingDtos.ProjectInput input = request.getProjects().get(i);
            TrainingProject row = input.getId() == null ? null : existingById.get(input.getId());
            if (row == null) {
                row = new TrainingProject();
                row.setSheetId(sheetId);
                row.setRowNo(input.getRowNo());
                row.setDataJson(jsonRows.get(i));
                row.setStatus("DRAFT");
                row.setRevisionNo(0);
                row.setCreatedBy(user.getUserId());
                row.setUpdatedBy(user.getUserId());
                projectMapper.insert(row);
                changed = true;
            } else if (EDITABLE_STATUSES.contains(row.getStatus())
                    && (!Objects.equals(row.getRowNo(), input.getRowNo())
                    || !sameData(readData(row), normalized.get(i)))) {
                row.setRowNo(input.getRowNo());
                row.setDataJson(jsonRows.get(i));
                row.setUpdatedBy(user.getUserId());
                projectMapper.updateById(row);
                changed = true;
            }
        }
        if (changed) {
            refreshSheetAggregate(sheet, user.getUserId());
        }
        return detail(sheetId);
    }

    @Transactional
    public TrainingDtos.Detail submit(Long sheetId, TrainingDtos.VersionRequest request) {
        TrainingSheet sheet = requireSheetForUpdate(sheetId);
        requireEditAccess(sheet);
        requireVersion(sheet, request.getVersion());
        LoginUser user = SecurityUtils.getLoginUser();
        Set<Long> projectIds = new HashSet<>(request.getProjectIds());
        if (projectIds.size() != request.getProjectIds().size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "培训项目不能重复选择");
        }
        List<TrainingProject> projects = loadProjects(sheetId).stream()
                .filter(project -> projectIds.contains(project.getId())).toList();
        if (projects.isEmpty() || projects.size() != projectIds.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所选培训项目不存在或不属于当前采集表");
        }
        List<FieldError> errors = new ArrayList<>();
        List<TrainingFieldSchema.ValidationResult> results = new ArrayList<>();
        for (int i = 0; i < projects.size(); i++) {
            TrainingProject project = projects.get(i);
            if (!EDITABLE_STATUSES.contains(project.getStatus())) {
                throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "待审核或已通过项目不能重复提交");
            }
            TrainingFieldSchema.ValidationResult result = TrainingFieldSchema.validate(
                    readData(project), user.getName(), true, "projects[" + i + "].data");
            errors.addAll(result.errors());
            results.add(result);
        }
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "培训项目不完整或校验未通过", errors);
        }
        String submitterRole = resolveSubmitterRole(user);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < projects.size(); i++) {
            TrainingProject project = projects.get(i);
            project.setDataJson(writeData(results.get(i).data()));
            project.setStatus("PENDING_REVIEW");
            project.setSubmitterRole(submitterRole);
            project.setCurrentReviewNode("STAFF".equals(submitterRole) ? "UNIT_REVIEW" : "LEADER_REVIEW");
            project.setRevisionNo(project.getRevisionNo() + 1);
            project.setSubmittedAt(now);
            project.setSubmittedBy(user.getUserId());
            project.setUpdatedBy(user.getUserId());
            projectMapper.updateById(project);
        }
        refreshSheetAggregate(sheet, user.getUserId());
        return detail(sheetId);
    }

    public TrainingService.ExportFile export(Long sheetId) {
        TrainingDtos.Detail detail = detail(sheetId);
        List<TrainingDtos.Project> approved = detail.projects().stream()
                .filter(project -> "APPROVED".equals(project.status())).toList();
        if (approved.isEmpty()) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "暂无审核通过的培训项目可导出");
        }
        TrainingDtos.Detail approvedDetail = new TrainingDtos.Detail(
                detail.sheet(), detail.fields(), approved, detail.reviews());
        return new TrainingService.ExportFile("职业培训信息表-" + detail.sheet().planName() + ".xlsx",
                excelService.build(approvedDetail));
    }

    private TrainingDtos.Detail buildDetail(TrainingSheet sheet, List<TrainingProject> projects) {
        LoginUser user = SecurityUtils.getLoginUser();
        TrainingDtos.SheetSummary summary = summaries(List.of(sheet)).get(0);
        List<TrainingReview> history = reviewMapper.selectList(new LambdaQueryWrapper<TrainingReview>()
                .eq(TrainingReview::getSheetId, sheet.getId())
                .orderByAsc(TrainingReview::getRevisionNo)
                .orderByAsc(TrainingReview::getCreatedAt)
                .orderByAsc(TrainingReview::getId));
        Map<Long, TrainingReview> latestByProject = new LinkedHashMap<>();
        for (TrainingReview review : history) {
            latestByProject.put(review.getProjectId(), review);
        }
        List<TrainingDtos.Project> items = projects.stream()
                .map(project -> new TrainingDtos.Project(project.getId(), project.getRowNo(), readData(project),
                        project.getStatus(), project.getSubmitterRole(), project.getCurrentReviewNode(),
                        project.getRevisionNo(), project.getSubmittedBy(), project.getSubmittedAt(),
                        summary.canEdit() && canEdit(sheet, project, user),
                        summary.canReview() && canReview(sheet, project, user),
                        latestByProject.containsKey(project.getId())
                                ? latestByProject.get(project.getId()).getComment() : null))
                .toList();
        List<Long> reviewerIds = history.stream().map(TrainingReview::getReviewerId).distinct().toList();
        Map<Long, String> reviewerNames = reviewerIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(reviewerIds).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getName));
        Map<Long, Integer> rowNumbers = projects.stream()
                .collect(Collectors.toMap(TrainingProject::getId, TrainingProject::getRowNo));
        List<TrainingDtos.ReviewItem> reviews = history.stream()
                .map(review -> new TrainingDtos.ReviewItem(review.getId(), review.getProjectId(),
                        rowNumbers.get(review.getProjectId()), review.getRevisionNo(),
                        review.getReviewNode(), review.getResult(), review.getComment(), review.getReviewerId(),
                        reviewerNames.get(review.getReviewerId()), Boolean.TRUE.equals(review.getIsProxy()),
                        review.getProxyReason(), review.getCreatedAt()))
                .toList();
        return new TrainingDtos.Detail(summary, TrainingFieldSchema.fields(), items, reviews);
    }

    private List<TrainingProject> loadProjects(Long sheetId) {
        return projectMapper.selectList(new LambdaQueryWrapper<TrainingProject>()
                .eq(TrainingProject::getSheetId, sheetId)
                .eq(TrainingProject::getDeleted, 0L)
                .orderByAsc(TrainingProject::getRowNo)
                .orderByAsc(TrainingProject::getId));
    }

    public Map<String, Object> auditSnapshot(Long sheetId) {
        TrainingSheet sheet = requireSheet(sheetId);
        requireReadAccess(sheet);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", sheet.getStatus());
        snapshot.put("submitterRole", sheet.getSubmitterRole());
        snapshot.put("currentReviewNode", sheet.getCurrentReviewNode());
        snapshot.put("revisionNo", sheet.getRevisionNo());
        snapshot.put("version", sheet.getVersion());
        snapshot.put("projects", loadProjects(sheetId).stream()
                .map(project -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("id", project.getId());
                    item.put("rowNo", project.getRowNo());
                    item.put("status", project.getStatus());
                    item.put("currentReviewNode", project.getCurrentReviewNode());
                    item.put("revisionNo", project.getRevisionNo());
                    item.put("data", readData(project));
                    return item;
                })
                .toList());
        return snapshot;
    }

    private TrainingSheet requireSheet(Long sheetId) {
        TrainingSheet sheet = sheetMapper.selectOne(new LambdaQueryWrapper<TrainingSheet>()
                .eq(TrainingSheet::getId, sheetId)
                .eq(TrainingSheet::getDeleted, 0L));
        if (sheet == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训采集表不存在");
        }
        return sheet;
    }

    TrainingSheet requireSheetForUpdate(Long sheetId) {
        TrainingSheet located = requireSheet(sheetId);
        Plan plan = planMapper.selectByIdForUpdate(located.getPlanId());
        if (plan == null || !"TRAINING".equals(plan.getPlanType())) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训计划不存在");
        }
        if ("ARCHIVED".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_ARCHIVED);
        }
        TrainingSheet sheet = sheetMapper.selectByIdForUpdate(sheetId);
        if (sheet == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训采集表不存在");
        }
        return sheet;
    }

    Plan requireTrainingPlan(Long planId) {
        Plan plan = planMapper.selectOne(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getId, planId)
                .eq(Plan::getPlanType, "TRAINING")
                .eq(Plan::getDeleted, 0L));
        if (plan == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训计划不存在");
        }
        return plan;
    }

    private void requireReadAccess(TrainingSheet sheet) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (user.getRoles().contains("ADMIN")
                || user.getRoles().contains("LEADER") && user.getPermissions().contains("submission:review")) {
            return;
        }
        boolean sameUnit = user.getUnitIds().contains(sheet.getUnitId());
        if (sameUnit && (user.getRoles().contains("UNIT_OWNER") || user.getRoles().contains("STAFF"))) {
            return;
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    private void requireEditAccess(TrainingSheet sheet) {
        if (!canFill(sheet, SecurityUtils.getLoginUser())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
    }

    private boolean canFill(TrainingSheet sheet, LoginUser user) {
        if (user.getRoles().contains("ADMIN")) {
            return true;
        }
        if (!user.getUnitIds().contains(sheet.getUnitId())) {
            return false;
        }
        return user.getRoles().contains("STAFF")
                || user.getRoles().contains("UNIT_OWNER") && user.getUserId().equals(sheet.getOwnerId());
    }

    public boolean canEdit(TrainingSheet sheet, LoginUser user) {
        return canFill(sheet, user);
    }

    public boolean canEdit(TrainingSheet sheet, TrainingProject project, LoginUser user) {
        return EDITABLE_STATUSES.contains(project.getStatus()) && canFill(sheet, user);
    }

    public boolean canReview(TrainingSheet sheet, LoginUser user) {
        return loadProjects(sheet.getId()).stream().anyMatch(project -> canReview(sheet, project, user));
    }

    public boolean canReview(TrainingSheet sheet, TrainingProject project, LoginUser user) {
        if (!"PENDING_REVIEW".equals(project.getStatus()) || project.getCurrentReviewNode() == null
                || !user.getPermissions().contains("submission:review")) {
            return false;
        }
        if ("UNIT_REVIEW".equals(project.getCurrentReviewNode())
                && Objects.equals(user.getUserId(), project.getSubmittedBy())) {
            return false;
        }
        if (user.getRoles().contains("ADMIN")) {
            return true;
        }
        if ("UNIT_REVIEW".equals(project.getCurrentReviewNode())) {
            return "STAFF".equals(project.getSubmitterRole())
                    && user.getRoles().contains("UNIT_OWNER")
                    && user.getUnitIds().contains(sheet.getUnitId());
        }
        return "LEADER_REVIEW".equals(project.getCurrentReviewNode())
                && user.getRoles().contains("LEADER");
    }

    TrainingProject requireProject(Long sheetId, Long projectId) {
        TrainingProject project = projectMapper.selectOne(new LambdaQueryWrapper<TrainingProject>()
                .eq(TrainingProject::getId, projectId)
                .eq(TrainingProject::getSheetId, sheetId)
                .eq(TrainingProject::getDeleted, 0L));
        if (project == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训项目不存在");
        }
        return project;
    }

    void refreshSheetAggregate(TrainingSheet sheet, Long operatorId) {
        List<TrainingProject> projects = loadProjects(sheet.getId());
        boolean pending = projects.stream().anyMatch(project -> "PENDING_REVIEW".equals(project.getStatus()));
        boolean allApproved = !projects.isEmpty()
                && projects.stream().allMatch(project -> "APPROVED".equals(project.getStatus()));
        boolean rejected = projects.stream().anyMatch(project -> "REJECTED".equals(project.getStatus()));
        sheet.setStatus(pending ? "PENDING_REVIEW" : allApproved ? "APPROVED" : rejected ? "REJECTED" : "DRAFT");
        Set<String> nodes = projects.stream()
                .filter(project -> "PENDING_REVIEW".equals(project.getStatus()))
                .map(TrainingProject::getCurrentReviewNode).collect(Collectors.toSet());
        sheet.setCurrentReviewNode(pending && nodes.size() == 1 ? nodes.iterator().next() : null);
        sheet.setRevisionNo(projects.stream().map(TrainingProject::getRevisionNo)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0));
        TrainingProject latest = projects.stream().filter(project -> project.getSubmittedAt() != null)
                .max(Comparator.comparing(TrainingProject::getSubmittedAt)).orElse(null);
        sheet.setSubmitterRole(latest == null ? null : latest.getSubmitterRole());
        sheet.setSubmittedAt(latest == null ? null : latest.getSubmittedAt());
        sheet.setSubmittedBy(latest == null ? null : latest.getSubmittedBy());
        sheet.setUpdatedBy(operatorId);
        if (sheetMapper.updateById(sheet) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private String resolveSubmitterRole(LoginUser user) {
        boolean staffOnly = user.getRoles().contains("STAFF")
                && !user.getRoles().contains("UNIT_OWNER")
                && !user.getRoles().contains("ADMIN");
        return staffOnly ? "STAFF" : "UNIT_OWNER";
    }

    private void validateUnitOwner(Long unitId, Long ownerId) {
        OrgUnit unit = orgUnitMapper.selectOne(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getId, unitId).eq(OrgUnit::getDeleted, 0L));
        if (unit == null || !Boolean.TRUE.equals(unit.getIsEnabled())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "填报学院不存在或未启用");
        }
        SysUser owner = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, ownerId).eq(SysUser::getDeleted, 0L));
        if (owner == null || !Boolean.TRUE.equals(owner.getIsEnabled())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "填报负责人不存在或未启用");
        }
        Long roles = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, ownerId).eq(UserRole::getRole, "UNIT_OWNER"));
        if (roles == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "填报负责人必须具有学院项目负责人角色");
        }
        boolean member = unitId.equals(owner.getUnitId()) || userUnitMapper.selectCount(
                new LambdaQueryWrapper<UserUnit>().eq(UserUnit::getUserId, ownerId)
                        .eq(UserUnit::getUnitId, unitId)) > 0;
        if (!member) {
            throw new BizException(ErrorCode.PARAM_INVALID, "填报负责人不属于所选学院");
        }
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

    void requireVersion(TrainingSheet sheet, Integer expected) {
        if (!Objects.equals(sheet.getVersion(), expected)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT);
        }
    }

    static boolean sameData(Map<String, Object> left, Map<String, Object> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (String key : left.keySet()) {
            Object leftValue = left.get(key);
            Object rightValue = right.get(key);
            if (leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber) {
                if (new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString())) != 0) {
                    return false;
                }
            } else if (!Objects.equals(leftValue, rightValue)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> readData(TrainingProject project) {
        try {
            Map<String, Object> raw = objectMapper.readValue(project.getDataJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            String reporter = raw.get("c57") instanceof String name ? name : null;
            return TrainingFieldSchema.validate(raw, reporter, false, "data").data();
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "培训项目数据损坏");
        }
    }

    private String writeData(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "培训项目数据无法序列化");
        }
    }

    public record ExportFile(String filename, byte[] bytes) {
    }
}
