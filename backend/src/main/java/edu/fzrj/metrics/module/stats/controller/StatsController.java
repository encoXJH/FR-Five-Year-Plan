package edu.fzrj.metrics.module.stats.controller;

import edu.fzrj.metrics.module.stats.dto.StatsDtos;
import edu.fzrj.metrics.module.stats.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计接口（API 约定 15.8）：已登录，按数据范围过滤；越权 20005
 */
@Tag(name = "统计")
@RestController
@RequestMapping("/api/v1")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @Operation(summary = "总体概览（总完成率、约束性/发展性达标数、任务计数、滞后预警）",
            description = "已登录；year 缺省取当前激活年度；按数据范围过滤")
    @GetMapping("/plans/{planId}/stats/overview")
    public StatsDtos.Overview overview(@PathVariable Long planId,
                                       @RequestParam(required = false) Integer year) {
        return statsService.overview(planId, year);
    }

    @Operation(summary = "按执行单元统计（完成率排名）",
            description = "已登录；全校聚合视图（PRD Q12：学院可见全校概览与排名，不见明细）")
    @GetMapping("/plans/{planId}/stats/by-unit")
    public List<StatsDtos.UnitStat> byUnit(@PathVariable Long planId,
                                           @RequestParam(required = false) Integer year) {
        return statsService.byUnit(planId, year);
    }

    @Operation(summary = "按 A 级指标统计", description = "已登录；全校聚合视图；非量化 A 不参与统计")
    @GetMapping("/plans/{planId}/stats/by-indicator")
    public List<StatsDtos.IndicatorStat> byIndicator(@PathVariable Long planId,
                                                     @RequestParam(required = false) Integer year) {
        return statsService.byIndicator(planId, year);
    }

    @Operation(summary = "A → A+ → B 逐级下钻（每层带目标/完成值/完成率/达标状态）",
            description = "已登录；按数据范围过滤（校级负责人只看本人负责指标，学院只看本单元 B）")
    @GetMapping("/plans/{planId}/stats/tree")
    public List<StatsDtos.TreeNode> tree(@PathVariable Long planId,
                                         @RequestParam(required = false) Integer year) {
        return statsService.tree(planId, year);
    }

    @Operation(summary = "指标各年度目标与实际趋势",
            description = "已登录；全校角色与指标有效负责人可看，其余 20005")
    @GetMapping("/indicators/{indicatorId}/trend")
    public StatsDtos.TrendView trend(@PathVariable Long indicatorId) {
        return statsService.trend(indicatorId);
    }
}
