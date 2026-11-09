package edu.fzrj.metrics.module.integration.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.fzrj.metrics.module.integration.config.TeacherApiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeacherApiClientTest {

    private HttpServer server;
    private TeacherApiClient client;
    private TeacherApiConfig config;
    private MockEnvironment environment;
    private final AtomicReference<String> requestQuery = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> accessKey = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private volatile String responseBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dehoop-admin/api/fr/teacher/JSJBXX_SM", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            accessKey.set(exchange.getRequestHeaders().getFirst("AccessKey"));
            requestMethod.set(exchange.getRequestMethod());
            requestQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            // 校内接口实际响应头不带 charset，响应体为 UTF-8
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        config = new TeacherApiConfig();
        ReflectionTestUtils.setField(config, "baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/dehoop-admin");
        ReflectionTestUtils.setField(config, "accessKey", "test-access-key");
        ReflectionTestUtils.setField(config, "connectTimeoutMs", 1000);
        ReflectionTestUtils.setField(config, "readTimeoutMs", 1000);
        environment = new MockEnvironment();
        client = new TeacherApiClient(config, new ObjectMapper(), environment);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 按契约请求并解析唯一教师记录() {
        responseBody = success("[{\"account_\":\"10001\",\"name_\":\"张三\","
                + "\"post_status_\":\"在职在岗\",\"org_name_\":\"党政办公室\"}]");

        TeacherApiClient.TeacherIdentity identity = client.verify("张三", "10001");

        assertEquals("10001", identity.account());
        assertEquals("张三", identity.name());
        assertEquals("在职在岗", identity.postStatus());
        assertEquals("test-access-key", accessKey.get());
        assertEquals("GET", requestMethod.get());
        assertEquals("/dehoop-admin/api/fr/teacher/JSJBXX_SM", path.get());
        String query = URLDecoder.decode(requestQuery.get(), StandardCharsets.UTF_8);
        assertTrue(query.contains("page=1"));
        assertTrue(query.contains("pageSize=1"));
        assertTrue(query.contains("account_=10001"));
        assertTrue(query.contains("name_=张三"));
    }

    @Test
    void 空记录表示核验未通过() {
        responseBody = success("[]");
        assertNull(client.verify("张三", "10001"));
    }

    @Test
    void 返回记录姓名或工号不一致时不通过() {
        responseBody = success("[{\"account_\":\"10002\",\"name_\":\"张三\"}]");
        assertNull(client.verify("张三", "10001"));
    }

    @Test
    void 多条记录或异常信封按接口异常处理() {
        responseBody = success("[{\"account_\":\"10001\",\"name_\":\"张三\"},"
                + "{\"account_\":\"10001\",\"name_\":\"张三\"}]");
        assertThrows(TeacherApiClient.TeacherApiException.class, () -> client.verify("张三", "10001"));

        responseBody = "{\"code\":\"FAILED\",\"bizCode\":\"500\",\"data\":[]}";
        assertThrows(TeacherApiClient.TeacherApiException.class, () -> client.verify("张三", "10001"));
    }

    @Test
    void local环境指定账号优先使用临时模拟且不请求真实接口() {
        ReflectionTestUtils.setField(config, "mockEnabled", true);
        ReflectionTestUtils.setField(config, "mockIdentities", "10001:张三,10002:李四");
        environment.setActiveProfiles("local");

        TeacherApiClient.TeacherIdentity first = client.verify("张三", "10001");
        TeacherApiClient.TeacherIdentity second = client.verify("李四", "10002");

        assertEquals("10001", first.account());
        assertEquals("张三", first.name());
        assertEquals("10002", second.account());
        assertEquals("李四", second.name());
        assertNull(path.get());
    }

    @Test
    void 非local环境或身份不匹配时不使用临时模拟() {
        responseBody = success("[]");
        ReflectionTestUtils.setField(config, "mockEnabled", true);
        ReflectionTestUtils.setField(config, "mockIdentities", "10001:张三,10002:李四");

        assertNull(client.verify("张三", "10001"));

        environment.setActiveProfiles("local");
        assertNull(client.verify("李四", "10001"));
        assertNull(client.verify("张三", "10002"));
    }

    private static String success(String data) {
        return "{\"code\":\"SUCCESS\",\"bizCode\":\"200\",\"message\":\"查询成功\",\"data\":" + data + "}";
    }
}
