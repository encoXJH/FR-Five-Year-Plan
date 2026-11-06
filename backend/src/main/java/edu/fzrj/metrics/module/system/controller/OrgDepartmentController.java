package edu.fzrj.metrics.module.system.controller;

import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.service.OrgDepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 全校组织架构（系统管理 → 组织架构，只读）
 */
@Tag(name = "组织架构")
@RestController
@RequestMapping("/api/v1/org-departments")
public class OrgDepartmentController {

    private final OrgDepartmentService orgDepartmentService;

    public OrgDepartmentController(OrgDepartmentService orgDepartmentService) {
        this.orgDepartmentService = orgDepartmentService;
    }

    @Operation(summary = "组织架构树",
            description = "权限：system:manage；返回全校组织树（学校 → 学校领导 / 职能部门 / 教学单位 / 校企合作 / "
                    + "学校超市 / 物业 / 医务室 / 商务 → 具体组织），同级按 sort、id 升序")
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('system:manage')")
    public List<SystemDtos.OrgDepartmentNode> tree() {
        return orgDepartmentService.tree();
    }
}
