package edu.fzrj.metrics.module.indicator.service;

import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.task.service.AllocationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DecompositionService {

    private final IndicatorService indicatorService;
    private final AllocationService allocationService;

    public DecompositionService(IndicatorService indicatorService, AllocationService allocationService) {
        this.indicatorService = indicatorService;
        this.allocationService = allocationService;
    }

    @Transactional
    public IndicatorDtos.DecompositionResponse decompose(Long planId, Long parentId,
                                                         IndicatorDtos.DecompositionRequest request) {
        throw new BizException(ErrorCode.LEVEL_INVALID, "新流程不再支持创建 A+，请直接在 A 下分配 B");
    }
}
