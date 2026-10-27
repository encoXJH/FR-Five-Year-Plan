package edu.fzrj.metrics.common.datascope;

import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 数据范围服务单测：通过 SecurityContext 注入登录主体，验证 20005 判定。
 */
class DataScopeServiceTest {

    private static final Long UNIT_C01 = 301L;
    private static final Long UNIT_C02 = 302L;

    private final DataScopeService dataScopeService = new DataScopeService();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void canAccessUnit_学院负责人跨单元抛20005() {
        login(new LoginUser(201L, "c01owner", "张三", UNIT_C01, List.of("UNIT_OWNER"), List.of()));
        // c01 用户访问 c02 单元数据 → 越权 20005
        BizException e = assertThrows(BizException.class, () -> dataScopeService.canAccessUnit(UNIT_C02));
        assertEquals(20005, e.getErrorCode().getCode());
    }

    @Test
    void canAccessUnit_学院负责人本单元通过() {
        login(new LoginUser(201L, "c01owner", "张三", UNIT_C01, List.of("UNIT_OWNER"), List.of()));
        assertDoesNotThrow(() -> dataScopeService.canAccessUnit(UNIT_C01));
    }

    @Test
    void canAccessUnit_管理员与校领导任意单元通过() {
        login(new LoginUser(100L, "admin", "管理员", null, List.of("ADMIN"), List.of()));
        assertDoesNotThrow(() -> dataScopeService.canAccessUnit(UNIT_C02));
        login(new LoginUser(101L, "leader", "校领导", null, List.of("LEADER"), List.of()));
        assertDoesNotThrow(() -> dataScopeService.canAccessUnit(UNIT_C02));
    }

    @Test
    void canAccessIndicatorOwner_校级负责人仅本人负责通过() {
        login(new LoginUser(201L, "owner", "李四", null, List.of("SCHOOL_OWNER"), List.of()));
        assertDoesNotThrow(() -> dataScopeService.canAccessIndicatorOwner(201L));
        BizException e = assertThrows(BizException.class, () -> dataScopeService.canAccessIndicatorOwner(999L));
        assertEquals(20005, e.getErrorCode().getCode());
    }

    @Test
    void requireAllData_非全校范围抛20005() {
        login(new LoginUser(201L, "c01owner", "张三", UNIT_C01, List.of("UNIT_OWNER"), List.of()));
        BizException e = assertThrows(BizException.class, dataScopeService::requireAllData);
        assertEquals(20005, e.getErrorCode().getCode());
    }

    private void login(LoginUser loginUser) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }
}
