package edu.fzrj.metrics.security;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 登录用户主体（存 SecurityContext）
 */
@Data
public class LoginUser implements Serializable {

    private Long userId;
    private String username;
    private String name;
    private Long unitId;
    private List<String> roles;
    private List<String> permissions;
    @Setter(AccessLevel.NONE)
    private List<Long> unitIds;

    public LoginUser(Long userId, String username, String name, Long unitId,
                     List<String> roles, List<String> permissions) {
        this(userId, username, name, unitId, roles, permissions,
                unitId == null ? List.of() : List.of(unitId));
    }

    public LoginUser(Long userId, String username, String name, Long unitId,
                     List<String> roles, List<String> permissions, Collection<Long> unitIds) {
        this.userId = userId;
        this.username = username;
        this.name = name;
        this.unitId = unitId;
        this.roles = roles;
        this.permissions = permissions;
        this.unitIds = unitIds == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(unitIds));
    }
}
