package edu.fzrj.metrics.module.stats.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.milestone.mapper.MakeupMapper;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskMapper;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock private PlanService planService;
    @Mock private PlanYearMapper planYearMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private TaskYearMapper taskYearMapper;
    @Mock private IndicatorMapper indicatorMapper;
    @Mock private IndicatorYearTargetMapper yearTargetMapper;
    @Mock private SubmissionMapper submissionMapper;
    @Mock private MakeupMapper makeupMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private UserRefResolver userRefResolver;
    @Mock private StatsService statsService;
    @Mock private ScreenService screenService;
    @InjectMocks private SettlementService settlementService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
        TableInfoHelper.initTableInfo(assistant, Submission.class);
    }

    @Test
    void 年度存在待审核填报时禁止核算锁定() {
        Plan plan = new Plan();
        plan.setId(1L);
        plan.setStatus("ACTIVE");
        plan.setVersion(3);
        when(planService.requirePlan(1L)).thenReturn(plan);
        PlanYear planYear = new PlanYear();
        planYear.setStatus("ACTIVE");
        when(planYearMapper.selectOne(any())).thenReturn(planYear);
        when(submissionMapper.selectCount(any())).thenReturn(1L);

        BizException error = assertThrows(BizException.class,
                () -> settlementService.settle(1L, 2026, 3));

        assertEquals(60001, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("待审核填报"));
        verify(planYearMapper, never()).updateById(any(PlanYear.class));
        verify(taskYearMapper, never()).updateById(any(TaskYear.class));
    }
}
