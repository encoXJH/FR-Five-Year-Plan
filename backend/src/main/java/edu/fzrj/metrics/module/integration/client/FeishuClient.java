package edu.fzrj.metrics.module.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 飞书开放平台 API 封装（RestTemplate，带连接 / 读取超时；所有方法失败返回 null 或抛
 * {@link FeishuApiException}，由调用方兜底，绝不影响主业务）。
 *
 * <p>官方文档：
 * <ul>
 *   <li>获取 tenant_access_token（自建应用）：
 *   https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal</li>
 *   <li>OAuth 换取 user_access_token（authen/v2 oauth/token）：
 *   https://open.feishu.cn/document/server-docs/authentication-management/login-state-management/obtain-oauth-token</li>
 *   <li>获取登录用户信息（authen/v1 user_info）：
 *   https://open.feishu.cn/document/server-docs/authentication-management/login-state-management/obtain-user-info</li>
 *   <li>发送消息（im/v1 messages）：
 *   https://open.feishu.cn/document/server-docs/im-v1/message/create</li>
 *   <li>通讯录-获取子部门列表 / 部门用户：
 *   https://open.feishu.cn/document/server-docs/contact-v3/department/children
 *   https://open.feishu.cn/document/server-docs/contact-v3/user/find_by_department</li>
 * </ul>
 */
@Slf4j
@Component
public class FeishuClient {

    /** 飞书接口调用失败（网络异常或业务 code 非 0） */
    public static class FeishuApiException extends RuntimeException {
        public FeishuApiException(String message) {
            super(message);
        }
    }

    /** 登录用户信息（authen/v1 user_info 的子集） */
    public record FeishuUserInfo(String openId, String unionId, String name, String mobile) {
    }

    /** 通讯录人员（find_by_department 的子集） */
    public record ContactUser(String openId, String unionId, String name, String mobile, String departmentId) {
    }

    /** 通讯录部门 */
    public record ContactDepartment(String departmentId, String name) {
    }

    private final FeishuConfig config;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /** tenant_access_token 缓存（提前 60 秒过期） */
    private String cachedTenantToken;
    private Instant tenantTokenExpireAt = Instant.EPOCH;

    public FeishuClient(FeishuConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    /** 获取 tenant_access_token（带内存缓存） */
    public synchronized String tenantAccessToken() {
        if (cachedTenantToken != null && Instant.now().isBefore(tenantTokenExpireAt)) {
            return cachedTenantToken;
        }
        JsonNode body = post("/open-apis/auth/v3/tenant_access_token/internal",
                Map.of("app_id", config.getAppId(), "app_secret", config.getAppSecret()), null);
        String token = text(body, "tenant_access_token");
        long expire = body != null && body.hasNonNull("expire") ? body.get("expire").asLong(7200) : 7200;
        if (token == null) {
            throw new FeishuApiException("tenant_access_token 响应缺少 token");
        }
        cachedTenantToken = token;
        tenantTokenExpireAt = Instant.now().plusSeconds(Math.max(60, expire - 60));
        return token;
    }

    /** 浏览器 OAuth 授权跳转地址（accounts.feishu.cn，doc: server-docs/authentication-management/login-state-management） */
    public String buildAuthorizeUrl(String state) {
        StringBuilder url = new StringBuilder(config.getAccountsUrl())
                .append("/open-apis/authen/v1/authorize?client_id=")
                .append(URLEncoder.encode(config.getAppId(), StandardCharsets.UTF_8))
                .append("&redirect_uri=")
                .append(URLEncoder.encode(config.getRedirectUri(), StandardCharsets.UTF_8));
        if (state != null && !state.isBlank()) {
            url.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    /** 用 JSAPI / OAuth 的 code 换 user_access_token；失败抛 FeishuApiException */
    public String exchangeUserAccessToken(String code) {
        JsonNode body = post("/open-apis/authen/v2/oauth/token",
                Map.of("grant_type", "authorization_code",
                        "client_id", config.getAppId(),
                        "client_secret", config.getAppSecret(),
                        "code", code,
                        "redirect_uri", config.getRedirectUri() == null ? "" : config.getRedirectUri()),
                null);
        String token = body == null ? null : text(body, "access_token");
        if (token == null) {
            throw new FeishuApiException("oauth/token 响应缺少 access_token");
        }
        return token;
    }

    /** 用 user_access_token 取登录用户信息（open_id / union_id / name / mobile，mobile 依赖已授权 scope） */
    public FeishuUserInfo getLoginUserInfo(String userAccessToken) {
        JsonNode body = get("/open-apis/authen/v1/user_info", "Bearer " + userAccessToken);
        JsonNode data = body == null ? null : body.get("data");
        if (data == null) {
            throw new FeishuApiException("user_info 响应缺少 data");
        }
        return new FeishuUserInfo(text(data, "open_id"), text(data, "union_id"),
                text(data, "name"), text(data, "mobile"));
    }

    /** 发送互动卡片消息（receive_id_type=open_id）；content 为卡片 JSON 字符串；成功返回 true */
    public boolean sendCardMessage(String openId, String cardJson) {
        try {
            JsonNode body = post("/open-apis/im/v1/messages?receive_id_type=open_id",
                    Map.of("receive_id", openId, "msg_type", "interactive", "content", cardJson),
                    "Bearer " + tenantAccessToken());
            return body != null;
        } catch (Exception e) {
            log.warn("飞书消息发送失败 openId={}：{}", openId, e.getMessage());
            return false;
        }
    }

    /** 拉取全部子部门（fetch_child=true 一次拿全量后代，分页遍历） */
    public List<ContactDepartment> listAllDepartments() {
        List<ContactDepartment> result = new ArrayList<>();
        String pageToken = null;
        do {
            String path = "/open-apis/contact/v3/departments/0/children?department_id_type=department_id"
                    + "&fetch_child=true&page_size=50" + (pageToken == null ? "" : "&page_token=" + pageToken);
            JsonNode data = getData(path);
            if (data == null || !data.hasNonNull("items")) {
                break;
            }
            for (JsonNode item : data.get("items")) {
                result.add(new ContactDepartment(text(item, "department_id"), text(item, "name")));
            }
            pageToken = data.hasNonNull("has_more") && data.get("has_more").asBoolean()
                    ? text(data, "page_token") : null;
        } while (pageToken != null);
        return result;
    }

    /** 拉取某部门下的成员（分页遍历） */
    public List<ContactUser> listDepartmentUsers(String departmentId) {
        List<ContactUser> result = new ArrayList<>();
        String pageToken = null;
        do {
            String path = "/open-apis/contact/v3/users/find_by_department?department_id=" + departmentId
                    + "&department_id_type=department_id&page_size=50"
                    + (pageToken == null ? "" : "&page_token=" + pageToken);
            JsonNode data = getData(path);
            if (data == null || !data.hasNonNull("items")) {
                break;
            }
            for (JsonNode item : data.get("items")) {
                result.add(new ContactUser(text(item, "open_id"), text(item, "union_id"),
                        text(item, "name"), text(item, "mobile"), departmentId));
            }
            pageToken = data.hasNonNull("has_more") && data.get("has_more").asBoolean()
                    ? text(data, "page_token") : null;
        } while (pageToken != null);
        return result;
    }

    private JsonNode getData(String path) {
        JsonNode body = get(path, "Bearer " + tenantAccessToken());
        return body == null ? null : body.get("data");
    }

    /** POST 并校验飞书业务 code==0；网络异常与业务错误统一抛 FeishuApiException */
    private JsonNode post(String path, Map<String, ?> body, String authorization) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (authorization != null) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
            ResponseEntity<String> response = restTemplate.exchange(config.getBaseUrl() + path,
                    HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
            return check(response.getBody(), path);
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("飞书接口调用失败 " + path + "：" + e.getMessage());
        }
    }

    private JsonNode get(String path, String authorization) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (authorization != null) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
            ResponseEntity<String> response = restTemplate.exchange(config.getBaseUrl() + path,
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return check(response.getBody(), path);
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("飞书接口调用失败 " + path + "：" + e.getMessage());
        }
    }

    private JsonNode check(String raw, String path) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new FeishuApiException("飞书接口空响应 " + path);
        }
        JsonNode body = objectMapper.readTree(raw);
        int code = body.hasNonNull("code") ? body.get("code").asInt(-1) : -1;
        if (code != 0) {
            String msg = body.hasNonNull("msg") ? body.get("msg").asText() : "unknown";
            throw new FeishuApiException("飞书接口返回错误 " + path + "：code=" + code + ", msg=" + msg);
        }
        return body;
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
