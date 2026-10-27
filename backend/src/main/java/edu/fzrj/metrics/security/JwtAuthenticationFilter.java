package edu.fzrj.metrics.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer Token 解析：合法则把 LoginUser 放进 SecurityContext；
 * 失败不拦截（交给安全入口按 20001/20002/20003 处理），错误码放入 request attribute。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 认证失败时写入的错误码 attribute，供入口读取 */
    public static final String AUTH_ERROR_ATTR = "AUTH_ERROR_CODE";

    private final JwtService jwtService;
    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;

    public JwtAuthenticationFilter(JwtService jwtService, SysUserMapper sysUserMapper, UserRoleMapper userRoleMapper) {
        this.jwtService = jwtService;
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                Long userId = Long.valueOf(claims.getSubject());
                SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getId, userId)
                        .eq(SysUser::getDeleted, 0L));
                if (user == null || Boolean.FALSE.equals(user.getIsEnabled())) {
                    request.setAttribute(AUTH_ERROR_ATTR, 20003);
                } else {
                    List<String> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                                    .eq(UserRole::getUserId, userId))
                            .stream().map(UserRole::getRole).toList();
                    List<String> permissions = RolePermissions.permissionsOf(roles);
                    LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getName(),
                            user.getUnitId(), roles, permissions);
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            loginUser, null,
                            permissions.stream().map(SimpleGrantedAuthority::new).toList());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute(AUTH_ERROR_ATTR, 20002);
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(AUTH_ERROR_ATTR, 20003);
            }
        }
        chain.doFilter(request, response);
    }
}
