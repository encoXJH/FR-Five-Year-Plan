package edu.fzrj.metrics.module.indicator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.datascope.DataScopeService;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.dto.ManualValueDtos;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.indicator.entity.IndicatorYearTarget;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorMapper;
import edu.fzrj.metrics.module.indicator.mapper.IndicatorYearTargetMapper;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.PlanYear;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.PlanYearMapper;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * MANUAL 汇总方式 A 指标的人工认定（PRD 4.5：A 为 MANUAL 时由校级项目负责人手工认定完成值；
 * 认定值即该年 A 的完成值，认定后按目标重算完成率与达成状态）。
 */
@Service
public class ManualValueService {

    private final IndicatorMapper indicatorMapper;
    private final IndicatorYearTargetMapper yearTargetMapper;
    private final PlanMapper planMapper;
    private final PlanYearMapper planYearMapper;
    private final DataScopeService dataScopeService;

    public ManualValueService(IndicatorMapper indicatorMapper, IndicatorYearTargetMapper yearTargetMapper,
                              PlanMapper planMapper, PlanYearMapper planYearMapper,
                              DataScopeService dataScopeService) {
        this.indicatorMapper = indicatorMapper;
        this.yearTargetMapper = yearTargetMapper;
        this.planMapper = planMapper;
        this.planYearMapper = planYearMapper;
        this.dataScopeService = dataScopeService;
    }

    public ManualValueDtos.View recognize(Long indicatorId, Integer year, ManualValueDtos.RecognizeRequest request) {
        Indicator indicator = indicatorMapper.selectById(indicatorId);
        if (indicator == null || indicator.getDeleted() != 0L) {
            throw new BizException(ErrorCode.NOT_FOUND, "指标不存在");
        }
        if (indicator.getParentId() != null || !"MANUAL".equals(indicator.getRollupMode())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "只有汇总方式为手工认定（MANUAL）的 A 指标支持人工认定");
        }
        // 数据范围：管理员全校；校级负责人仅本人负责的指标（越权 20005）
        dataScopeService.canAccessIndicatorOwner(indicator.getOwnerId());

        Plan plan = planMapper.selectById(indicator.getPlanId());
        if (plan == null || "ARCHIVED".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_STATUS_INVALID, "计划已归档，不能人工认定");
        }
        PlanYear planYear = planYearMapper.selectOne(new LambdaQueryWrapper<PlanYear>()
                .eq(PlanYear::getPlanId, plan.getId())
                .eq(PlanYear::getYear, year)
                .eq(PlanYear::getDeleted, 0L));
        if (planYear == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "年度不在规划期内：" + year);
        }
        if ("LOCKED".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.YEAR_SETTLED, "年度已核算锁定，不能人工认定");
        }
        if (!"ACTIVE".equals(planYear.getStatus())) {
            throw new BizException(ErrorCode.YEAR_NOT_ACTIVATED, "年度未激活，不能人工认定");
        }

        IndicatorYearTarget row = yearTargetMapper.selectOne(new LambdaQueryWrapper<IndicatorYearTarget>()
                .eq(IndicatorYearTarget::getIndicatorId, indicatorId)
                .eq(IndicatorYearTarget::getYear, year)
                .eq(IndicatorYearTarget::getDeleted, 0L));
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该年度目标不存在");
        }
        if (!row.getVersion().equals(request.getVersion())) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + row.getVersion());
        }

        Long userId = SecurityUtils.getUserId();
        row.setManualActualValue(request.getValue());
        row.setManualNote(request.getNote());
        // 认定值即 A 当年完成值：按口径构造结果重算完成率与达成状态（STOCK / GROWTH 口径取总量口径）
        row.setActualValue(request.getValue());
        MetricCalculator.YearResult result = new MetricCalculator.YearResult(
                request.getValue(), null, null, request.getValue(), null, null);
        String caliber = indicator.getStatCaliber();
        row.setCompletionRate(MetricCalculator.completionRate(caliber, result, row.getTarget()));
        row.setAchieveStatus(MetricCalculator.achieveStatus(
                indicator.getTargetDirection(), caliber, result, row.getTarget()));
        row.setCalculatedAt(LocalDateTime.now());
        row.setUpdatedBy(userId);
        yearTargetMapper.updateById(row);

        return new ManualValueDtos.View(indicatorId, year, row.getTarget(), row.getActualValue(),
                row.getCompletionRate(), row.getAchieveStatus(), row.getManualActualValue(),
                row.getManualNote(), row.getCalculatedAt(), row.getVersion());
    }
}
