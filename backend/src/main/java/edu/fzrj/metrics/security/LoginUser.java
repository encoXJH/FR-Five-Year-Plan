package edu.fzrj.metrics.security;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 登录用户主体（存 SecurityContext）
 */
@Data
@AllArgsConstructor
public class LoginUser implements Serializable {

    private Long userId;
    private String username;
    private String name;
    private Long unitId;
    private List<String> roles;
    private List<String> permissions;
}
