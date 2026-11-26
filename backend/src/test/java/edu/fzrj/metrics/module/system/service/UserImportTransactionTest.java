package edu.fzrj.metrics.module.system.service;

import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserImportTransactionTest {

    private MockedStatic<SecurityUtils> security;

    @AfterEach
    void tearDown() {
        if (security != null) {
            security.close();
        }
    }

    @Test
    void 第二行失败时事务回滚且保留预览批次() throws Exception {
        SysUserMapper sysUserMapper = mock(SysUserMapper.class);
        OrgUnitMapper orgUnitMapper = mock(OrgUnitMapper.class);
        UserAdminService userAdminService = mock(UserAdminService.class);
        UserImportPreviewStore previewStore = new UserImportPreviewStore();
        UserImportService target = new UserImportService(sysUserMapper, orgUnitMapper, userAdminService, previewStore);
        String importId = previewStore.save(99L, List.of(draft("user301"), draft("user302")), false);

        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
        security.when(SecurityUtils::getLoginUser).thenReturn(new LoginUser(
                99L, "creator", "创建人", null, List.of("ADMIN"), List.of()));
        when(userAdminService.create(any()))
                .thenReturn(userItem(3001L))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID, "第二行创建失败"));

        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        UserImportService transactionalService = (UserImportService) proxyFactory.getProxy();

        BizException error = assertThrows(BizException.class,
                () -> transactionalService.confirm(importId, "password"));

        assertNotNull(error);
        verify(userAdminService, times(2)).create(any());
        verify(connection).rollback();
        verify(connection, never()).commit();
        assertNotNull(previewStore.get(importId));
    }

    @Test
    void 管理员干事批次绕过预览后仍在确认时阻断() {
        SysUserMapper sysUserMapper = mock(SysUserMapper.class);
        OrgUnitMapper orgUnitMapper = mock(OrgUnitMapper.class);
        UserAdminService userAdminService = mock(UserAdminService.class);
        UserImportPreviewStore previewStore = new UserImportPreviewStore();
        UserImportService service = new UserImportService(
                sysUserMapper, orgUnitMapper, userAdminService, previewStore);
        String importId = previewStore.save(99L, List.of(draft("staff401", "STAFF")), false);
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
        security.when(SecurityUtils::getLoginUser).thenReturn(new LoginUser(
                99L, "creator", "创建人", null, List.of("ADMIN"), List.of()));

        BizException error = assertThrows(BizException.class,
                () -> service.confirm(importId, "password"));

        assertEquals(10001, error.getErrorCode().getCode());
        assertTrue(error.getMessage().contains("批量导入不支持 STAFF"));
        verify(userAdminService, never()).create(any());
        assertNotNull(previewStore.get(importId));
    }

    private static SystemDtos.UserImportDraftRow draft(String username) {
        return draft(username, "LEADER");
    }

    private static SystemDtos.UserImportDraftRow draft(String username, String role) {
        SystemDtos.UserImportDraftRow row = new SystemDtos.UserImportDraftRow();
        row.setName(username);
        row.setUsername(username);
        row.setRoles(List.of(role));
        row.setUnitIds(List.of());
        return row;
    }

    private static SystemDtos.UserItem userItem(Long id) {
        return new SystemDtos.UserItem(id, "user301", "用户一", null, null,
                null, null, List.of("LEADER"), true, null, 1);
    }
}
