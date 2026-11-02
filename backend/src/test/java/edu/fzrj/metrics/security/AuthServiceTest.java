package edu.fzrj.metrics.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.Sha256;
import edu.fzrj.metrics.module.audit.mapper.AuditLogMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.RefreshToken;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.RefreshTokenMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private OrgUnitMapper orgUnitMapper;
    @Mock
    private RefreshTokenMapper refreshTokenMapper;
    @Mock
    private JwtService jwtService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AuthService authService;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, RefreshToken.class);
        TableInfoHelper.initTableInfo(assistant, UserRole.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "maxFailCount", 5);
        ReflectionTestUtils.setField(authService, "lockMinutes", 15);
        ReflectionTestUtils.setField(authService, "refreshExpireDays", 7);
    }

    @Test
    void 登录业务异常不会回滚失败次数() throws Exception {
        Transactional transactional = AuthService.class.getMethod(
                "login", String.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertTrue(Arrays.asList(transactional.noRollbackFor()).contains(BizException.class));
    }

    @Test
    void 第五次登录失败写入锁定状态() {
        SysUser user = user();
        user.setLoginFailCount(4);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);
        AtomicReference<LambdaUpdateWrapper<SysUser>> updateRef = new AtomicReference<>();
        doAnswer(invocation -> {
            updateRef.set(invocation.getArgument(1));
            return 1;
        }).when(sysUserMapper).update(any(), any());

        BizException error = assertThrows(BizException.class,
                () -> authService.login("user", "bad", "WEB", "127.0.0.1"));

        assertEquals(ErrorCode.ACCOUNT_LOCKED.getCode(), error.getErrorCode().getCode());
        assertTrue(updateRef.get().getSqlSet().contains("login_fail_count"));
        assertTrue(updateRef.get().getParamNameValuePairs().containsValue(0));
        assertTrue(updateRef.get().getSqlSet().contains("locked_until"));
    }

    @Test
    void 同一旧刷新令牌并发旋转只能成功一次() throws Exception {
        String oldToken = "old-refresh-token";
        RefreshToken stored = new RefreshToken();
        stored.setId(100L);
        stored.setUserId(7L);
        stored.setTokenHash(Sha256.hex(oldToken));
        stored.setExpiresAt(LocalDateTime.now().plusDays(1));
        SysUser user = user();
        user.setId(7L);
        AtomicBoolean consumed = new AtomicBoolean();
        when(refreshTokenMapper.selectOne(any())).thenReturn(stored);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(jwtService.createAccessToken(any(), any(), anyList())).thenReturn("access-token");
        when(jwtService.getAccessExpireSeconds()).thenReturn(3600L);
        doAnswer(invocation -> {
            invocation.getArgument(0, RefreshToken.class).setId(200L);
            return 1;
        }).when(refreshTokenMapper).insert(any(RefreshToken.class));
        doAnswer(invocation -> {
            LambdaUpdateWrapper<RefreshToken> wrapper = invocation.getArgument(1);
            if (wrapper.getSqlSet().contains("replaced_by_id")) {
                return 1;
            }
            return consumed.compareAndSet(false, true) ? 1 : 0;
        }).when(refreshTokenMapper).update(any(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<String> call = () -> {
                ready.countDown();
                start.await();
                try {
                    authService.refresh(oldToken, "WEB", "127.0.0.1");
                    return "OK";
                } catch (BizException e) {
                    return String.valueOf(e.getErrorCode().getCode());
                }
            };
            Future<String> first = executor.submit(call);
            Future<String> second = executor.submit(call);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertEquals(1, results.stream().filter("OK"::equals).count());
            assertEquals(1, results.stream()
                    .filter(String.valueOf(ErrorCode.TOKEN_INVALID.getCode())::equals).count());
            verify(refreshTokenMapper, times(1)).insert(any(RefreshToken.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private static SysUser user() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("user");
        user.setName("用户");
        user.setPasswordHash("hash");
        user.setIsEnabled(true);
        user.setLoginFailCount(0);
        return user;
    }
}
