package edu.fzrj.metrics.module.indicator.service;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.indicator.controller.IndicatorController;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import edu.fzrj.metrics.module.task.service.AllocationService;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecompositionServiceTest {

    private static final Long PLAN_ID = 11L;
    private static final Long PARENT_ID = 21L;
    private static final Long A_PLUS_ID = 31L;
    private static final Integer CREATED_VERSION = 4;

    @Mock
    private IndicatorService indicatorService;
    @Mock
    private AllocationService allocationService;

    @InjectMocks
    private DecompositionService decompositionService;

    @Test
    void 分解编排入口禁止新增A加并返回40006() {
        IndicatorDtos.DecompositionRequest request = request();

        BizException error = assertThrows(BizException.class,
                () -> decompositionService.decompose(PLAN_ID, PARENT_ID, request));

        assertEquals(40006, error.getErrorCode().getCode());
        verifyNoInteractions(indicatorService, allocationService);
    }

    @Test
    void 聚合服务是独立事务Bean() throws NoSuchMethodException {
        Method method = DecompositionService.class.getDeclaredMethod(
                "decompose", Long.class, Long.class, IndicatorDtos.DecompositionRequest.class);

        assertNotNull(DecompositionService.class.getAnnotation(Service.class));
        assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    void 控制器声明分解路径和双重权限及审计幂等注解() throws NoSuchMethodException {
        Method method = IndicatorController.class.getDeclaredMethod(
                "decompose", Long.class, Long.class, IndicatorDtos.DecompositionRequest.class);

        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/api/v1/plans/{planId}/indicators/{parentId}/decomposition"},
                mapping.value());
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertNotNull(preAuthorize);
        assertEquals("hasAuthority('indicator:edit') and hasAuthority('allocation:edit')",
                preAuthorize.value());
        assertNotNull(method.getAnnotation(Idempotent.class));
        assertNotNull(method.getAnnotation(AuditLog.class));
        assertNotNull(method.getAnnotation(Operation.class));
    }

    private IndicatorDtos.DecompositionRequest request() {
        IndicatorDtos.CreateRequest indicator = new IndicatorDtos.CreateRequest();
        indicator.setLevel("A");
        indicator.setParentId(999L);
        indicator.setName("新增省级教学成果奖");
        indicator.setNature("CONSTRAINT");

        TaskDtos.AllocationRow row = new TaskDtos.AllocationRow();
        row.setUnitId(41L);
        row.setOwnerId(51L);
        row.setYearTargets(List.of());

        IndicatorDtos.DecompositionRequest request = new IndicatorDtos.DecompositionRequest();
        request.setIndicator(indicator);
        request.setRows(List.of(row));
        return request;
    }

    private IndicatorDtos.Detail aPlus() {
        return new IndicatorDtos.Detail(A_PLUS_ID, PLAN_ID, null, null, PARENT_ID, null,
                "A_PLUS", "A01-1", "新增省级教学成果奖", "CONSTRAINT", "NUMBER", "INCREMENT",
                "项", "HIGHER_BETTER", null, null, null, null, 0, null, BigDecimal.TEN,
                null, 0, "DRAFT", List.of(), List.of(), List.of(), CREATED_VERSION, null, null);
    }
}
