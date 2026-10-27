package edu.fzrj.metrics.common.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JacksonConfigTest {

    private ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    void 序列化带0800时区() {
        ObjectMapper om = mapper();
        String json = om.valueToTree(LocalDateTime.of(2026, 10, 22, 16, 8, 43)).asText();
        assertEquals("2026-10-22T16:08:43+08:00", json);
    }

    @Test
    void 反序列化任意时区归一化到0800() throws Exception {
        ObjectMapper om = mapper();
        LocalDateTime value = om.readValue("\"2026-10-22T10:08:43+02:00\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 10, 22, 16, 8, 43), value);
    }

    @Test
    void 反序列化Zulu时间() throws Exception {
        ObjectMapper om = mapper();
        LocalDateTime value = om.readValue("\"2026-10-22T08:08:43Z\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 10, 22, 16, 8, 43), value);
    }

    @Test
    void 反序列化无时区字符串原样解析() throws Exception {
        ObjectMapper om = mapper();
        LocalDateTime value = om.readValue("\"2026-10-22T16:08:43\"", LocalDateTime.class);
        assertEquals(LocalDateTime.of(2026, 10, 22, 16, 8, 43), value);
    }
}
