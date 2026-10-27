package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.file.entity.FileInfo;
import edu.fzrj.metrics.module.file.entity.FileRef;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.entity.SubmissionHistory;
import edu.fzrj.metrics.module.submission.entity.Review;
import edu.fzrj.metrics.module.submission.mapper.ReviewMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionHistoryMapper;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.submission.service.support.SubmissionValueRules;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 填报（API 约定 15.5）：草稿 CRUD、提交、撤回。
 * 「年度已激活」校验以 plan_year 激活状态为准（阶段 3 遗留拍板，见开发决策记录）。
 */
@Service
public class SubmissionService {

    private static final Set<String> SORT_FIELDS = Set.of("created_at", "submitted_at", "year", "status");
    private static final Set<String> SUBMISSION_STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "APPROVED", "REJECTED", "WITHDRAWN");
    private static final Set<String> EDITABLE_STATUSES = Set.of("DRAFT", "REJECTED", "WITHDRAWN");
    private static final String FILE_BIZ_TYPE = "SUBMISSION";

    private final SubmissionMapper submissionMapper;
    private final SubmissionHistoryMapper historyMapper;
    private final ReviewMapper reviewMapper;
    private final TaskYearMapper taskYearMapper;
    private final PlanYearMapper planYearMapper;
    private final FileRefMapper fileRefMapper;
    private final FileInfoMapper fileInfoMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final TaskService taskService;
    private final IndicatorService indicatorService;
    private final PlanService planService;
    private final DataScopeService dataScopeService;
    private final UserRefResolver userRefResolver;
    private final ObjectMapper objectMapper;

    public SubmissionService(SubmissionMapper submissionMapper, SubmissionHistoryMapper historyMapper,
                             ReviewMapper reviewMapper, TaskYearMapper taskYearMapper,
                             PlanYearMapper planYearMapper, FileRefMapper fileRefMapper,
                             FileInfoMapper fileInfoMapper, OrgUnitMapper orgUnitMapper,
                             TaskService taskService, IndicatorService indicatorService,
                             PlanService planService, DataScopeService dataScopeService,
                             UserRefResolver userRefResolver, ObjectMapper objectMapper) {
        this.submissionMapper = submissionMapper;
        this.historyMapper = historyMapper;
        this.reviewMapper = reviewMapper;
        this.taskYearMapper = taskYearMapper;
        this.planYearMapper = planYearMapper;
        this.fileRefMapper = fileRefMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.taskService = taskService;
        this.indicatorService = indicatorService;
        this.planService = planService;
        this.dataScopeService = dataScopeService;
        this.userRefResolver = userRefResolver;
        this.objectMapper = objectMapper;
    }

    // ---------------- 查询 ----------------

    /** 某 B 任务的填报记录（按数据范围） */
    public PageResult<SubmissionDtos.ListItem> page(Long taskId, SubmissionDtos.SubmissionQuery query) {
        Task task = taskService.requireTask(taskId);
        taskService.requireAccess(task);
        if (StringUtils.hasText(query.getStatus()) && !SUBMISSION_STATUSES.contains(query.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "填报状态取值不合法：" + query.getStatus());
        }
        LambdaQueryWrapper<Submission> wrapper = new LambdaQueryWrapper<Submission>()
                .eq(Submission::getTaskId, taskId)
                .eq(Submission::getDeleted, 0L)
                .eq(query.getYear() != null, Submission::getYear, query.getYear())
                .eq(StringUtils.hasText(query.getStatus()), Submission::getStatus, query.getStatus())
                .orderByDesc(Submission::getCreatedAt);
        Page<Submission> page = submissionMapper.selectPage(query.toPage(SORT_FIELDS), wrapper);
        return new PageResult<>(toListItems(page.getRecords()), page.getTotal(),
                query.getPage(), query.getPageSize());
    }

    public Submission requireSubmission(Long submissionId) {
        Submission submission = submissionMapper.selectOne(new LambdaQueryWrapper<Submission>()
                .eq(Submission::getId, submissionId)
                .eq(Submission::getDeleted, 0L));
        if (submission == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "填报记录不存在");
        }
        return submission;
    }

    /** 详情（含材料 file_ref、审核记录 review、历史 submission_history）；按数据范围 */
    public SubmissionDtos.Detail detail(Long submissionId) {
        Submission submission = requireSubmission(submissionId);
        taskService.requireAccess(taskService.requireTask(submission.getTaskId()));
        return toDetail(submission);
    }

    // ---------------- 新建草稿 ----------------

    @Transactional
    public SubmissionDtos.Detail create(Long taskId, SubmissionDtos.CreateRequest request) {
        Task task = taskService.requireTask(taskId);
        boolean proxy = requireSubmitter(task.getUnitId(), request.getProxyReason());
        taskService.requireCurrentTask(task);
        requireTaskFillable(task);
        Indicator indicator = indicatorService.requireIndicator(task.getIndicatorId());
        Plan plan = planService.requirePlan(task.getPlanId());
        if (request.getYear() < plan.getStartYear() || request.getYear() > plan.getEndYear()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "年度不在计划起止年份内");
        }
        requireFillableYear(plan.getId(), request.getYear());
        String caliber = indicator.getStatCaliber();
        requireCaliberValue(caliber, request.getValue());
        TaskYear taskYear = requireTaskYear(taskId, request.getYear());

        Long userId = SecurityUtils.getUserId();
        Submission submission = new Submission();
        submission.setTaskId(task.getId());
        submission.setTaskYearId(taskYear.getId());
        submission.setYear(request.getYear());
        submission.setPlanId(task.getPlanId());
        submission.setIndicatorId(task.getIndicatorId());
        submission.setUnitId(task.getUnitId());
        submission.setStatCaliber(caliber);
        applyValue(submission, request.getValue());
        submission.setRemark(request.getRemark());
        submission.setSourceNote(request.getSourceNote());
        submission.setStatus("DRAFT");
        submission.setRevisionNo(0);
        submission.setIsProxy(proxy);
        submission.setProxyReason(proxy ? request.getProxyReason() : null);
        submission.setCreatedBy(userId);
        submission.setUpdatedBy(userId);
        submissionMapper.insert(submission);
        bindFiles(submission.getId(), request.getFileIds());
        return detail(submission.getId());
    }

    // ---------------- 修改 / 删除草稿 ----------------

    @Transactional
    public SubmissionDtos.Detail patch(Long submissionId, PatchHelper patch) {
        Submission submission = requireSubmission(submissionId);
        requireSubmitter(submission.getUnitId(), null);
        taskService.requireCurrentTask(submission.getTaskId());
        if (!EDITABLE_STATUSES.contains(submission.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "只有草稿 / 已驳回 / 已撤回的填报可以修改");
        }
        requireVersion(submission, patch.versionRequired());
        String caliber = submission.getStatCaliber();
        if (patch.has("value")) {
            if (patch.isNull("value")) {
                throw new BizException(ErrorCode.PARAM_INVALID, "填报值不能清空");
            }
            SubmissionDtos.MetricValue value = parseValue(patch.raw("value"));
            requireCaliberValue(caliber, value);
            applyValue(submission, value);
        }
        if (patch.has("remark")) {
            submission.setRemark(patch.isNull("remark") ? null : patch.text("remark"));
        }
        if (patch.has("sourceNote")) {
            submission.setSourceNote(patch.isNull("sourceNote") ? null : patch.text("sourceNote"));
        }
        if (patch.has("fileIds")) {
            List<Long> fileIds = patch.isNull("fileIds") ? List.of() : parseFileIds(patch.raw("fileIds"));
            replaceFiles(submissionId, fileIds);
        }
        submission.setUpdatedBy(SecurityUtils.getUserId());
        if (submissionMapper.updateById(submission) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + requireSubmission(submissionId).getVersion());
        }
        return detail(submissionId);
    }

    @Transactional
    public void delete(Long submissionId) {
        Submission submission = requireSubmission(submissionId);
        requireSubmitter(submission.getUnitId(), null);
        taskService.requireCurrentTask(submission.getTaskId());
        if (!"DRAFT".equals(submission.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "只有草稿可以删除");
        }
        List<Long> fileIds = fileIdsOf(submissionId);
        fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getBizType, FILE_BIZ_TYPE)
                .eq(FileRef::getBizId, submissionId));
        fileIds.forEach(this::unbindIfOrphan);
        submissionMapper.update(null, new LambdaUpdateWrapper<Submission>()
                .eq(Submission::getId, submissionId)
                .eq(Submission::getDeleted, 0L)
                .set(Submission::getDeleted, submissionId)
                .set(Submission::getUpdatedBy, SecurityUtils.getUserId()));
    }

    // ---------------- 提交 / 撤回 ----------------

    @Transactional
    public SubmissionDtos.Detail submit(Long submissionId, Integer version) {
        Submission submission = requireSubmission(submissionId);
        requireSubmitter(submission.getUnitId(), null);
        requireVersion(submission, version);
        if (!EDITABLE_STATUSES.contains(submission.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "当前状态不允许提交");
        }
        Task task = taskService.requireTask(submission.getTaskId());
        taskService.requireCurrentTask(task);
        requireTaskFillable(task);
        // 年度校验以 plan_year 激活状态为准（下发晚于激活的任务 task_year 仍 NOT_ACTIVATED，见决策记录）
        requireFillableYear(submission.getPlanId(), submission.getYear());
        Indicator indicator = indicatorService.requireIndicator(submission.getIndicatorId());
        String caliber = submission.getStatCaliber();
        SubmissionDtos.MetricValue value = valueViewOf(submission);
        requireCaliberValue(caliber, value);
        List<Long> fileIds = fileIdsOf(submissionId);
        String attachmentError = SubmissionValueRules.attachmentError(
                caliber, indicator.getMinFileCount(), fileIds.size(), submission.getSourceNote());
        if (attachmentError != null) {
            throw new BizException(ErrorCode.ATTACHMENT_REQUIRED, attachmentError);
        }
        TaskYear taskYear = requireTaskYear(submission.getTaskId(), submission.getYear());
        if ("ADJUSTING".equals(taskYear.getYearStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "该年度正在调整审批中，不能提交");
        }

        Long userId = SecurityUtils.getUserId();
        LocalDateTime now = LocalDateTime.now();
        submission.setStatus("PENDING_REVIEW");
        submission.setRevisionNo(submission.getRevisionNo() + 1);
        submission.setSubmittedAt(now);
        submission.setSubmittedBy(userId);
        submission.setUpdatedBy(userId);
        if (submissionMapper.updateById(submission) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + requireSubmission(submissionId).getVersion());
        }
        SubmissionHistory history = new SubmissionHistory();
        history.setSubmissionId(submissionId);
        history.setRevisionNo(submission.getRevisionNo());
        history.setValueJson(writeJson(value));
        history.setFileIdsJson(writeJson(fileIds));
        history.setSubmittedAt(now);
        history.setSubmittedBy(userId);
        historyMapper.insert(history);

        if ("NOT_ACTIVATED".equals(taskYear.getYearStatus()) || "IN_PROGRESS".equals(taskYear.getYearStatus())) {
            taskYear.setYearStatus("PENDING_REVIEW");
            taskYear.setUpdatedBy(userId);
            taskYearMapper.updateById(taskYear);
        }
        return detail(submissionId);
    }

    @Transactional
    public SubmissionDtos.Detail withdraw(Long submissionId, Integer version) {
        Submission submission = requireSubmission(submissionId);
        requireSubmitter(submission.getUnitId(), null);
        taskService.requireCurrentTask(submission.getTaskId());
        requireVersion(submission, version);
        if (!"PENDING_REVIEW".equals(submission.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "只有待审核的填报可以撤回");
        }
        Long userId = SecurityUtils.getUserId();
        submission.setStatus("WITHDRAWN");
        submission.setUpdatedBy(userId);
        if (submissionMapper.updateById(submission) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + requireSubmission(submissionId).getVersion());
        }
        TaskYear taskYear = taskYearMapper.selectById(submission.getTaskYearId());
        if (taskYear != null && "PENDING_REVIEW".equals(taskYear.getYearStatus())) {
            boolean hasPending = submissionMapper.selectCount(new LambdaQueryWrapper<Submission>()
                    .eq(Submission::getTaskYearId, taskYear.getId())
                    .eq(Submission::getStatus, "PENDING_REVIEW")
                    .eq(Submission::getDeleted, 0L)) > 0;
            if (!hasPending) {
                taskYear.setYearStatus(Boolean.TRUE.equals(taskYear.getIsAchieved()) ? "ACHIEVED" : "IN_PROGRESS");
                taskYear.setUpdatedBy(userId);
                taskYearMapper.updateById(taskYear);
            }
        }
        return detail(submissionId);
    }

    // ---------------- 填报人数据范围 / 前置校验 ----------------

    /**
     * 填报人校验：本单元负责人正常填报；平台管理员代填（敏感操作，必须填写原因）。
     *
     * @return true 表示管理员代填
     */
    public boolean requireSubmitter(Long unitId, String proxyReason) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (dataScopeService.isUnitScope(user) && unitId != null && unitId.equals(user.getUnitId())) {
            return false;
        }
        if (user.getRoles().contains("ADMIN")) {
            if (proxyReason != null && !StringUtils.hasText(proxyReason)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "管理员代填必须填写代填原因（proxyReason）");
            }
            return true;
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    /** 任务可填报：已下发或执行中（阶段 3 遗留拍板：不要求 task_year 已激活，以 plan_year 为准） */
    private void requireTaskFillable(Task task) {
        if (!"DISTRIBUTED".equals(task.getStatus()) && !"IN_PROGRESS".equals(task.getStatus())) {
            throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "任务尚未下发，不能填报");
        }
    }

    /** 年度可填报：plan_year 已激活（50004）且未核算锁定（60003） */
    public void requireFillableYear(Long planId, Integer year) {
        PlanYear planYear = planYearMapper.selectOne(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, planId)
                .eq(PlanYear::getYear, year)
                .eq(PlanYear::getDeleted, 0L));
        if (planYear == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "计划年度不存在");
        }
        if ("LOCKED".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.YEAR_SETTLED);
        }
        if (!"ACTIVE".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.YEAR_NOT_ACTIVATED);
        }
    }

    private void requireCaliberValue(String caliber, SubmissionDtos.MetricValue value) {
        String error = SubmissionValueRules.caliberError(caliber, value.getAmount(), value.getTotal(),
                value.getNumerator(), value.getDenominator(), value.getPercent(), value.getAchieved());
        if (error != null) {
            throw new BizException(ErrorCode.VALUE_CALIBER_MISMATCH, error);
        }
    }

    private TaskYear requireTaskYear(Long taskId, Integer year) {
        TaskYear taskYear = taskYearMapper.selectOne(new LambdaQueryWrapper<TaskYear>()
                .eq(TaskYear::getTaskId, taskId)
                .eq(TaskYear::getYear, year)
                .eq(TaskYear::getDeleted, 0L));
        if (taskYear == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务年度不存在");
        }
        return taskYear;
    }

    private void requireVersion(Submission submission, Integer version) {
        if (!submission.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + submission.getVersion());
        }
    }

    // ---------------- 文件 ----------------

    private void bindFiles(Long submissionId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        int sort = 0;
        for (Long fileId : new LinkedHashSet<>(fileIds)) {
            FileInfo fileInfo = requireFile(fileId);
            FileRef ref = new FileRef();
            ref.setBizType(FILE_BIZ_TYPE);
            ref.setBizId(submissionId);
            ref.setFileId(fileId);
            ref.setSort(sort++);
            fileRefMapper.insert(ref);
            if ("TEMP".equals(fileInfo.getStatus())) {
                fileInfoMapper.update(null, new LambdaUpdateWrapper<FileInfo>()
                        .eq(FileInfo::getId, fileId)
                        .set(FileInfo::getStatus, "BOUND")
                        .set(FileInfo::getBoundAt, LocalDateTime.now()));
            }
        }
    }

    private void replaceFiles(Long submissionId, List<Long> fileIds) {
        List<Long> keep = fileIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(fileIds));
        List<FileRef> existing = fileRefMapper.selectList(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getBizType, FILE_BIZ_TYPE)
                .eq(FileRef::getBizId, submissionId));
        List<Long> existingIds = existing.stream().map(FileRef::getFileId).toList();
        List<Long> removed = existingIds.stream().filter(id -> !keep.contains(id)).toList();
        if (!removed.isEmpty()) {
            fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                    .eq(FileRef::getBizType, FILE_BIZ_TYPE)
                    .eq(FileRef::getBizId, submissionId)
                    .in(FileRef::getFileId, removed));
            removed.forEach(this::unbindIfOrphan);
        }
        List<Long> added = keep.stream().filter(id -> !existingIds.contains(id)).toList();
        if (!added.isEmpty()) {
            bindFiles(submissionId, added);
        }
    }

    /** 不再被任何业务引用的文件退回 TEMP（待自动清理） */
    private void unbindIfOrphan(Long fileId) {
        Long refs = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId));
        if (refs == 0) {
            fileInfoMapper.update(null, new LambdaUpdateWrapper<FileInfo>()
                    .eq(FileInfo::getId, fileId)
                    .eq(FileInfo::getStatus, "BOUND")
                    .set(FileInfo::getStatus, "TEMP")
                    .set(FileInfo::getBoundAt, null));
        }
    }

    public List<Long> fileIdsOf(Long submissionId) {
        return fileRefMapper.selectList(new LambdaQueryWrapper<FileRef>()
                        .eq(FileRef::getBizType, FILE_BIZ_TYPE)
                        .eq(FileRef::getBizId, submissionId)
                        .orderByAsc(FileRef::getSort))
                .stream().map(FileRef::getFileId).toList();
    }

    private FileInfo requireFile(Long fileId) {
        FileInfo fileInfo = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, fileId)
                .eq(FileInfo::getDeleted, 0L));
        if (fileInfo == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return fileInfo;
    }

    // ---------------- 视图组装（审核模块复用） ----------------

    public List<SubmissionDtos.ListItem> toListItems(List<Submission> submissions) {
        if (submissions.isEmpty()) {
            return List.of();
        }
        Refs refs = loadRefs(submissions);
        // 佐证材料份数（file_ref 按 bizId 分组统计，待审列表展示用）
        List<Long> ids = submissions.stream().map(Submission::getId).toList();
        Map<Long, Long> fileCounts = fileRefMapper.selectList(new LambdaQueryWrapper<FileRef>()
                        .eq(FileRef::getBizType, FILE_BIZ_TYPE)
                        .in(FileRef::getBizId, ids)
                        .select(FileRef::getBizId))
                .stream().collect(Collectors.groupingBy(FileRef::getBizId, Collectors.counting()));
        return submissions.stream().map(s -> toListItem(s, refs, fileCounts)).toList();
    }

    private SubmissionDtos.ListItem toListItem(Submission s, Refs refs, Map<Long, Long> fileCounts) {
        return new SubmissionDtos.ListItem(s.getId(), s.getPlanId(),
                taskRef(refs.tasks().get(s.getTaskId())),
                indicatorRef(refs.indicators().get(s.getIndicatorId())),
                unitRef(refs.units().get(s.getUnitId())),
                s.getYear(), s.getStatCaliber(), valueViewOf(s), s.getStatus(), s.getRevisionNo(),
                s.getSubmittedAt(), userRefResolver.refOf(s.getSubmittedBy(), refs.users()),
                s.getReviewedAt(), userRefResolver.refOf(s.getReviewerId(), refs.users()),
                s.getVersion(), s.getCreatedAt(),
                fileCounts.getOrDefault(s.getId(), 0L).intValue());
    }

    private SubmissionDtos.Detail toDetail(Submission s) {
        Refs refs = loadRefs(List.of(s));
        List<FileRef> fileRefs = fileRefMapper.selectList(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getBizType, FILE_BIZ_TYPE)
                .eq(FileRef::getBizId, s.getId())
                .orderByAsc(FileRef::getSort));
        List<SubmissionDtos.FileView> files = fileRefs.isEmpty() ? List.of()
                : fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                                .in(FileInfo::getId, fileRefs.stream().map(FileRef::getFileId).toList())
                                .eq(FileInfo::getDeleted, 0L))
                        .stream().map(f -> new SubmissionDtos.FileView(f.getId(), f.getFileName(),
                                f.getSize(), f.getExt(), f.getCategory(), f.getMimeType()))
                        .toList();
        List<Review> reviews = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getSubmissionId, s.getId())
                .orderByDesc(Review::getCreatedAt));
        Map<Long, SysUser> reviewUsers = userRefResolver.mapOf(
                reviews.stream().map(Review::getReviewerId).toList());
        List<SubmissionDtos.ReviewView> reviewViews = reviews.stream()
                .map(r -> new SubmissionDtos.ReviewView(r.getId(), r.getRevisionNo(),
                        userRefResolver.refOf(r.getReviewerId(), reviewUsers),
                        r.getResult(), r.getComment(), readValue(r.getApprovedValueJson()),
                        r.getIsProxy(), r.getCreatedAt()))
                .toList();
        List<SubmissionHistory> histories = historyMapper.selectList(new LambdaQueryWrapper<SubmissionHistory>()
                .eq(SubmissionHistory::getSubmissionId, s.getId())
                .orderByDesc(SubmissionHistory::getRevisionNo));
        Map<Long, SysUser> historyUsers = userRefResolver.mapOf(
                histories.stream().map(SubmissionHistory::getSubmittedBy).toList());
        List<SubmissionDtos.HistoryView> historyViews = histories.stream()
                .map(h -> new SubmissionDtos.HistoryView(h.getRevisionNo(), readValue(h.getValueJson()),
                        readLongList(h.getFileIdsJson()), h.getSubmittedAt(),
                        userRefResolver.refOf(h.getSubmittedBy(), historyUsers)))
                .toList();
        return new SubmissionDtos.Detail(s.getId(), s.getPlanId(),
                taskRef(refs.tasks().get(s.getTaskId())),
                indicatorRef(refs.indicators().get(s.getIndicatorId())),
                unitRef(refs.units().get(s.getUnitId())),
                s.getYear(), s.getStatCaliber(), valueViewOf(s), approvedValueViewOf(s),
                s.getRemark(), s.getSourceNote(), s.getStatus(), s.getRevisionNo(), s.getIsProxy(),
                files, reviewViews, historyViews,
                s.getSubmittedAt(), userRefResolver.refOf(s.getSubmittedBy(), refs.users()),
                s.getReviewedAt(), userRefResolver.refOf(s.getReviewerId(), refs.users()),
                s.getVersion(), s.getCreatedAt(), s.getUpdatedAt());
    }

    /** 填报值视图（RATIO 附带 rate；growthRate / increment 为年度级派生，填报级不展开） */
    public SubmissionDtos.MetricValue valueViewOf(Submission s) {
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(s.getAmount());
        value.setTotal(s.getTotal());
        value.setNumerator(s.getNumerator());
        value.setDenominator(s.getDenominator());
        value.setPercent(s.getPercent());
        value.setAchieved(s.getAchieved());
        value.setRate(rateOf(s.getNumerator(), s.getDenominator()));
        return value;
    }

    /** 认定值视图（未审核时全空返回 null） */
    public SubmissionDtos.MetricValue approvedValueViewOf(Submission s) {
        if (s.getApprovedAmount() == null && s.getApprovedTotal() == null && s.getApprovedNumerator() == null
                && s.getApprovedDenominator() == null && s.getApprovedPercent() == null
                && s.getApprovedAchieved() == null) {
            return null;
        }
        SubmissionDtos.MetricValue value = new SubmissionDtos.MetricValue();
        value.setAmount(s.getApprovedAmount());
        value.setTotal(s.getApprovedTotal());
        value.setNumerator(s.getApprovedNumerator());
        value.setDenominator(s.getApprovedDenominator());
        value.setPercent(s.getApprovedPercent());
        value.setAchieved(s.getApprovedAchieved());
        value.setRate(rateOf(s.getApprovedNumerator(), s.getApprovedDenominator()));
        return value;
    }

    private BigDecimal rateOf(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || BigDecimal.ZERO.compareTo(denominator) == 0) {
            return null;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    public void applyValue(Submission submission, SubmissionDtos.MetricValue value) {
        submission.setAmount(value.getAmount());
        submission.setTotal(value.getTotal());
        submission.setNumerator(value.getNumerator());
        submission.setDenominator(value.getDenominator());
        submission.setPercent(value.getPercent());
        submission.setAchieved(value.getAchieved());
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "序列化失败");
        }
    }

    private SubmissionDtos.MetricValue readValue(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SubmissionDtos.MetricValue.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Long> readLongList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private SubmissionDtos.MetricValue parseValue(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, SubmissionDtos.MetricValue.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "value 格式不合法");
        }
    }

    private List<Long> parseFileIds(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "fileIds 应为数组");
        }
        try {
            return objectMapper.readValue(node.toString(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "fileIds 格式不合法");
        }
    }

    // ---------------- 引用装配 ----------------

    private record Refs(Map<Long, Task> tasks, Map<Long, Indicator> indicators,
                        Map<Long, OrgUnit> units, Map<Long, SysUser> users) {
    }

    private Refs loadRefs(List<Submission> submissions) {
        List<Long> taskIds = submissions.stream().map(Submission::getTaskId).distinct().toList();
        Map<Long, Task> tasks = taskIds.isEmpty() ? Map.of()
                : taskService.findByIds(taskIds).stream()
                        .collect(Collectors.toMap(Task::getId, Function.identity()));
        List<Long> indicatorIds = submissions.stream().map(Submission::getIndicatorId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Indicator> indicators = indicatorIds.isEmpty() ? Map.of()
                : indicatorService.findByIds(indicatorIds).stream()
                        .collect(Collectors.toMap(Indicator::getId, Function.identity()));
        List<Long> unitIds = submissions.stream().map(Submission::getUnitId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, OrgUnit> units = unitIds.isEmpty() ? Map.of()
                : orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, unitIds)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
        Map<Long, SysUser> users = userRefResolver.mapOf(submissions.stream()
                .flatMap(s -> java.util.stream.Stream.of(s.getSubmittedBy(), s.getReviewerId()))
                .toList());
        return new Refs(tasks, indicators, units, users);
    }

    private SubmissionDtos.TaskRef taskRef(Task task) {
        return task == null ? null : new SubmissionDtos.TaskRef(task.getId(), task.getCode());
    }

    private SubmissionDtos.IndicatorRef indicatorRef(Indicator indicator) {
        return indicator == null ? null
                : new SubmissionDtos.IndicatorRef(indicator.getId(), indicator.getCode(),
                        indicator.getName(), indicator.getLevel());
    }

    private SubmissionDtos.UnitRef unitRef(OrgUnit unit) {
        return unit == null ? null : new SubmissionDtos.UnitRef(unit.getId(), unit.getCode(), unit.getName());
    }
}
