package edu.fzrj.metrics.module.plan.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Direction;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.DirectionMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.mapper.TemplateMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.mapper.MajorGroupMapper;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskYearActivationService;
import edu.fzrj.metrics.module.training.entity.TrainingProject;
import edu.fzrj.metrics.module.training.entity.TrainingSheet;
import edu.fzrj.metrics.module.training.mapper.TrainingProjectMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingReviewMapper;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private PlanMapper planMapper;
    @Mock
    private PlanYearMapper planYearMapper;
    @Mock
    private TemplateMapper templateMapper;
    @Mock
    private DirectionMapper directionMapper;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private IndicatorYearTargetMapper yearTargetMapper;
    @Mock
    private MajorGroupMapper majorGroupMapper;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private UserRefResolver userRefResolver;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private TaskYearActivationService taskYearActivationService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private PlanReadScopeService planReadScopeService;
    @Mock
    private TrainingSheetMapper trainingSheetMapper;
    @Mock
    private TrainingProjectMapper trainingProjectMapper;
    @Mock
    private TrainingReviewMapper trainingReviewMapper;

    @InjectMocks
    private PlanService planService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Plan.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, IndicatorYearTarget.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, TaskYear.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
        TableInfoHelper.initTableInfo(assistant, TrainingSheet.class);
        TableInfoHelper.initTableInfo(assistant, TrainingProject.class);
    }

    @BeforeEach
    void login() {
        LoginUser user = new LoginUser(99L, "admin", "管理员", null,
                List.of("ADMIN"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 模板实例化只创建A草稿不创建任务与任务年度() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        plan.setStatus("DRAFT");
        TemplateContent content = new TemplateContent();
        content.getUnassignedIndicators().add(indicatorNode("A", "A01"));

        planService.instantiate(plan, content);

        ArgumentCaptor<Indicator> indicators = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorMapper).insert(indicators.capture());
        assertEquals("A", indicators.getValue().getLevel());
        assertEquals("DRAFT", indicators.getValue().getStatus());
        assertEquals("DRAFT", plan.getStatus());
        assertNull(plan.getFinalizedAt());
        verifyNoInteractions(taskMapper, taskYearMapper);
    }

    @Test
    void 模板实例化按sort生成建设方向及对应指标编号() {
        Plan plan = new Plan();
        plan.setId(11L);
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        TemplateContent.DirectionNode later = new TemplateContent.DirectionNode();
        later.setSubjectType("SCHOOL");
        later.setName("后排序方向");
        later.setSort(20);
        later.getIndicators().add(indicatorNode("A", "OLD02"));
        TemplateContent.DirectionNode first = new TemplateContent.DirectionNode();
        first.setSubjectType("SCHOOL");
        first.setName("先排序方向");
        first.setSort(10);
        first.getIndicators().add(indicatorNode("A", "OLD01"));
        TemplateContent content = new TemplateContent();
        content.getDirections().add(later);
        content.getDirections().add(first);

        planService.instantiate(plan, content);

        ArgumentCaptor<Direction> directions = ArgumentCaptor.forClass(Direction.class);
        verify(directionMapper, times(2)).insert(directions.capture());
        assertEquals("先排序方向", directions.getAllValues().get(0).getName());
        assertEquals("A", directions.getAllValues().get(0).getCode());
        assertEquals("后排序方向", directions.getAllValues().get(1).getName());
        assertEquals("B", directions.getAllValues().get(1).getCode());
        ArgumentCaptor<Indicator> indicators = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorMapper, times(2)).insert(indicators.capture());
        assertEquals("A01", indicators.getAllValues().get(0).getCode());
        assertEquals("B01", indicators.getAllValues().get(1).getCode());
        verifyNoInteractions(taskMapper, taskYearMapper);
    }

    @Test
    void 模板实例化拒绝历史A加并返回40006() {
        Plan plan = new Plan();
        TemplateContent.IndicatorNode a = indicatorNode("A", "A01");
        a.getChildren().add(indicatorNode("A_PLUS", "A01-1"));
        TemplateContent content = new TemplateContent();
        content.getUnassignedIndicators().add(a);

        BizException error = assertThrows(BizException.class, () -> planService.instantiate(plan, content));

        assertEquals(40006, error.getErrorCode().getCode());
        verifyNoInteractions(indicatorMapper, taskMapper, taskYearMapper);
    }

    @Test
    void 新建计划后直接激活全部年度() {
        Plan stored = draftPlan();
        stored.setId(null);
        stored.setVersion(0);
        doAnswer(invocation -> {
            Plan inserted = invocation.getArgument(0);
            inserted.setId(1L);
            stored.setId(1L);
            stored.setName(inserted.getName());
            stored.setStartYear(inserted.getStartYear());
            stored.setEndYear(inserted.getEndYear());
            stored.setBaselineYear(inserted.getBaselineYear());
            stored.setSettleDeadline(inserted.getSettleDeadline());
            stored.setStatus(inserted.getStatus());
            stored.setSource(inserted.getSource());
            return null;
        }).when(planMapper).insert(any(Plan.class));
        when(planMapper.selectOne(any())).thenReturn(stored);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);
        when(planYearMapper.selectList(any())).thenReturn(List.of());
        PlanDtos.CreateRequest request = new PlanDtos.CreateRequest();
        request.setName("自动激活计划");
        request.setStartYear(2026);
        request.setEndYear(2030);
        request.setBaselineYear(2025);
        request.setSource("BLANK");

        PlanDtos.Detail result = planService.create(request);

        ArgumentCaptor<PlanYear> years = ArgumentCaptor.forClass(PlanYear.class);
        verify(planYearMapper, times(5)).insert(years.capture());
        assertTrue(years.getAllValues().stream().allMatch(year -> "ACTIVE".equals(year.getStatus())));
        assertTrue(years.getAllValues().stream().allMatch(year -> year.getActivatedAt() != null));
        assertEquals("ACTIVE", result.status());
        verify(taskYearActivationService).syncPlan(1L, 99L);
    }

    @Test
    void 启动既有草稿计划时直接激活全部年度() {
        Plan plan = draftPlan();
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);

        planService.activate(1L, 2);

        ArgumentCaptor<PlanYear> years = ArgumentCaptor.forClass(PlanYear.class);
        verify(planYearMapper, times(5)).insert(years.capture());
        assertTrue(years.getAllValues().stream().allMatch(year -> "ACTIVE".equals(year.getStatus())));
        assertTrue(years.getAllValues().stream().allMatch(year -> year.getActivatedAt() != null));
        assertTrue(years.getAllValues().stream().allMatch(year -> year.getActivatedBy() != null));
        assertEquals("ACTIVE", plan.getStatus());
    }

    @Test
    void 启动职业培训草稿计划不生成指标年度或任务() {
        Plan plan = draftPlan();
        plan.setPlanType("TRAINING");
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);

        PlanDtos.Detail result = planService.activate(1L, 2);

        assertEquals("ACTIVE", result.status());
        assertEquals("TRAINING", result.planType());
        assertTrue(result.years().isEmpty());
        verify(planYearMapper, never()).insert(any(PlanYear.class));
        verifyNoInteractions(taskYearActivationService);
    }

    @Test
    void 计划卡片按可见A指标计算五年累计完成率() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 8, 0);
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("关联计划");
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        plan.setStatus("ACTIVE");
        plan.setSource("BLANK");
        plan.setCreatedAt(createdAt);
        Task firstTask = task(101L);
        firstTask.setIndicatorId(10L);
        firstTask.setTopIndicatorId(10L);
        Task secondTask = task(102L);
        secondTask.setIndicatorId(10L);
        secondTask.setTopIndicatorId(10L);
        List<Task> tasks = List.of(firstTask, secondTask);
        Indicator a = indicator(10L, null, "A");
        a.setIsQuantifiable(true);
        a.setStatCaliber("INCREMENT");
        a.setTotalTarget(new BigDecimal("100"));
        Indicator aPlus = indicator(11L, 10L, "A_PLUS");
        Indicator unrelated = indicator(12L, null, "A");
        TaskYear firstYear = taskYear(101L, 2026, "20");
        TaskYear secondYear = taskYear(101L, 2027, "20");
        TaskYear otherYear = taskYear(102L, 2026, "10");

        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(planReadScopeService.filterVisiblePlans(any())).thenReturn(List.of(plan));
        when(planReadScopeService.visibleTasks(1L)).thenReturn(tasks);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(a, aPlus, unrelated));
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of(10L, 11L));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(firstYear, secondYear, otherYear));

        PlanDtos.ListItem item = planService.list(null).get(0);

        assertEquals(1L, item.indicatorCountA());
        assertEquals(1L, item.indicatorCountAPlus());
        assertEquals(2L, item.taskCountB());
        assertEquals(0, new BigDecimal("0.5").compareTo(item.completionRate()));
        assertEquals(createdAt, item.updatedAt());
    }

    @Test
    void 计划卡片将无完成值指标按零计入五年累计平均分母() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("测试计划");
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        plan.setStatus("ACTIVE");
        plan.setSource("BLANK");
        plan.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        Task completedTask = task(101L);
        completedTask.setIndicatorId(10L);
        completedTask.setTopIndicatorId(10L);
        Task emptyTask = task(102L);
        emptyTask.setIndicatorId(20L);
        emptyTask.setTopIndicatorId(20L);
        Indicator completed = indicator(10L, null, "A");
        completed.setIsQuantifiable(true);
        completed.setStatCaliber("INCREMENT");
        completed.setTotalTarget(new BigDecimal("100"));
        Indicator noData = indicator(20L, null, "A");
        noData.setIsQuantifiable(true);
        noData.setStatCaliber("INCREMENT");
        noData.setTotalTarget(new BigDecimal("100"));
        TaskYear completedYear = taskYear(101L, 2026, "100");
        TaskYear emptyYear = taskYear(102L, "IN_PROGRESS");
        emptyYear.setYear(2026);

        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(planReadScopeService.filterVisiblePlans(any())).thenReturn(List.of(plan));
        when(planReadScopeService.visibleTasks(1L)).thenReturn(List.of(completedTask, emptyTask));
        when(indicatorMapper.selectList(any())).thenReturn(List.of(completed, noData));
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of(10L, 20L));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(completedYear, emptyYear));

        PlanDtos.ListItem item = planService.list(null).get(0);

        assertEquals(0, new BigDecimal("0.5").compareTo(item.completionRate()));
    }

    @Test
    void 跨学院直接访问计划详情返回数据范围错误() {
        Plan plan = new Plan();
        plan.setId(1L);
        when(planMapper.selectOne(any())).thenReturn(plan);
        doThrow(new BizException(ErrorCode.DATA_SCOPE_DENIED))
                .when(planReadScopeService).requireVisible(plan);

        BizException error = assertThrows(BizException.class, () -> planService.detail(1L));

        assertEquals(20005, error.getErrorCode().getCode());
    }

    @Test
    void 职业培训采集计划列表不查询指标任务或完成率() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("职业培训信息采集");
        plan.setPlanType("TRAINING");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(planReadScopeService.filterVisiblePlans(any())).thenReturn(List.of(plan));

        PlanDtos.ListItem item = planService.list(null).get(0);

        assertEquals("TRAINING", item.planType());
        assertNull(item.completionRate());
        assertEquals(0L, item.indicatorCountA());
        assertEquals(0L, item.taskCountB());
        verifyNoInteractions(indicatorMapper, taskMapper, taskYearMapper);
    }

    @Test
    void 职业培训采集计划不能进入指标计划详情和操作() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setPlanType("TRAINING");
        when(planMapper.selectOne(any())).thenReturn(plan);

        BizException error = assertThrows(BizException.class, () -> planService.detail(1L));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(planReadScopeService, never()).requireVisible(any());
    }

    @Test
    void 缩短规划期时存在区间外指标年度目标则阻止修改() throws Exception {
        Plan plan = draftPlan();
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator(10L, null, "A")));
        when(yearTargetMapper.selectCount(any())).thenReturn(1L);
        PatchHelper patch = new PatchHelper(new ObjectMapper()
                .readTree("{\"endYear\":2029,\"version\":2}"));

        BizException error = assertThrows(BizException.class, () -> planService.patch(1L, patch));

        assertEquals(10001, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("不能缩短规划期"));
        assertEquals(2030, plan.getEndYear());
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @Test
    void 缩短规划期时存在区间外任务年度则阻止修改() throws Exception {
        Plan plan = draftPlan();
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator(10L, null, "A")));
        when(yearTargetMapper.selectCount(any())).thenReturn(0L);
        when(taskYearMapper.selectCount(any())).thenReturn(1L);
        PatchHelper patch = new PatchHelper(new ObjectMapper()
                .readTree("{\"startYear\":2027,\"baselineYear\":2026,\"version\":2}"));

        BizException error = assertThrows(BizException.class, () -> planService.patch(1L, patch));

        assertEquals(10001, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("任务年度"));
        assertEquals(2026, plan.getStartYear());
        assertEquals(2025, plan.getBaselineYear());
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @Test
    void 复制计划分工仅保留结构并重置执行状态() {
        Plan targetPlan = draftPlan();
        targetPlan.setId(2L);
        Indicator sourceIndicator = indicator(10L, null, "A");
        sourceIndicator.setCode("A01");
        Indicator targetIndicator = indicator(20L, null, "A");
        targetIndicator.setCode("A01");
        Task sourceTask = task(100L);
        sourceTask.setPlanId(1L);
        sourceTask.setIndicatorId(10L);
        sourceTask.setTopIndicatorId(10L);
        sourceTask.setUnitId(300L);
        sourceTask.setOwnerId(400L);
        sourceTask.setCode("A01-C01");
        sourceTask.setAcceptedAt(LocalDateTime.now());
        TaskYear sourceYear = taskYear(100L, "ACHIEVED");
        sourceYear.setYear(2026);
        sourceYear.setActualValue(new BigDecimal("10"));
        when(planMapper.selectOne(any())).thenReturn(draftPlan());
        when(indicatorMapper.selectList(any())).thenReturn(List.of(sourceIndicator), List.of(targetIndicator));
        when(taskMapper.selectList(any())).thenReturn(List.of(sourceTask));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(sourceYear));
        OrgUnit unit = new OrgUnit();
        unit.setId(300L);
        unit.setCode("03");
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));
        doAnswer(invocation -> {
            invocation.getArgument(0, Task.class).setId(200L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        planService.copyAllocations(1L, targetPlan, 99L);

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        ArgumentCaptor<TaskYear> yearCaptor = ArgumentCaptor.forClass(TaskYear.class);
        verify(taskMapper).insert(taskCaptor.capture());
        verify(taskYearMapper).insert(yearCaptor.capture());
        Task copied = taskCaptor.getValue();
        assertEquals(2L, copied.getPlanId());
        assertEquals(20L, copied.getIndicatorId());
        assertEquals("A01-03", copied.getCode());
        assertEquals("PENDING_DISTRIBUTION", copied.getStatus());
        assertNull(copied.getAcceptedAt());
        assertNull(copied.getDistributedAt());
        TaskYear copiedYear = yearCaptor.getValue();
        assertEquals(200L, copiedYear.getTaskId());
        assertEquals("NOT_ACTIVATED", copiedYear.getYearStatus());
        assertEquals(0, new BigDecimal("10").compareTo(copiedYear.getTarget()));
        assertNull(copiedYear.getActualValue());
    }

    @Test
    void 模板跨周期按相对年份复制全部指标年度目标() {
        Plan targetPlan = draftPlan();
        targetPlan.setId(2L);
        targetPlan.setStartYear(2031);
        targetPlan.setEndYear(2035);
        TemplateContent content = new TemplateContent();
        content.setStartYear(2026);
        content.setEndYear(2030);
        TemplateContent.IndicatorNode node = indicatorNode("A", "A01");
        node.getYearTargets().put(2026, new BigDecimal("1"));
        node.getYearTargets().put(2030, new BigDecimal("5"));
        content.getUnassignedIndicators().add(node);

        planService.instantiate(targetPlan, content);

        ArgumentCaptor<IndicatorYearTarget> targets = ArgumentCaptor.forClass(IndicatorYearTarget.class);
        verify(yearTargetMapper, times(2)).insert(targets.capture());
        assertEquals(List.of(2031, 2035), targets.getAllValues().stream()
                .map(IndicatorYearTarget::getYear).toList());
        assertEquals(0, new BigDecimal("1").compareTo(targets.getAllValues().get(0).getTarget()));
        assertEquals(0, new BigDecimal("5").compareTo(targets.getAllValues().get(1).getTarget()));
    }

    @Test
    void 模板周期长度不一致时拒绝实例化且不写入指标() {
        Plan targetPlan = draftPlan();
        targetPlan.setStartYear(2031);
        targetPlan.setEndYear(2034);
        TemplateContent content = new TemplateContent();
        content.setStartYear(2026);
        content.setEndYear(2030);
        TemplateContent.IndicatorNode node = indicatorNode("A", "A01");
        node.getYearTargets().put(2026, BigDecimal.ONE);
        content.getUnassignedIndicators().add(node);

        BizException error = assertThrows(BizException.class, () -> planService.instantiate(targetPlan, content));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(indicatorMapper, never()).insert(any(Indicator.class));
        verify(yearTargetMapper, never()).insert(any(IndicatorYearTarget.class));
    }

    @Test
    void 复制计划跨周期按相对年份复制全部B年度目标() {
        Plan sourcePlan = draftPlan();
        Plan targetPlan = draftPlan();
        targetPlan.setId(2L);
        targetPlan.setStartYear(2031);
        targetPlan.setEndYear(2035);
        Indicator sourceIndicator = indicator(10L, null, "A");
        sourceIndicator.setCode("A01");
        Indicator targetIndicator = indicator(20L, null, "A");
        targetIndicator.setCode("A01");
        Task sourceTask = task(100L);
        sourceTask.setPlanId(1L);
        sourceTask.setIndicatorId(10L);
        sourceTask.setTopIndicatorId(10L);
        sourceTask.setUnitId(300L);
        sourceTask.setCode("A01-C01");
        TaskYear firstYear = taskYear(100L, "ACHIEVED");
        firstYear.setYear(2026);
        firstYear.setTarget(BigDecimal.ONE);
        TaskYear lastYear = taskYear(100L, "ACHIEVED");
        lastYear.setYear(2030);
        lastYear.setTarget(new BigDecimal("5"));
        when(planMapper.selectOne(any())).thenReturn(sourcePlan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(sourceIndicator), List.of(targetIndicator));
        when(taskMapper.selectList(any())).thenReturn(List.of(sourceTask));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(firstYear, lastYear));
        OrgUnit unit = new OrgUnit();
        unit.setId(300L);
        unit.setCode("03");
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));
        doAnswer(invocation -> {
            invocation.getArgument(0, Task.class).setId(200L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        planService.copyAllocations(1L, targetPlan, 99L);

        ArgumentCaptor<TaskYear> years = ArgumentCaptor.forClass(TaskYear.class);
        verify(taskYearMapper, times(2)).insert(years.capture());
        assertEquals(List.of(2031, 2035), years.getAllValues().stream().map(TaskYear::getYear).toList());
        assertEquals(0, BigDecimal.ONE.compareTo(years.getAllValues().get(0).getTarget()));
        assertEquals(0, new BigDecimal("5").compareTo(years.getAllValues().get(1).getTarget()));
        assertTrue(years.getAllValues().stream().allMatch(year -> "NOT_ACTIVATED".equals(year.getYearStatus())));
    }

    @Test
    void 复制计划周期长度不一致时拒绝且不生成B草稿() {
        Plan sourcePlan = draftPlan();
        Plan targetPlan = draftPlan();
        targetPlan.setId(2L);
        targetPlan.setStartYear(2031);
        targetPlan.setEndYear(2034);
        Indicator sourceIndicator = indicator(10L, null, "A");
        sourceIndicator.setCode("A01");
        Indicator targetIndicator = indicator(20L, null, "A");
        targetIndicator.setCode("A01");
        Task sourceTask = task(100L);
        sourceTask.setIndicatorId(10L);
        sourceTask.setTopIndicatorId(10L);
        sourceTask.setUnitId(300L);
        TaskYear sourceYear = taskYear(100L, "ACHIEVED");
        sourceYear.setYear(2026);
        when(planMapper.selectOne(any())).thenReturn(sourcePlan);
        when(indicatorMapper.selectList(any())).thenReturn(List.of(sourceIndicator), List.of(targetIndicator));
        when(taskMapper.selectList(any())).thenReturn(List.of(sourceTask));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(sourceYear));
        OrgUnit unit = new OrgUnit();
        unit.setId(300L);
        unit.setCode("03");
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));

        BizException error = assertThrows(BizException.class,
                () -> planService.copyAllocations(1L, targetPlan, 99L));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(taskMapper, never()).insert(any(Task.class));
        verify(taskYearMapper, never()).insert(any(TaskYear.class));
    }

    @Test
    void 培训计划按版本归档并执行数据范围校验() {
        Plan plan = draftPlan();
        plan.setPlanType("TRAINING");
        plan.setStatus("ACTIVE");
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(plan);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);

        PlanDtos.Detail result = planService.archive(1L, 2);

        assertEquals("ARCHIVED", result.status());
        assertEquals("TRAINING", result.planType());
        assertEquals(99L, plan.getUpdatedBy());
        verify(planReadScopeService).requireVisible(plan);
        verify(planMapper).selectByIdForUpdate(1L);
    }

    @Test
    void 重复归档返回状态错误() {
        Plan plan = draftPlan();
        plan.setPlanType("TRAINING");
        plan.setStatus("ARCHIVED");
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(plan);

        BizException error = assertThrows(BizException.class, () -> planService.archive(1L, 2));

        assertEquals(30001, error.getErrorCode().getCode());
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @Test
    void 归档版本冲突时不写入() {
        Plan plan = draftPlan();
        plan.setPlanType("TRAINING");
        plan.setStatus("ACTIVE");
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(plan);

        BizException error = assertThrows(BizException.class, () -> planService.archive(1L, 1));

        assertEquals(10005, error.getErrorCode().getCode());
        assertEquals("ACTIVE", plan.getStatus());
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @Test
    void 已归档计划按版本逻辑删除并保留历史数据() {
        Plan plan = draftPlan();
        plan.setStatus("ARCHIVED");
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(plan);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);
        PlanDtos.DeleteRequest request = new PlanDtos.DeleteRequest();
        request.setVersion(2);
        request.setReason("清理测试计划");

        planService.delete(1L, request);

        assertEquals(1L, plan.getDeleted());
        assertEquals(99L, plan.getUpdatedBy());
        verify(planReadScopeService).requireVisible(plan);
        verify(planMapper).updateById(plan);
    }

    @Test
    void 草稿计划可以逻辑删除() {
        Plan plan = draftPlan();
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(plan);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);
        PlanDtos.DeleteRequest request = new PlanDtos.DeleteRequest();
        request.setVersion(2);
        request.setReason("删除空白草稿");

        planService.delete(1L, request);

        assertEquals(1L, plan.getDeleted());
    }

    @Test
    void 执行中培训计划必须先归档再删除() {
        Plan plan = draftPlan();
        plan.setPlanType("TRAINING");
        plan.setStatus("ACTIVE");
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(plan);
        PlanDtos.DeleteRequest request = new PlanDtos.DeleteRequest();
        request.setVersion(2);
        request.setReason("清理测试计划");

        BizException error = assertThrows(BizException.class, () -> planService.delete(1L, request));

        assertEquals(30001, error.getErrorCode().getCode());
        assertNull(plan.getDeleted());
        verify(planMapper, never()).updateById(any(Plan.class));
        verifyNoInteractions(trainingSheetMapper, trainingProjectMapper, trainingReviewMapper);
    }

    @Test
    void 删除计划版本冲突时不写入() {
        Plan plan = draftPlan();
        plan.setStatus("ARCHIVED");
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(plan);
        PlanDtos.DeleteRequest request = new PlanDtos.DeleteRequest();
        request.setVersion(1);
        request.setReason("清理测试计划");

        BizException error = assertThrows(BizException.class, () -> planService.delete(1L, request));

        assertEquals(10005, error.getErrorCode().getCode());
        assertNull(plan.getDeleted());
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ARCHIVED"})
    void 删除培训计划级联逻辑删除三表并保留审核记录(String status) {
        Plan plan = draftPlan();
        plan.setPlanType("TRAINING");
        plan.setStatus(status);
        TrainingSheet sheet = new TrainingSheet();
        sheet.setId(11L);
        sheet.setPlanId(1L);
        TrainingProject project = new TrainingProject();
        project.setId(101L);
        project.setSheetId(11L);
        when(planMapper.selectByIdForUpdate(1L)).thenReturn(plan);
        when(trainingSheetMapper.selectList(any())).thenReturn(List.of(sheet));
        when(trainingProjectMapper.selectList(any())).thenReturn(List.of(project));
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);
        PlanDtos.DeleteRequest request = new PlanDtos.DeleteRequest();
        request.setVersion(2);
        request.setReason("清理培训计划");

        planService.delete(1L, request);

        assertEquals(1L, plan.getDeleted());
        assertEquals(11L, sheet.getDeleted());
        assertEquals(101L, project.getDeleted());
        assertEquals(99L, plan.getUpdatedBy());
        assertEquals(99L, sheet.getUpdatedBy());
        assertEquals(99L, project.getUpdatedBy());
        verify(trainingSheetMapper).updateById(sheet);
        verify(trainingProjectMapper).updateById(project);
        verifyNoInteractions(trainingReviewMapper);
    }

    @Test
    void UNIT和STAFF的计划完成率均按可见任务重算MANUAL() {
        Plan plan = singleYearPlan();
        Indicator indicator = manualIndicator(10L, 500L);
        Task visible = task(101L);
        visible.setIndicatorId(10L);
        visible.setTopIndicatorId(10L);
        TaskYear visibleYear = taskYear(101L, 2026, "20");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(planReadScopeService.filterVisiblePlans(any())).thenReturn(List.of(plan));
        when(planReadScopeService.visibleTasks(1L)).thenReturn(List.of(visible));
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator));
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of(10L));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(visibleYear));

        for (String role : List.of("UNIT_OWNER", "STAFF")) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new LoginUser(99L, role, role, 31L, List.of(role), List.of()), null, List.of()));
            PlanDtos.ListItem item = planService.list(null).get(0);
            assertEquals(0, new BigDecimal("0.2").compareTo(item.completionRate()));
        }
        verify(yearTargetMapper, never()).selectList(any());
    }

    @Test
    void 纯STAFF无可见任务时计划完成率failClosed() {
        Plan plan = singleYearPlan();
        Indicator indicator = manualIndicator(10L, 500L);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(planReadScopeService.filterVisiblePlans(any())).thenReturn(List.of(plan));
        when(planReadScopeService.visibleTasks(1L)).thenReturn(List.of());
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator));
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(99L, "staff", "干事", 31L, List.of("STAFF"), List.of()), null, List.of()));

        PlanDtos.ListItem item = planService.list(null).get(0);

        assertNull(item.completionRate());
        assertEquals(0L, item.taskCountB());
        verify(yearTargetMapper, never()).selectList(any());
        verify(taskYearMapper, never()).selectList(any());
    }

    @Test
    void 多角色计划完成率分别使用本人OWNER认定值和分配任务进度() {
        Plan plan = singleYearPlan();
        Indicator owned = manualIndicator(10L, 99L);
        Indicator assigned = manualIndicator(20L, 500L);
        Task assignedTask = task(101L);
        assignedTask.setIndicatorId(20L);
        assignedTask.setTopIndicatorId(20L);
        TaskYear assignedYear = taskYear(101L, 2026, "20");
        IndicatorYearTarget ownedTarget = manualTarget(10L, "80");
        IndicatorYearTarget assignedTarget = manualTarget(20L, "90");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(planReadScopeService.filterVisiblePlans(any())).thenReturn(List.of(plan));
        when(planReadScopeService.visibleTasks(1L)).thenReturn(List.of(assignedTask));
        when(indicatorMapper.selectList(any())).thenReturn(List.of(owned, assigned));
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of(10L, 20L));
        when(taskYearMapper.selectList(any())).thenReturn(List.of(assignedYear));
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(ownedTarget, assignedTarget));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(99L, "mixed", "兼任", 31L,
                        List.of("SCHOOL_OWNER", "STAFF"), List.of()), null, List.of()));

        PlanDtos.ListItem item = planService.list(null).get(0);

        assertEquals(0, new BigDecimal("0.5").compareTo(item.completionRate()));
    }

    @Test
    void 管理员计划完成率仍读取MANUAL全局认定值() {
        Plan plan = singleYearPlan();
        Indicator indicator = manualIndicator(10L, 500L);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(planReadScopeService.filterVisiblePlans(any())).thenReturn(List.of(plan));
        when(planReadScopeService.visibleTasks(1L)).thenReturn(List.of());
        when(indicatorMapper.selectList(any())).thenReturn(List.of(indicator));
        when(planReadScopeService.visibleIndicatorIds(any(), any())).thenReturn(Set.of(10L));
        when(yearTargetMapper.selectList(any())).thenReturn(List.of(manualTarget(10L, "90")));

        PlanDtos.ListItem item = planService.list(null).get(0);

        assertEquals(0, new BigDecimal("0.9").compareTo(item.completionRate()));
    }

    private static Plan singleYearPlan() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("测试计划");
        plan.setStartYear(2026);
        plan.setEndYear(2026);
        plan.setStatus("ACTIVE");
        plan.setSource("BLANK");
        return plan;
    }

    private static Indicator manualIndicator(Long id, Long ownerId) {
        Indicator indicator = indicator(id, null, "A");
        indicator.setOwnerId(ownerId);
        indicator.setIsQuantifiable(true);
        indicator.setStatCaliber("INCREMENT");
        indicator.setRollupMode("MANUAL");
        indicator.setTotalTarget(new BigDecimal("100"));
        return indicator;
    }

    private static IndicatorYearTarget manualTarget(Long indicatorId, String value) {
        IndicatorYearTarget target = new IndicatorYearTarget();
        target.setIndicatorId(indicatorId);
        target.setYear(2026);
        target.setManualActualValue(new BigDecimal(value));
        return target;
    }

    private static Plan draftPlan() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setName("测试计划");
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        plan.setBaselineYear(2025);
        plan.setStatus("DRAFT");
        plan.setVersion(2);
        return plan;
    }

    private static Indicator indicator(Long id, Long parentId, String level) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setParentId(parentId);
        indicator.setLevel(level);
        return indicator;
    }

    private static Task task(Long id) {
        Task task = new Task();
        task.setId(id);
        task.setStatus("DISTRIBUTED");
        task.setIsHistorical(false);
        return task;
    }

    private static TaskYear taskYear(Long taskId, Integer year, String actualValue) {
        TaskYear taskYear = taskYear(taskId, "IN_PROGRESS");
        taskYear.setYear(year);
        taskYear.setActualValue(new BigDecimal(actualValue));
        return taskYear;
    }

    private static TaskYear taskYear(Long taskId, String yearStatus) {
        TaskYear taskYear = new TaskYear();
        taskYear.setTaskId(taskId);
        taskYear.setTarget(new BigDecimal("10"));
        taskYear.setYearStatus(yearStatus);
        return taskYear;
    }

    private TemplateContent.IndicatorNode indicatorNode(String level, String code) {
        TemplateContent.IndicatorNode node = new TemplateContent.IndicatorNode();
        node.setLevel(level);
        node.setCode(code);
        node.setName(code + " 指标");
        node.setNature("CONSTRAINT");
        node.setMeasureType("NUMBER");
        node.setStatCaliber("INCREMENT");
        node.setUnitLabel("项");
        node.setIsQuantifiable(true);
        return node;
    }
}
