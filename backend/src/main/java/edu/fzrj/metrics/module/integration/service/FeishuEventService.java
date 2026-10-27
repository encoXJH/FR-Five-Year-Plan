package edu.fzrj.metrics.module.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.entity.FeishuEvent;
import edu.fzrj.metrics.module.integration.mapper.FeishuEventMapper;
import edu.fzrj.metrics.module.integration.service.support.FeishuSignature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 飞书事件回调（API 约定第 11 章 / 15.11）：
 * 验签（Encrypt Key 方案）→ 解密（加密报文）→ Verification Token 校验 → url_verification 应答 →
 * 按 event_id 去重落库 → 异步处理。入口必须 3 秒内返回，业务处理全部异步。
 */
@Slf4j
@Service
public class FeishuEventService {

    private final FeishuConfig config;
    private final FeishuEventMapper feishuEventMapper;
    private final FeishuEventExecutor eventExecutor;
    private final ObjectMapper objectMapper;

    public FeishuEventService(FeishuConfig config, FeishuEventMapper feishuEventMapper,
                              FeishuEventExecutor eventExecutor, ObjectMapper objectMapper) {
        this.config = config;
        this.feishuEventMapper = feishuEventMapper;
        this.eventExecutor = eventExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理事件回调入口。
     *
     * @return url_verification 时需要原样应答的 JSON（{"challenge": "..."}），其余情况返回 null（调用方回空对象）
     */
    public JsonNode handle(String timestamp, String nonce, String signature, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(ErrorCode.BODY_INVALID);
        }
        if (!config.hasEncryptKey() && !config.hasVerificationToken()) {
            // 没有任何验签凭据：无法确认来源，拒绝（fail closed）
            log.warn("飞书事件回调被拒绝：平台未配置 FEISHU_ENCRYPT_KEY / FEISHU_VERIFICATION_TOKEN");
            throw new BizException(ErrorCode.FEISHU_SIGNATURE_INVALID, "平台未配置飞书事件验签参数");
        }

        String body = rawBody;
        if (config.hasEncryptKey()) {
            if (!FeishuSignature.verify(timestamp, nonce, config.getEncryptKey(), rawBody, signature)) {
                throw new BizException(ErrorCode.FEISHU_SIGNATURE_INVALID);
            }
            if (FeishuSignature.isEncryptedBody(rawBody)) {
                String encrypt = parse(rawBody).path("encrypt").asText(null);
                if (!StringUtils.hasText(encrypt)) {
                    throw new BizException(ErrorCode.BODY_INVALID);
                }
                body = FeishuSignature.decrypt(config.getEncryptKey(), encrypt);
            }
        }

        JsonNode json = parse(body);
        // Verification Token 校验（v1 在顶层 token，v2 在 header.token）
        if (config.hasVerificationToken()) {
            String token = tokenOf(json);
            if (!config.getVerificationToken().equals(token)) {
                throw new BizException(ErrorCode.FEISHU_SIGNATURE_INVALID);
            }
        }

        // url_verification：原样回 challenge
        if ("url_verification".equals(json.path("type").asText(null))) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("challenge", json.path("challenge").asText(""));
            return response;
        }

        String eventId = eventIdOf(json);
        if (!StringUtils.hasText(eventId)) {
            // 无事件 id 无法去重，按约定仍快速成功返回（不落库）
            log.warn("飞书事件缺少 event_id，已忽略：{}", abbreviate(body));
            return null;
        }
        FeishuEvent event = tryRecord(eventId, eventTypeOf(json), body);
        if (event != null) {
            eventExecutor.processAsync(event.getId());
        }
        return null;
    }

    /** 事件去重：event_id 唯一索引防并发；返回 null 表示重复事件 */
    public FeishuEvent tryRecord(String eventId, String eventType, String payload) {
        FeishuEvent event = new FeishuEvent();
        event.setEventId(eventId);
        event.setEventType(eventType == null ? "unknown" : eventType);
        event.setPayload(payload);
        event.setStatus("RECEIVED");
        try {
            feishuEventMapper.insert(event);
            return event;
        } catch (DuplicateKeyException e) {
            log.info("飞书事件重复，已忽略 eventId={}", eventId);
            return null;
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BODY_INVALID);
        }
    }

    private static String tokenOf(JsonNode json) {
        String token = json.path("token").asText(null);
        if (token == null) {
            token = json.path("header").path("token").asText(null);
        }
        return token;
    }

    /** v2 报文取 header.event_id；v1 报文取 uuid */
    private static String eventIdOf(JsonNode json) {
        String eventId = json.path("header").path("event_id").asText(null);
        if (eventId == null) {
            eventId = json.path("uuid").asText(null);
        }
        return eventId;
    }

    private static String eventTypeOf(JsonNode json) {
        String eventType = json.path("header").path("event_type").asText(null);
        if (eventType == null) {
            eventType = json.path("type").asText(null);
        }
        return eventType;
    }

    private static String abbreviate(String body) {
        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
