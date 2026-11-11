package edu.fzrj.metrics.module.indicator.service;

import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorExcelServiceTest {

    private static final Long PLAN_ID = 11L;
    private static final Long USER_ID = 99L;

    @Mock
    private PlanService planService;
    @Mock
    private IndicatorService indicatorService;
    @Mock
    private ImportPreviewStore importPreviewStore;
    @Mock
    private IndicatorMapper indicatorMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private DataScopeService dataScopeService;

    @InjectMocks
    private IndicatorExcelService indicatorExcelService;

    private Plan plan;
    private SysUser owner;

    @BeforeEach
    void setUp() {
        LoginUser user = new LoginUser(USER_ID, "owner1", "建设方向负责人", null,
                List.of("ADMIN"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        plan = new Plan();
        plan.setId(PLAN_ID);
        plan.setName("测试计划");
        plan.setStartYear(2026);
        plan.setEndYear(2030);
        plan.setStatus("DRAFT");

        owner = new SysUser();
        owner.setId(USER_ID);
        owner.setUsername("owner1");
        owner.setIsEnabled(true);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 预览行统一返回行号上级编号和五年目标() {
        when(planService.requirePlan(PLAN_ID)).thenReturn(plan);
        when(indicatorService.existingCodes(PLAN_ID)).thenReturn(Set.of());
        when(indicatorMapper.selectList(any())).thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of(owner));
        when(dataScopeService.isAllData(any())).thenReturn(true);
        when(importPreviewStore.save(any())).thenReturn("import-1");
        MockMultipartFile file = new MockMultipartFile("file", "indicators.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                indicatorExcelService.buildImportTemplate(plan));

        IndicatorDtos.ImportPreviewResponse response = indicatorExcelService.preview(PLAN_ID, file);

        assertEquals("import-1", response.importId());
        assertEquals(1, response.validRows());
        assertEquals(0, response.invalidRows());
        assertEquals(1, response.rows().size());
        assertEquals(2, response.rows().get(0).rowNum());
        assertNull(response.rows().get(0).parentCode());
        assertEquals(0, new BigDecimal("5").compareTo(response.rows().get(0).totalTarget()));
    }

    @Test
    void 确认导入只创建可继续编辑的A草稿() {
        ParsedImport draft = new ParsedImport();
        draft.setPlanId(PLAN_ID);
        draft.setUserId(USER_ID);
        draft.getRows().add(importRow("A", "A01", null));
        when(importPreviewStore.get("batch-1")).thenReturn(draft);
        when(planService.requirePlan(PLAN_ID)).thenReturn(plan);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(owner));
        when(indicatorService.existingCodes(PLAN_ID)).thenReturn(Set.of());
        when(indicatorMapper.selectList(any())).thenReturn(List.of());

        IndicatorDtos.ImportConfirmResponse response = indicatorExcelService.confirm(PLAN_ID, "batch-1");

        assertEquals(1, response.aCount());
        assertEquals(0, response.aPlusCount());
        ArgumentCaptor<Indicator> indicators = ArgumentCaptor.forClass(Indicator.class);
        verify(indicatorMapper).insert(indicators.capture());
        assertEquals("DRAFT", indicators.getValue().getStatus());
        assertEquals("DRAFT", plan.getStatus());
        assertNull(plan.getFinalizedAt());
        verify(importPreviewStore).remove("batch-1");
    }

    @Test
    void 确认导入拒绝历史A加批次并返回40006() {
        ParsedImport draft = new ParsedImport();
        draft.setPlanId(PLAN_ID);
        draft.setUserId(USER_ID);
        draft.getRows().add(importRow("A_PLUS", "A01-1", "A01"));
        when(importPreviewStore.get("batch-legacy")).thenReturn(draft);

        BizException error = assertThrows(BizException.class,
                () -> indicatorExcelService.confirm(PLAN_ID, "batch-legacy"));

        assertEquals(40006, error.getErrorCode().getCode());
        verify(indicatorMapper, times(0)).insert(any(Indicator.class));
    }

    private ParsedImport.Row importRow(String level, String code, String parentCode) {
        ParsedImport.Row row = new ParsedImport.Row();
        row.setLevel(level);
        row.setCode(code);
        row.setParentCode(parentCode);
        row.setName(code + " 指标");
        row.setNature("CONSTRAINT");
        row.setMeasureType("NUMBER");
        row.setStatCaliber("INCREMENT");
        row.setUnitLabel("项");
        row.setTargetDirection("HIGHER_BETTER");
        row.setRollupMode("AUTO_SUM");
        row.setQuantifiable(true);
        row.setTotalTarget(BigDecimal.ONE);
        row.setOwnerUsername("owner1");
        return row;
    }
}
