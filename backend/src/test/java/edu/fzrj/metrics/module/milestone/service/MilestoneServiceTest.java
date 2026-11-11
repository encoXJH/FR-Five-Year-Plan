package edu.fzrj.metrics.module.milestone.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import edu.fzrj.metrics.module.milestone.dto.MilestoneDtos;
import edu.fzrj.metrics.module.milestone.entity.Milestone;
import edu.fzrj.metrics.module.milestone.mapper.MilestoneMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MilestoneServiceTest {

    @Mock
    private MilestoneMapper milestoneMapper;
    @Mock
    private IndicatorService indicatorService;
    @Mock
    private TaskService taskService;
    @Mock
    private PlanService planService;
    @Spy
    private DataScopeService dataScopeService;
    @Mock
    private UserRefResolver userRefResolver;

    @InjectMocks
    private MilestoneService milestoneService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Milestone.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, Task.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 建设方向负责人不能创建他人指标的里程碑() {
        login("SCHOOL_OWNER", "indicator:edit");
        Indicator indicator = indicator(88L);
        when(indicatorService.requireIndicator(5L)).thenReturn(indicator);
        when(indicatorService.effectiveOwnerOf(indicator)).thenReturn(88L);
        MilestoneDtos.CreateRequest request = new MilestoneDtos.CreateRequest();
        request.setIndicatorId(5L);
        request.setName("他人指标里程碑");
        request.setDueDate(LocalDate.of(2026, 12, 31));

        BizException error = assertThrows(BizException.class, () -> milestoneService.create(request));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(milestoneMapper, never()).insert(any(Milestone.class));
    }

    @Test
    void 建设方向负责人不能修改他人指标的里程碑() {
        login("SCHOOL_OWNER", "indicator:edit");
        Milestone milestone = indicatorMilestone();
        Indicator indicator = indicator(88L);
        when(milestoneMapper.selectOne(any())).thenReturn(milestone);
        when(indicatorService.requireIndicator(5L)).thenReturn(indicator);
        when(indicatorService.effectiveOwnerOf(indicator)).thenReturn(88L);
        PatchHelper patch = new PatchHelper(new ObjectMapper().createObjectNode()
                .put("name", "越权修改").put("version", 0));

        BizException error = assertThrows(BizException.class, () -> milestoneService.patch(10L, patch));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(milestoneMapper, never()).updateById(any(Milestone.class));
    }

    @Test
    void 纯学院负责人即使带指标编辑权限也不能凭指标编号删除里程碑() {
        login("UNIT_OWNER", "indicator:edit", "submission:submit");
        when(milestoneMapper.selectOne(any())).thenReturn(indicatorMilestone());

        BizException error = assertThrows(BizException.class, () -> milestoneService.delete(10L, 0));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(milestoneMapper, never()).update(any(), any());
    }

    @Test
    void 已归档计划不能删除里程碑() {
        login("ADMIN", "indicator:edit");
        Milestone milestone = indicatorMilestone();
        milestone.setPlanId(4L);
        Indicator indicator = indicator(88L);
        when(milestoneMapper.selectOne(any())).thenReturn(milestone);
        when(indicatorService.requireIndicator(5L)).thenReturn(indicator);
        edu.fzrj.metrics.module.plan.entity.Plan plan = new edu.fzrj.metrics.module.plan.entity.Plan();
        plan.setStatus("ARCHIVED");
        when(planService.requirePlan(4L)).thenReturn(plan);
        doThrow(new BizException(edu.fzrj.metrics.common.error.ErrorCode.PLAN_ARCHIVED))
                .when(planService).requireNotArchived(plan);

        BizException error = assertThrows(BizException.class, () -> milestoneService.delete(10L, 0));

        assertEquals(30002, error.getErrorCode().getCode());
        verify(milestoneMapper, never()).update(any(), any());
    }

    @Test
    void 任务级里程碑修改仍校验任务数据范围() {
        login("UNIT_OWNER", "submission:submit");
        Milestone milestone = new Milestone();
        milestone.setId(10L);
        milestone.setTaskId(7L);
        Task task = new Task();
        task.setId(7L);
        when(milestoneMapper.selectOne(any())).thenReturn(milestone);
        when(taskService.requireTask(7L)).thenReturn(task);
        doThrow(new BizException(edu.fzrj.metrics.common.error.ErrorCode.DATA_SCOPE_DENIED))
                .when(taskService).requireAccess(task);
        PatchHelper patch = new PatchHelper(new ObjectMapper().createObjectNode()
                .put("name", "越权修改").put("version", 0));

        BizException error = assertThrows(BizException.class, () -> milestoneService.patch(10L, patch));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(milestoneMapper, never()).updateById(any(Milestone.class));
    }

    private static Milestone indicatorMilestone() {
        Milestone milestone = new Milestone();
        milestone.setId(10L);
        milestone.setIndicatorId(5L);
        milestone.setVersion(0);
        return milestone;
    }

    private static Indicator indicator(Long ownerId) {
        Indicator indicator = new Indicator();
        indicator.setId(5L);
        indicator.setOwnerId(ownerId);
        return indicator;
    }

    private static void login(String role, String... permissions) {
        LoginUser user = new LoginUser(99L, "user", "用户", 31L,
                List.of(role), List.of(permissions));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
