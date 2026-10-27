package edu.fzrj.metrics.module.stats.controller;

import edu.fzrj.metrics.module.stats.dto.ScreenDtos;
import edu.fzrj.metrics.module.stats.service.ScreenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可视化大屏接口（API 约定 15.8）
 */
@Tag(name = "可视化大屏")
@RestController
@RequestMapping("/api/v1/screens")
public class ScreenController {

    private final ScreenService screenService;

    public ScreenController(ScreenService screenService) {
        this.screenService = screenService;
    }

    @Operation(summary = "大屏聚合数据（总完成率、达标数、单元排名、A 级指标、趋势、预警、调整统计、最近动态；缓存 1–5 分钟）",
            description = "权限：screen:view；year 缺省取当前激活年度")
    @GetMapping("/{planId}")
    @PreAuthorize("hasAuthority('screen:view')")
    public ScreenDtos.ScreenView screen(@PathVariable Long planId,
                                        @RequestParam(required = false) Integer year) {
        return screenService.screen(planId, year);
    }
}
