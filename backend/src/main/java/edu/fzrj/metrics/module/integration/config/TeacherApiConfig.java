package edu.fzrj.metrics.module.integration.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 校内教师核验接口配置（凭证只从环境变量注入） */
@Getter
@Component
public class TeacherApiConfig {

    @Value("${app.teacher-api.base-url:}")
    private String baseUrl;

    @Value("${app.teacher-api.access-key:}")
    private String accessKey;

    @Value("${app.teacher-api.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${app.teacher-api.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${app.teacher-api.mock-enabled:false}")
    private boolean mockEnabled;

    /** 仅限 local 验收环境，格式：工号1:姓名1,工号2:姓名2 */
    @Value("${app.teacher-api.mock-identities:}")
    private String mockIdentities;

    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(accessKey);
    }
}
