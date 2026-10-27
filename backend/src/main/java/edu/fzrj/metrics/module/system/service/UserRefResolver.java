package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户名引用解析：批量查 sys_user 组装 UserRef（避免 N+1）
 */
@Component
public class UserRefResolver {

    private final SysUserMapper sysUserMapper;

    public UserRefResolver(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    public Map<Long, SysUser> mapOf(Collection<Long> ids) {
        List<Long> distinct = ids == null ? List.of() : ids.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, distinct)
                        .eq(SysUser::getDeleted, 0L))
                .stream().collect(Collectors.toMap(SysUser::getId, Function.identity()));
    }

    public UserRef refOf(Long id, Map<Long, SysUser> userMap) {
        if (id == null) {
            return null;
        }
        SysUser user = userMap.get(id);
        return new UserRef(id, user == null ? null : user.getName());
    }

    /** 按登录名查用户（导入用），不存在返回 null */
    public SysUser findByUsername(String username) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleted, 0L));
    }
}
