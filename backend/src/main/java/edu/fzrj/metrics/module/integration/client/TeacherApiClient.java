package edu.fzrj.metrics.module.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.module.integration.config.TeacherApiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 校内教师身份核验：姓名和工号必须同时精确匹配，接口凭证不离开服务端。 */
@Slf4j
@Component
public class TeacherApiClient {

    public static class TeacherApiException extends RuntimeException {
        public TeacherApiException(String message) {
            super(message);
        }

        public TeacherApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record TeacherIdentity(String account, String name, String postStatus, String orgName) {
    }

    private final TeacherApiConfig config;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final RestTemplate restTemplate;

    public TeacherApiClient(TeacherApiConfig config, ObjectMapper objectMapper, Environment environment) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.environment = environment;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
        // 校内接口返回 application/json 且不带 charset，按 UTF-8 解码避免中文姓名乱码
        this.restTemplate.getMessageConverters().add(0,
                new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    /** 返回 null 表示教师信息未通过核验；接口不可用或响应异常抛 TeacherApiException。 */
    public TeacherIdentity verify(String name, String employeeId) {
        if (!config.isConfigured()) {
            throw new TeacherApiException("校内教师核验接口未配置");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("AccessKey", config.getAccessKey());
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl() + "/api/fr/teacher/JSJBXX_SM")
                .queryParam("page", 1)
                .queryParam("pageSize", 1)
                .queryParam("account_", employeeId)
                .queryParam("name_", name)
                .build()
                .encode()
                .toUri();
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);
            TeacherIdentity identity = parseResponse(response.getBody(), name, employeeId);
            return identity == null ? localMockIdentity(name, employeeId) : identity;
        } catch (TeacherApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TeacherApiException("校内教师核验接口调用失败", e);
        }
    }

    private TeacherIdentity parseResponse(String raw, String expectedName, String expectedEmployeeId)
            throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new TeacherApiException("校内教师核验接口返回空响应");
        }
        JsonNode body = objectMapper.readTree(raw);
        if (body == null || !"SUCCESS".equals(text(body, "code")) || !"200".equals(text(body, "bizCode"))) {
            throw new TeacherApiException("校内教师核验接口返回异常状态");
        }
        JsonNode data = body.get("data");
        if (data == null || !data.isArray() || data.size() > 1) {
            throw new TeacherApiException("校内教师核验接口返回异常数据");
        }
        if (data.isEmpty()) {
            return null;
        }
        JsonNode item = data.get(0);
        String account = text(item, "account_");
        String name = text(item, "name_");
        if (!expectedEmployeeId.equals(account) || !expectedName.equals(name)) {
            return null;
        }
        return new TeacherIdentity(account, name, text(item, "post_status_"), text(item, "org_name_"));
    }

    private TeacherIdentity localMockIdentity(String name, String employeeId) {
        if (!config.isMockEnabled() || !List.of(environment.getActiveProfiles()).contains("local")) {
            return null;
        }
        if (!name.equals(config.getMockName()) || !employeeId.equals(config.getMockEmployeeId())) {
            return null;
        }
        log.warn("本机临时教师核验模拟已命中，仅限 local Profile");
        return new TeacherIdentity(employeeId, name, null, null);
    }

    private String baseUrl() {
        String value = config.getBaseUrl().trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
