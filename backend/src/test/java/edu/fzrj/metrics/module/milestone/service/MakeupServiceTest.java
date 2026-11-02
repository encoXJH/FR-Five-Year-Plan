package edu.fzrj.metrics.module.milestone.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.milestone.entity.Makeup;
import edu.fzrj.metrics.module.milestone.mapper.MakeupMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import edu.fzrj.metrics.module.task.service.TaskService;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MakeupServiceTest {

    @Mock
    private MakeupMapper makeupMapper;
    @Mock
    private TaskYearMapper taskYearMapper;
    @Mock
    private TaskService taskService;
    @Spy
    private DataScopeService dataScopeService;
    @Mock
    private PlanService planService;
    @InjectMocks
    private MakeupService makeupService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Makeup.class);
    }

    @BeforeEach
    void login() {
        LoginUser user = new LoginUser(9L, "admin", "管理员", null,
                List.of("ADMIN"), List.of("submission:submit"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 已归档计划不能完成补做或修改来源年度() {
        Makeup makeup = new Makeup();
        makeup.setId(1L);
        makeup.setTaskId(2L);
        makeup.setFromTaskYearId(3L);
        makeup.setStatus("PENDING");
        makeup.setVersion(0);
        Task task = new Task();
        task.setId(2L);
        task.setPlanId(4L);
        task.setUnitId(31L);
        Plan plan = new Plan();
        plan.setStatus("ARCHIVED");
        when(makeupMapper.selectOne(any())).thenReturn(makeup);
        when(taskService.requireTask(2L)).thenReturn(task);
        when(planService.requirePlan(4L)).thenReturn(plan);
        doThrow(new BizException(ErrorCode.PLAN_ARCHIVED)).when(planService).requireNotArchived(plan);

        BizException error = assertThrows(BizException.class, () -> makeupService.complete(1L, 0));

        assertEquals(30002, error.getErrorCode().getCode());
        verify(makeupMapper, never()).updateById(any(Makeup.class));
        verify(taskYearMapper, never()).updateById(any(TaskYear.class));
    }
}
