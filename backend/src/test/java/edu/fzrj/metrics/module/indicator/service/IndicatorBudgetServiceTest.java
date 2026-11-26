package edu.fzrj.metrics.module.indicator.service;

import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.file.mapper.FileInfoMapper;
import edu.fzrj.metrics.module.file.mapper.FileRefMapper;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.service.UserRefResolver;
import edu.fzrj.metrics.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorBudgetServiceTest {

    @Mock private IndicatorService indicatorService;
    @Mock private SubmissionMapper submissionMapper;
    @Mock private FileRefMapper fileRefMapper;
    @Mock private FileInfoMapper fileInfoMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private UserRefResolver userRefResolver;
    @Mock private PlanService planService;
    @Mock private DataScopeService dataScopeService;
    @InjectMocks private IndicatorBudgetService budgetService;

    private Indicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new Indicator();
        indicator.setId(9L);
        indicator.setPlanId(4L);
        indicator.setLevel("A");
        indicator.setVersion(7);
        lenient().when(indicatorService.requireIndicator(9L)).thenReturn(indicator);
        lenient().when(planService.requirePlan(4L)).thenReturn(new Plan());
        login(1L, "ADMIN");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 仅A级指标可维护预算() {
        indicator.setLevel("A_PLUS");
        BizException ex = assertThrows(BizException.class,
                () -> budgetService.putBudget(9L, request("100")));
        assertEquals(ErrorCode.LEVEL_INVALID, ex.getErrorCode());
    }

    @Test
    void 建设方向负责人只能维护本人负责指标() {
        login(20L, "SCHOOL_OWNER");
        when(dataScopeService.isAllData(any())).thenReturn(false);
        when(dataScopeService.isOwnerScope(any())).thenReturn(true);
        when(indicatorService.effectiveOwnerOf(indicator)).thenReturn(99L);

        BizException ex = assertThrows(BizException.class,
                () -> budgetService.putBudget(9L, request("100")));
        assertEquals(ErrorCode.DATA_SCOPE_DENIED, ex.getErrorCode());
    }

    @Test
    void 建设方向负责人可维护本人负责指标() {
        login(20L, "SCHOOL_OWNER");
        when(dataScopeService.isAllData(any())).thenReturn(false);
        when(dataScopeService.isOwnerScope(any())).thenReturn(true);
        when(indicatorService.effectiveOwnerOf(indicator)).thenReturn(20L);
        when(indicatorService.spentOfIndicator(indicator)).thenReturn(BigDecimal.ZERO);
        when(indicatorService.updateIndicator(indicator)).thenReturn(1);

        budgetService.putBudget(9L, request("100"));

        assertEquals(new BigDecimal("100"), indicator.getTotalBudget());
        verify(indicatorService).updateIndicator(indicator);
    }

    @Test
    void 总预算不能低于已认定支出() {
        when(dataScopeService.isAllData(any())).thenReturn(true);
        when(indicatorService.spentOfIndicator(indicator)).thenReturn(new BigDecimal("50"));

        BizException ex = assertThrows(BizException.class,
                () -> budgetService.putBudget(9L, request("30")));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void 总预算必须非负且最多两位小数() {
        when(dataScopeService.isAllData(any())).thenReturn(true);

        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> budgetService.putBudget(9L, request("-1"))).getErrorCode());
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> budgetService.putBudget(9L, request("1.234"))).getErrorCode());
    }

    @Test
    void 清除预算不受已认定支出限制() {
        when(dataScopeService.isAllData(any())).thenReturn(true);
        when(indicatorService.updateIndicator(indicator)).thenReturn(1);
        IndicatorDtos.BudgetPutRequest request = new IndicatorDtos.BudgetPutRequest();
        request.setVersion(7);
        request.setTotalBudget(null);
        request.setReason("取消预算管理");

        budgetService.putBudget(9L, request);

        assertNull(indicator.getTotalBudget());
    }

    @Test
    void 版本冲突返回10005() {
        when(dataScopeService.isAllData(any())).thenReturn(true);
        when(indicatorService.spentOfIndicator(indicator)).thenReturn(BigDecimal.ZERO);
        when(indicatorService.updateIndicator(indicator)).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> budgetService.putBudget(9L, request("100")));
        assertEquals(ErrorCode.VERSION_CONFLICT, ex.getErrorCode());
    }

    @Test
    void 无可见任务时支出明细为空() {
        when(indicatorService.spentOfIndicator(indicator)).thenReturn(BigDecimal.ZERO);
        when(indicatorService.visibleTasks(indicator)).thenReturn(List.of());

        IndicatorDtos.BudgetView view = budgetService.view(9L, new IndicatorDtos.BudgetQuery());

        assertNull(view.totalBudget());
        assertEquals(BigDecimal.ZERO, view.spentBudget());
        assertEquals(0L, view.expenses().getTotal());
        assertEquals(List.of(), view.expenses().getItems());
    }

    private IndicatorDtos.BudgetPutRequest request(String budget) {
        IndicatorDtos.BudgetPutRequest request = new IndicatorDtos.BudgetPutRequest();
        request.setVersion(7);
        request.setTotalBudget(new BigDecimal(budget));
        request.setReason("预算维护");
        return request;
    }

    private static void login(Long id, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(id, "u" + id, "用户" + id, null, List.of(roles), List.of()), null, List.of()));
    }
}
