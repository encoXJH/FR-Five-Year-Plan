package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.OrgDepartment;
import edu.fzrj.metrics.module.system.mapper.OrgDepartmentMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全校组织架构（只读）：按 parentId 组装树，同级按 sort、id 升序。
 */
@Service
public class OrgDepartmentService {

    private static final long ROOT_PARENT_ID = 0L;

    private final OrgDepartmentMapper orgDepartmentMapper;

    public OrgDepartmentService(OrgDepartmentMapper orgDepartmentMapper) {
        this.orgDepartmentMapper = orgDepartmentMapper;
    }

    public List<SystemDtos.OrgDepartmentNode> tree() {
        List<OrgDepartment> all = orgDepartmentMapper.selectList(new LambdaQueryWrapper<OrgDepartment>()
                .eq(OrgDepartment::getDeleted, 0L)
                .orderByAsc(OrgDepartment::getSort)
                .orderByAsc(OrgDepartment::getId));
        Map<Long, List<OrgDepartment>> childrenByParent = new LinkedHashMap<>();
        for (OrgDepartment department : all) {
            childrenByParent.computeIfAbsent(department.getParentId(), key -> new ArrayList<>())
                    .add(department);
        }
        return buildChildren(ROOT_PARENT_ID, childrenByParent);
    }

    private List<SystemDtos.OrgDepartmentNode> buildChildren(
            Long parentId, Map<Long, List<OrgDepartment>> childrenByParent) {
        return childrenByParent.getOrDefault(parentId, List.of()).stream()
                .map(department -> new SystemDtos.OrgDepartmentNode(
                        department.getId(),
                        department.getParentId(),
                        department.getName(),
                        department.getCategory(),
                        department.getSort(),
                        department.getIsEnabled(),
                        buildChildren(department.getId(), childrenByParent)))
                .toList();
    }
}
