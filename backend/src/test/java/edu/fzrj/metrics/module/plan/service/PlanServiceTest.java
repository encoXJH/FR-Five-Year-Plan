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
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    void 启动计划后年度默认未激活等待管理员手动激活() {
        Plan plan = draftPlan();
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);

        planService.activate(1L, 2);

        ArgumentCaptor<PlanYear> years = ArgumentCaptor.forClass(PlanYear.class);
        verify(planYearMapper, times(5)).insert(years.capture());
        assertTrue(years.getAllValues().stream().allMatch(year -> "NOT_ACTIVATED".equals(year.getStatus())));
        assertTrue(years.getAllValues().stream().allMatch(year -> year.getActivatedAt() == null));
        assertTrue(years.getAllValues().stream().allMatch(year -> year.getActivatedBy() == null));
        assertEquals("ACTIVE", plan.getStatus());
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
    void 已归档计划按版本逻辑删除并保留历史数据() {
        Plan plan = draftPlan();
        plan.setStatus("ARCHIVED");
        when(planMapper.selectOne(any())).thenReturn(plan);
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
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(planMapper.updateById(any(Plan.class))).thenReturn(1);
        PlanDtos.DeleteRequest request = new PlanDtos.DeleteRequest();
        request.setVersion(2);
        request.setReason("删除空白草稿");

        planService.delete(1L, request);

        assertEquals(1L, plan.getDeleted());
    }

    @Test
    void 执行中计划必须先归档再删除() {
        Plan plan = draftPlan();
        plan.setStatus("ACTIVE");
        when(planMapper.selectOne(any())).thenReturn(plan);
        PlanDtos.DeleteRequest request = new PlanDtos.DeleteRequest();
        request.setVersion(2);
        request.setReason("清理测试计划");

        BizException error = assertThrows(BizException.class, () -> planService.delete(1L, request));

        assertEquals(30001, error.getErrorCode().getCode());
        assertNull(plan.getDeleted());
        verify(planMapper, never()).updateById(any(Plan.class));
    }

    @Test
    void 删除计划版本冲突时不写入() {
        Plan plan = draftPlan();
        plan.setStatus("ARCHIVED");
        when(planMapper.selectOne(any())).thenReturn(plan);
        PlanDtos.DeleteRequest request = new PlanDtos.DeleteRequest();
        request.setVersion(1);
        request.setReason("清理测试计划");

        BizException error = assertThrows(BizException.class, () -> planService.delete(1L, request));

        assertEquals(10005, error.getErrorCode().getCode());
        assertNull(plan.getDeleted());
        verify(planMapper, never()).updateById(any(Plan.class));
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
