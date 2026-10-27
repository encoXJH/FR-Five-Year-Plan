package edu.fzrj.metrics.security.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前用户信息（GET /auth/me）
 */
@Data
@AllArgsConstructor
public class MeResponse {

    private UserInfo user;
    private java.util.List<String> roles;
    private java.util.List<String> permissions;
    private DataScope dataScope;

    @Data
    @AllArgsConstructor
    public static class UserInfo {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        private String username;
        private String name;
        /** 脱敏手机号 */
        private String phone;
        private String email;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;
        private String unitName;
    }

    @Data
    @AllArgsConstructor
    public static class DataScope {
        /** ALL 全校 / OWNER 本人负责 / UNIT 本单元 */
        private String type;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;
    }
}
