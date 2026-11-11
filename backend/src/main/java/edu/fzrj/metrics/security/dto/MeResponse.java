package edu.fzrj.metrics.security.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

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
        private List<TokenResponse.UnitRef> units;
        @JsonSerialize(contentUsing = ToStringSerializer.class)
        private List<Long> unitIds;

        public UserInfo(Long id, String username, String name, String phone, String email,
                        Long unitId, String unitName) {
            this(id, username, name, phone, email, unitId, unitName,
                    unitId == null ? List.of() : List.of(new TokenResponse.UnitRef(unitId, null, unitName)),
                    unitId == null ? List.of() : List.of(unitId));
        }
    }

    @Data
    @AllArgsConstructor
    public static class DataScope {
        /** ALL 全校 / OWNER 本人负责 / UNIT 本单元 */
        private String type;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;
        @JsonSerialize(contentUsing = ToStringSerializer.class)
        private List<Long> unitIds;

        public DataScope(String type, Long unitId) {
            this(type, unitId, unitId == null ? List.of() : List.of(unitId));
        }
    }
}
