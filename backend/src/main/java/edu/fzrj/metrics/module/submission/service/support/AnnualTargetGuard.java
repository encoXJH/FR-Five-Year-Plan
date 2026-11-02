package edu.fzrj.metrics.module.submission.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.submission.mapper.SubmissionMapper;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 提交与审核共用的年度目标上限校验。 */
@Component
public class AnnualTargetGuard {

    private final TaskYearMapper taskYearMapper;
    private final SubmissionMapper submissionMapper;

    public AnnualTargetGuard(TaskYearMapper taskYearMapper, SubmissionMapper submissionMapper) {
        this.taskYearMapper = taskYearMapper;
        this.submissionMapper = submissionMapper;
    }

    public void checkSubmit(Submission submission, Indicator indicator, TaskYear taskYear) {
        String caliber = submission.getStatCaliber();
        if (!requiresCheck(caliber, indicator.getTargetDirection())) {
            return;
        }
        assertWithinTarget(caliber, indicator.getTargetDirection(), taskYear.getTarget(),
                approvedValues(submission, caliber), effectiveValue(caliber, submission));
    }

    /** 审核写入前锁定任务年度，再基于最新已通过记录投影候选值。 */
    public void checkReview(Submission submission, Indicator indicator,
                            SubmissionDtos.MetricValue candidate) {
        String caliber = submission.getStatCaliber();
        if (!requiresCheck(caliber, indicator.getTargetDirection())) {
            return;
        }
        TaskYear taskYear = taskYearMapper.selectByIdForUpdate(submission.getTaskYearId());
        if (taskYear == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务年度不存在");
        }
        assertWithinTarget(caliber, indicator.getTargetDirection(), taskYear.getTarget(),
                approvedValues(submission, caliber), candidateValue(caliber, candidate));
    }

    static void assertWithinTarget(String caliber, String direction, BigDecimal target,
                                   List<MetricCalculator.ApprovedValue> approvedValues,
                                   MetricCalculator.ApprovedValue candidate) {
        if (!requiresCheck(caliber, direction)) {
            return;
        }
        List<MetricCalculator.ApprovedValue> projectedValues = new ArrayList<>(approvedValues);
        projectedValues.add(candidate);
        MetricCalculator.YearResult projected = MetricCalculator.yearActual(caliber, projectedValues, null);
        BigDecimal rate = MetricCalculator.completionRate(caliber, projected, target);
        if (rate != null && rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(ErrorCode.ANNUAL_TARGET_EXCEEDED);
        }
    }

    private static boolean requiresCheck(String caliber, String direction) {
        return !"BOOLEAN".equals(caliber) && "HIGHER_BETTER".equals(direction);
    }

    private List<MetricCalculator.ApprovedValue> approvedValues(Submission candidate, String caliber) {
        return submissionMapper.selectList(new LambdaQueryWrapper<Submission>()
                        .eq(Submission::getTaskYearId, candidate.getTaskYearId())
                        .eq(Submission::getStatus, "APPROVED")
                        .ne(Submission::getId, candidate.getId())
                        .eq(Submission::getDeleted, 0L)
                        .orderByAsc(Submission::getReviewedAt)
                        .orderByAsc(Submission::getId))
                .stream().map(s -> effectiveValue(caliber, s)).toList();
    }

    private static MetricCalculator.ApprovedValue candidateValue(String caliber,
                                                                 SubmissionDtos.MetricValue value) {
        return switch (caliber) {
            case "INCREMENT" -> MetricCalculator.ApprovedValue.increment(value.getAmount());
            case "STOCK", "GROWTH" -> MetricCalculator.ApprovedValue.total(value.getTotal());
            case "RATIO" -> MetricCalculator.ApprovedValue.ratio(value.getNumerator(), value.getDenominator());
            case "DIRECT_PERCENT" -> MetricCalculator.ApprovedValue.percent(value.getPercent());
            case "BOOLEAN" -> MetricCalculator.ApprovedValue.bool(value.getAchieved());
            default -> throw new BizException(ErrorCode.INTERNAL_ERROR, "未知统计口径：" + caliber);
        };
    }

    private static MetricCalculator.ApprovedValue effectiveValue(String caliber, Submission s) {
        return switch (caliber) {
            case "INCREMENT" -> MetricCalculator.ApprovedValue.increment(
                    s.getApprovedAmount() != null ? s.getApprovedAmount() : s.getAmount());
            case "STOCK", "GROWTH" -> MetricCalculator.ApprovedValue.total(
                    s.getApprovedTotal() != null ? s.getApprovedTotal() : s.getTotal());
            case "RATIO" -> MetricCalculator.ApprovedValue.ratio(
                    s.getApprovedNumerator() != null ? s.getApprovedNumerator() : s.getNumerator(),
                    s.getApprovedDenominator() != null ? s.getApprovedDenominator() : s.getDenominator());
            case "DIRECT_PERCENT" -> MetricCalculator.ApprovedValue.percent(
                    s.getApprovedPercent() != null ? s.getApprovedPercent() : s.getPercent());
            case "BOOLEAN" -> MetricCalculator.ApprovedValue.bool(
                    s.getApprovedAchieved() != null ? s.getApprovedAchieved() : s.getAchieved());
            default -> throw new BizException(ErrorCode.INTERNAL_ERROR, "未知统计口径：" + caliber);
        };
    }
}
