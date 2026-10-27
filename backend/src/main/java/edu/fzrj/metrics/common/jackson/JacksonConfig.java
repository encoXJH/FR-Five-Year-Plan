package edu.fzrj.metrics.common.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * 时间序列化：LocalDateTime ↔ ISO 8601 带 +08:00（API 约定 3.1）
 * 服务端统一返回 +08:00；接收任意 ISO 时区并归一化到 +08:00。
 */
@Configuration
public class JacksonConfig {

    public static final ZoneOffset OFFSET = ZoneOffset.of("+08:00");

    /** 输出格式：2026-10-22T16:08:43+08:00（LocalDateTime 无时区字段，时区用字面量拼接） */
    public static final DateTimeFormatter OUTPUT_FORMATTER = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendLiteral("+08:00")
            .toFormatter();

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.format(OUTPUT_FORMATTER));
            }
        });
        module.addDeserializer(LocalDateTime.class, new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String text = p.getText().trim();
                if (text.contains("+") || text.endsWith("Z") || text.indexOf('-', 10) > 0) {
                    // 带时区：归一化到 +08:00
                    return OffsetDateTime.parse(text).withOffsetSameInstant(OFFSET).toLocalDateTime();
                }
                return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
        });
        return builder -> builder.modules(module);
    }
}
