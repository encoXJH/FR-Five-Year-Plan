package edu.fzrj.metrics.module.indicator.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.module.indicator.dto.ManualValueDtos;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualValueServiceTest {

    @Mock private IndicatorMapper indicatorMapper;
    @Mock private IndicatorYearTargetMapper yearTargetMapper;
    @Mock private PlanMapper planMapper;
    @Mock private PlanYearMapper planYearMapper;
    @Mock private DataScopeService dataScopeService;
    @InjectMocks private ManualValueService manualValueService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlanYear.class);
        TableInfoHelper.initTableInfo(assistant, IndicatorYearTarget.class);
    }

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(20L, "owner", "建设方向负责人", null,
                        List.of("SCHOOL_OWNER"), List.of()), null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOCK", "GROWTH"})
    void 手工认定总量时同步保存累计统计字段(String caliber) {
        Indicator indicator = new Indicator();
        indicator.setId(1L);
        indicator.setPlanId(2L);
        indicator.setRollupMode("MANUAL");
        indicator.setStatCaliber(caliber);
        indicator.setTargetDirection("HIGHER_BETTER");
        indicator.setOwnerId(20L);
        indicator.setDeleted(0L);
        when(indicatorMapper.selectById(1L)).thenReturn(indicator);

        Plan plan = new Plan();
        plan.setId(2L);
        plan.setStatus("ACTIVE");
        when(planMapper.selectById(2L)).thenReturn(plan);
        PlanYear planYear = new PlanYear();
        planYear.setStatus("ACTIVE");
        when(planYearMapper.selectOne(any())).thenReturn(planYear);

        IndicatorYearTarget row = new IndicatorYearTarget();
        row.setTarget(new BigDecimal("100"));
        row.setVersion(1);
        when(yearTargetMapper.selectOne(any())).thenReturn(row);
        ManualValueDtos.RecognizeRequest request = new ManualValueDtos.RecognizeRequest();
        request.setValue(new BigDecimal("80"));
        request.setVersion(1);

        manualValueService.recognize(1L, 2026, request);

        assertEquals(new BigDecimal("80"), row.getActualValue());
        assertEquals(new BigDecimal("80"), row.getActualTotal());
        verify(yearTargetMapper).updateById(row);
    }
}
