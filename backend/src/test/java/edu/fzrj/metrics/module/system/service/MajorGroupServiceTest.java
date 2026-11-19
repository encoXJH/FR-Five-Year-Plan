package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.MajorGroup;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.MajorGroupMapper;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MajorGroupServiceTest {

    @Mock private MajorGroupMapper majorGroupMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private SysUserMapper sysUserMapper;
    @InjectMocks private MajorGroupService majorGroupService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MajorGroup.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @Test
    void 专业群展示保留已删除负责人姓名() {
        MajorGroup group = new MajorGroup();
        group.setId(1L);
        group.setName("专业群");
        group.setUnitId(11L);
        group.setOwnerId(7L);
        group.setIsEnabled(true);
        group.setVersion(1);
        OrgUnit unit = new OrgUnit();
        unit.setId(11L);
        unit.setName("学院");
        SysUser deletedOwner = new SysUser();
        deletedOwner.setId(7L);
        deletedOwner.setName("历史负责人");
        deletedOwner.setDeleted(7L);
        when(majorGroupMapper.selectList(any())).thenReturn(List.of(group));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(unit));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(deletedOwner));

        List<SystemDtos.MajorGroupView> result = majorGroupService.list(null);

        assertEquals("历史负责人", result.get(0).ownerName());
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sysUserMapper).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().toUpperCase().contains("DELETED"));
    }
}
