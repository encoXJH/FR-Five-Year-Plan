package edu.fzrj.metrics.module.submission.service.support;

import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.indicator.entity.Indicator;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.entity.Submission;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import edu.fzrj.metrics.module.task.mapper.TaskYearMapper;
import org.springframework.stereotype.Component;

/** 年度允许超额完成；审核时仍需锁定任务年度以串行重算完成值。 */
@Component
public class AnnualTargetGuard {

    private final TaskYearMapper taskYearMapper;

    public AnnualTargetGuard(TaskYearMapper taskYearMapper) {
        this.taskYearMapper = taskYearMapper;
    }

    public void checkSubmit(Submission submission, Indicator indicator, TaskYear taskYear) {
        // 年度完成值允许超过目标，不再进行上限校验。
    }

    /** 审核写入前锁定任务年度；所有口径都加锁，保证并发审核与缓存重算串行。 */
    public void checkReview(Submission submission, Indicator indicator,
                            SubmissionDtos.MetricValue candidate) {
        lockForReview(submission);
    }

    /** 驳回也使用同一锁顺序，避免与通过审核及完成值重算交错。 */
    public TaskYear lockForReview(Submission submission) {
        TaskYear taskYear = taskYearMapper.selectByIdForUpdate(submission.getTaskYearId());
        if (taskYear == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务年度不存在");
        }
        return taskYear;
    }

}
