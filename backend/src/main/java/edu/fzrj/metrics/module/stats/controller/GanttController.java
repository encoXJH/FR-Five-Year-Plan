package edu.fzrj.metrics.module.stats.controller;

import edu.fzrj.metrics.module.stats.dto.GanttDtos;
import edu.fzrj.metrics.module.stats.service.GanttService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "计划甘特图")
@RestController
public class GanttController {

    private final GanttService ganttService;

    public GanttController(GanttService ganttService) {
        this.ganttService = ganttService;
    }

    @Operation(summary = "全计划或单 A 甘特图聚合",
            description = "已登录，按数据范围批量聚合 A→B 全年度、里程碑、FS 依赖及 CPM 关键路径；"
                    + "criticalPath.status：EMPTY/OK/NO_DEPENDENCIES/UNSCHEDULED/CYCLE_DETECTED/YEAR_CONFLICT；"
                    + "indicatorId 支持 A 或归一到父 A 的历史 A+；越权 20005，跨计划 10001")
    @GetMapping("/api/v1/plans/{planId}/gantt")
    public GanttDtos.View get(@PathVariable Long planId,
                              @RequestParam(required = false) Long indicatorId) {
        return ganttService.get(planId, indicatorId);
    }
}
