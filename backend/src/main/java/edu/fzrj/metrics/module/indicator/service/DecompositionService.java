package edu.fzrj.metrics.module.indicator.service;

import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.service.support.IndicatorRules;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
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
        IndicatorDtos.CreateRequest createRequest = request.getIndicator();
        createRequest.setLevel(IndicatorRules.LEVEL_A_PLUS);
        createRequest.setParentId(parentId);
        IndicatorDtos.Detail aPlus = indicatorService.create(planId, createRequest);

        TaskDtos.AllocationPutRequest allocationRequest = new TaskDtos.AllocationPutRequest();
        allocationRequest.setVersion(aPlus.version());
        allocationRequest.setRows(request.getRows());
        TaskDtos.AllocationView allocation = allocationService.putAllocation(aPlus.id(), allocationRequest);
        return new IndicatorDtos.DecompositionResponse(aPlus, allocation);
    }
}
