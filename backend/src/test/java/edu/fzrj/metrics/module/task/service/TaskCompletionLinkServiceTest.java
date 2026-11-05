package edu.fzrj.metrics.module.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.submission.service.ActualRecalcService;
import edu.fzrj.metrics.module.task.dto.TaskCompletionLinkDtos;
import edu.fzrj.metrics.module.task.entity.Task;
import edu.fzrj.metrics.module.task.entity.TaskCompletionLink;
import edu.fzrj.metrics.module.task.mapper.TaskCompletionLinkMapper;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCompletionLinkServiceTest {

    @Mock
    private TaskCompletionLinkMapper completionLinkMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private ActualRecalcService actualRecalcService;
    @InjectMocks
    private TaskCompletionLinkService completionLinkService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Task.class);
        TableInfoHelper.initTableInfo(assistant, Indicator.class);
        TableInfoHelper.initTableInfo(assistant, TaskCompletionLink.class);
    }

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(9L, "admin", "管理员", null, List.of("ADMIN"), List.of("system:manage")),
                null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 创建时允许跨计划并规范化任务顺序和同步既有进度() {
        Task task10 = task(10L, 1001L, 301L);
        Task task20 = task(20L, 1002L, 302L);
        when(taskMapper.selectOne(any())).thenReturn(task10, task20);
        when(indicatorMapper.selectOne(any())).thenReturn(indicator(301L, "INCREMENT"),
                indicator(302L, "INCREMENT"));
        doAnswer(invocation -> {
            TaskCompletionLink link = invocation.getArgument(0);
            link.setId(99L);
            link.setVersion(0);
            return 1;
        }).when(completionLinkMapper).insert(any(TaskCompletionLink.class));

        TaskCompletionLinkDtos.View result = completionLinkService.create(request(20L, 10L));

        assertEquals(99L, result.id());
        assertEquals(10L, result.taskAId());
        assertEquals(20L, result.taskBId());
        verify(completionLinkMapper).selectActivePair(10L, 20L);
        verify(actualRecalcService).syncExistingProgress(10L, 20L);
    }

    @Test
    void 反向重复关联被拒绝() {
        when(taskMapper.selectOne(any())).thenReturn(task(10L, 1001L, 301L), task(20L, 1002L, 302L));
        when(indicatorMapper.selectOne(any())).thenReturn(indicator(301L, "INCREMENT"),
                indicator(302L, "INCREMENT"));
        when(completionLinkMapper.selectActivePair(10L, 20L)).thenReturn(new TaskCompletionLink());

        BizException error = assertThrows(BizException.class,
                () -> completionLinkService.create(request(20L, 10L)));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(completionLinkMapper, never()).insert(any(TaskCompletionLink.class));
        verify(actualRecalcService, never()).syncExistingProgress(any(), any());
    }

    @Test
    void 统计口径不一致不能创建关联() {
        when(taskMapper.selectOne(any())).thenReturn(task(10L, 1001L, 301L), task(20L, 1002L, 302L));
        when(indicatorMapper.selectOne(any())).thenReturn(indicator(301L, "INCREMENT"),
                indicator(302L, "STOCK"));

        BizException error = assertThrows(BizException.class,
                () -> completionLinkService.create(request(10L, 20L)));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(completionLinkMapper, never()).insert(any(TaskCompletionLink.class));
    }

    @Test
    void 历史任务与自关联不能创建() {
        Task historical = task(10L, 1001L, 301L);
        historical.setIsHistorical(true);
        when(taskMapper.selectOne(any())).thenReturn(historical);

        BizException historicalError = assertThrows(BizException.class,
                () -> completionLinkService.create(request(10L, 20L)));
        BizException selfError = assertThrows(BizException.class,
                () -> completionLinkService.create(request(10L, 10L)));

        assertEquals(50006, historicalError.getErrorCode().getCode());
        assertEquals(10001, selfError.getErrorCode().getCode());
        verify(completionLinkMapper, never()).insert(any(TaskCompletionLink.class));
    }

    @Test
    void 关联任务不存在时返回10003() {
        when(taskMapper.selectOne(any())).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> completionLinkService.create(request(10L, 20L)));

        assertEquals(10003, error.getErrorCode().getCode());
        verify(completionLinkMapper, never()).insert(any(TaskCompletionLink.class));
    }

    private TaskCompletionLinkDtos.CreateRequest request(Long taskAId, Long taskBId) {
        TaskCompletionLinkDtos.CreateRequest request = new TaskCompletionLinkDtos.CreateRequest();
        request.setTaskAId(taskAId);
        request.setTaskBId(taskBId);
        request.setSourceNote("同一建设成果");
        return request;
    }

    private Task task(Long id, Long planId, Long indicatorId) {
        Task task = new Task();
        task.setId(id);
        task.setPlanId(planId);
        task.setIndicatorId(indicatorId);
        task.setIsHistorical(false);
        task.setDeleted(0L);
        return task;
    }

    private Indicator indicator(Long id, String caliber) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setStatCaliber(caliber);
        indicator.setDeleted(0L);
        return indicator;
    }
}
