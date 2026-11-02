package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.SysSetting;
import edu.fzrj.metrics.module.system.mapper.SysSettingMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingServiceTest {

    @Mock private SysSettingMapper sysSettingMapper;
    @InjectMocks private SettingService settingService;

    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysSetting.class);
    }

    @AfterEach
    void tearDown() {
        if (security != null) security.close();
    }

    @Test
    void 批量设置中任一更新发生版本冲突时抛出10005() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
        SysSetting first = setting(1L, "site.name", "旧名称");
        SysSetting second = setting(2L, "site.notice", "旧公告");
        when(sysSettingMapper.selectList(any())).thenReturn(List.of(first, second));
        when(sysSettingMapper.updateById(any(SysSetting.class))).thenReturn(1, 0);
        SystemDtos.SettingPatchRequest request = new SystemDtos.SettingPatchRequest();
        request.setItems(List.of(item("site.name", "新名称"), item("site.notice", "新公告")));

        BizException error = assertThrows(BizException.class, () -> settingService.patch(request));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(sysSettingMapper, times(2)).updateById(any(SysSetting.class));
        verify(sysSettingMapper, times(1)).selectList(any());
    }

    private SysSetting setting(Long id, String key, String value) {
        SysSetting setting = new SysSetting();
        setting.setId(id);
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setValueType("STRING");
        setting.setVersion(3);
        setting.setDeleted(0L);
        return setting;
    }

    private SystemDtos.SettingPatchItem item(String key, String value) {
        SystemDtos.SettingPatchItem item = new SystemDtos.SettingPatchItem();
        item.setKey(key);
        item.setValue(value);
        item.setVersion(3);
        return item;
    }
}
