package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.OrgDepartment;
import edu.fzrj.metrics.module.system.mapper.OrgDepartmentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgDepartmentServiceTest {

    @Mock
    private OrgDepartmentMapper orgDepartmentMapper;
    @InjectMocks
    private OrgDepartmentService orgDepartmentService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrgDepartment.class);
    }

    @Test
    void 按父子关系组装组织树() {
        when(orgDepartmentMapper.selectList(any())).thenReturn(List.of(
                department(2001L, 0L, "福州软件职业技术学院", "SCHOOL", 1),
                department(2101L, 2001L, "学校领导", "LEADERSHIP", 1),
                department(2102L, 2001L, "职能部门", "FUNCTIONAL", 2),
                department(2103L, 2001L, "教学单位", "TEACHING", 3),
                department(2111L, 2101L, "学校党政", "LEADERSHIP", 1)));

        List<SystemDtos.OrgDepartmentNode> tree = orgDepartmentService.tree();

        assertEquals(1, tree.size());
        SystemDtos.OrgDepartmentNode root = tree.get(0);
        assertEquals("福州软件职业技术学院", root.name());
        assertEquals(List.of("学校领导", "职能部门", "教学单位"),
                root.children().stream().map(SystemDtos.OrgDepartmentNode::name).toList());
        assertEquals("学校党政", root.children().get(0).children().get(0).name());
        assertEquals("TEACHING", root.children().get(2).category());
        assertTrue(root.children().get(2).children().isEmpty());
    }

    @Test
    void 空数据返回空树() {
        when(orgDepartmentMapper.selectList(any())).thenReturn(List.of());
        assertTrue(orgDepartmentService.tree().isEmpty());
    }

    private static OrgDepartment department(Long id, Long parentId, String name, String category, Integer sort) {
        OrgDepartment department = new OrgDepartment();
        department.setId(id);
        department.setParentId(parentId);
        department.setName(name);
        department.setCategory(category);
        department.setSort(sort);
        department.setIsEnabled(true);
        return department;
    }
}
