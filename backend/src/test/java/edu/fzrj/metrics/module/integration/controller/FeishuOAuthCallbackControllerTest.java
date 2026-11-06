package edu.fzrj.metrics.module.integration.controller;

import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeishuOAuthCallbackControllerTest {

    private static final String BASE = "http://localhost:5173";

    private static FeishuOAuthCallbackController controller() {
        FeishuConfig config = new FeishuConfig();
        ReflectionTestUtils.setField(config, "appBaseUrl", BASE);
        return new FeishuOAuthCallbackController(config);
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(controller())
            .build();

    @Test
    void 接收授权码和状态并跳转现有免登页() throws Exception {
        mockMvc.perform(get("/api/callback")
                        .param("code", "abc123")
                        .param("state", "state456"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        BASE + "/login/feishu-callback?code=abc123&state=state456"));
    }

    @Test
    void callback根路径同样可用() throws Exception {
        mockMvc.perform(get("/callback")
                        .param("code", "abc123")
                        .param("state", "state456"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        BASE + "/login/feishu-callback?code=abc123&state=state456"));
    }

    @Test
    void 转发授权失败结果并编码参数() throws Exception {
        mockMvc.perform(get("/api/callback")
                        .param("error", "access_denied")
                        .param("error_description", "user denied"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        BASE + "/login/feishu-callback?error=access_denied&error_description=user%20denied"));
    }

    @Test
    void 预留入口无参数时也进入既有回调错误处理() throws Exception {
        mockMvc.perform(get("/api/callback"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, BASE + "/login/feishu-callback"));
    }
}
