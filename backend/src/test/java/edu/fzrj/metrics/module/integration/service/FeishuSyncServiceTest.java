package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.module.audit.mapper.AuditLogMapper;
import edu.fzrj.metrics.module.integration.client.FeishuClient;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.dto.FeishuDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuSyncServiceTest {

    @Mock private FeishuConfig config;
    @Mock private FeishuClient feishuClient;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private FeishuSyncService service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @Test
    void 组织同步更新资料但不绕过首次核验自动绑定openId() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("10001");
        user.setName("旧姓名");
        user.setPhone("13800000000");
        user.setIsEnabled(true);
        when(config.isConfigured()).thenReturn(true);
        when(feishuClient.listAllDepartments())
                .thenReturn(List.of(new FeishuClient.ContactDepartment("d1", "党政办公室")));
        when(feishuClient.listDepartmentUsers("d1"))
                .thenReturn(List.of(new FeishuClient.ContactUser("ou_new", "un_new", "张三", "13800000000", "d1")));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));

        FeishuDtos.SyncOrgView result = service.sync();

        assertEquals(0, result.openIdBoundCount());
        assertEquals(1, result.nameUpdatedCount());
        assertEquals("张三", user.getName());
        assertNull(user.getFeishuOpenId());
        assertNull(user.getFeishuUnionId());
    }
}
