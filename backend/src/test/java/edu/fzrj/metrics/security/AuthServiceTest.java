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
import edu.fzrj.metrics.module.system.service.UserUnitService;
import edu.fzrj.metrics.module.training.mapper.TrainingSheetStaffMapper;
import edu.fzrj.metrics.security.dto.MeResponse;
import edu.fzrj.metrics.security.dto.TokenResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private UserUnitService userUnitService;
    @Mock
    private TrainingSheetStaffMapper trainingSheetStaffMapper;

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
        ReflectionTestUtils.setField(authService, "initialPassword", "fzrj@12345678");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 登录业务异常不会回滚失败次数() throws Exception {
        Transactional transactional = AuthService.class.getMethod(
                "login", String.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertTrue(Arrays.asList(transactional.noRollbackFor()).contains(BizException.class));
    }

    @Test
    void 目录隐藏账号仍可正常登录并返回主学院和全部关联学院() {
        SysUser user = user();
        user.setDirectoryVisible(false);
        user.setUnitId(103L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role("UNIT_OWNER")));
        when(userUnitService.unitIdsOf(7L)).thenReturn(List.of(103L, 104L));
        when(orgUnitMapper.selectBatchIds(any())).thenReturn(List.of(
                unit(103L, "C03", "数字影视传媒产业学院"),
                unit(104L, "C04", "数据产业学院")));
        when(jwtService.createAccessToken(any(), any(), anyList())).thenReturn("access-token");
        when(jwtService.getAccessExpireSeconds()).thenReturn(3600L);

        TokenResponse response = authService.login("user", "password", "WEB", "127.0.0.1");

        assertEquals(103L, response.getUser().unit().id());
        assertEquals(List.of(103L, 104L), response.getUser().unitIds());
        assertEquals(List.of("数字影视传媒产业学院", "数据产业学院"),
                response.getUser().units().stream().map(TokenResponse.UnitRef::name).toList());
    }

    @Test
    void 当前用户响应保留混合角色的多学院数据范围() {
        SysUser user = user();
        user.setUnitId(103L);
        when(sysUserMapper.selectById(7L)).thenReturn(user);
        when(orgUnitMapper.selectBatchIds(any())).thenReturn(List.of(
                unit(103L, "C03", "数字影视传媒产业学院"),
                unit(104L, "C04", "数据产业学院")));
        LoginUser loginUser = new LoginUser(7L, "user", "用户", 103L,
                List.of("UNIT_OWNER", "SCHOOL_OWNER"), List.of(), List.of(103L, 104L));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));

        MeResponse response = authService.me();

        assertEquals(List.of(103L, 104L), response.getUser().getUnitIds());
        assertEquals(2, response.getUser().getUnits().size());
        assertEquals("OWNER", response.getDataScope().getType());
        assertEquals(List.of(103L, 104L), response.getDataScope().getUnitIds());
        assertFalse(response.isPasswordChangeRequired());
    }

    @Test
    void 纯干事当前用户返回ASSIGNED数据范围() {
        SysUser user = user();
        when(sysUserMapper.selectById(7L)).thenReturn(user);
        LoginUser loginUser = new LoginUser(7L, "staff", "干事", 103L,
                List.of("STAFF"), List.of(), List.of(103L));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));

        MeResponse response = authService.me();

        assertEquals("ASSIGNED", response.getDataScope().getType());
        assertEquals(List.of(103L), response.getDataScope().getUnitIds());
    }

    @Test
    void 纯干事无有效培训分配时移除培训填写权限() {
        SysUser user = user();
        when(sysUserMapper.selectById(7L)).thenReturn(user);
        when(trainingSheetStaffMapper.selectValidSheetIds(7L)).thenReturn(List.of());
        LoginUser loginUser = new LoginUser(7L, "staff", "干事", 103L,
                List.of("STAFF"), List.of("training:fill", "submission:submit"), List.of(103L));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));

        MeResponse response = authService.me();

        assertFalse(response.getPermissions().contains("training:fill"));
        assertTrue(response.getPermissions().contains("submission:submit"));
    }

    @Test
    void 纯干事存在有效培训分配时保留培训填写权限() {
        SysUser user = user();
        when(sysUserMapper.selectById(7L)).thenReturn(user);
        when(trainingSheetStaffMapper.selectValidSheetIds(7L)).thenReturn(List.of(11L));
        LoginUser loginUser = new LoginUser(7L, "staff", "干事", 103L,
                List.of("STAFF"), List.of("training:fill"), List.of(103L));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));

        MeResponse response = authService.me();

        assertTrue(response.getPermissions().contains("training:fill"));
    }

    @Test
    void 多角色不过滤培训填写权限且不查询干事分配() {
        SysUser user = user();
        when(sysUserMapper.selectById(7L)).thenReturn(user);
        LoginUser loginUser = new LoginUser(7L, "staff", "干事", 103L,
                List.of("STAFF", "UNIT_OWNER"), List.of("training:fill"), List.of(103L));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));

        MeResponse response = authService.me();

        assertTrue(response.getPermissions().contains("training:fill"));
        verifyNoInteractions(trainingSheetStaffMapper);
    }

    @Test
    void 当前用户仍使用初始密码时返回强制改密标记() {
        SysUser user = user();
        when(sysUserMapper.selectById(7L)).thenReturn(user);
        when(passwordEncoder.matches("fzrj@12345678", "hash")).thenReturn(true);
        LoginUser loginUser = new LoginUser(7L, "user", "用户", null,
                List.of("UNIT_OWNER"), List.of(), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));

        MeResponse response = authService.me();

        assertTrue(response.isPasswordChangeRequired());
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

    @Test
    void 两次密码不一致时不修改账号() {
        BizException error = assertThrows(BizException.class,
                () -> authService.changePassword(7L, "NewPass@123", "Different@123"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getErrorCode().getCode());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verify(refreshTokenMapper, never()).update(any(), any());
    }

    @Test
    void 新密码仍为初始密码时拒绝修改() {
        BizException error = assertThrows(BizException.class,
                () -> authService.changePassword(7L, "fzrj@12345678", "fzrj@12345678"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getErrorCode().getCode());
        assertEquals("新密码不能仍为初始密码", error.getMessage());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verify(refreshTokenMapper, never()).update(any(), any());
    }

    @Test
    void 修改密码成功更新哈希并吊销刷新令牌() {
        SysUser user = user();
        user.setVersion(2);
        user.setLoginFailCount(3);
        user.setLockedUntil(LocalDateTime.now().plusDays(1));
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.encode("NewPass@123")).thenReturn("new-bcrypt-hash");
        when(sysUserMapper.updateById(user)).thenReturn(1);

        authService.changePassword(7L, "NewPass@123", "NewPass@123");

        assertEquals("new-bcrypt-hash", user.getPasswordHash());
        assertEquals(0, user.getLoginFailCount());
        assertNull(user.getLockedUntil());
        assertEquals(7L, user.getUpdatedBy());
        verify(sysUserMapper).updateById(user);
        verify(refreshTokenMapper).update(any(), any());
    }

    private static UserRole role(String role) {
        UserRole userRole = new UserRole();
        userRole.setUserId(7L);
        userRole.setRole(role);
        return userRole;
    }

    private static OrgUnit unit(Long id, String code, String name) {
        OrgUnit unit = new OrgUnit();
        unit.setId(id);
        unit.setCode(code);
        unit.setName(name);
        return unit;
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
