package edu.fzrj.metrics.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.web.RequestIdFilter;
import edu.fzrj.metrics.module.audit.mapper.AuditLogMapper;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 审计切面：@AuditLog 方法执行成功后写 audit_log（只追加，库层触发器禁改删）。
 * 审计写入失败不影响主业务；带快照的操作在同一事务内完成 before、业务写入和 after。
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private static final Set<String> USER_REFERENCE_FIELDS = Set.of(
            "owner", "owners", "applicant", "expectedApprover", "decidedBy", "submittedBy", "reviewer");
    private static final Set<String> USER_ID_FIELDS = Set.of("ownerId", "applicantId");

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    public AuditLogAspect(AuditLogMapper auditLogMapper, ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Around("@annotation(auditLogAnn)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLogAnn) throws Throwable {
        if (auditLogAnn.before().isBlank() && auditLogAnn.after().isBlank()) {
            return doAround(joinPoint, auditLogAnn);
        }
        try {
            return transactionTemplate.execute(status -> {
                try {
                    return doAround(joinPoint, auditLogAnn);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new AuditedInvocationException(e);
                }
            });
        } catch (AuditedInvocationException e) {
            throw e.getCause();
        }
    }

    private Object doAround(ProceedingJoinPoint joinPoint, AuditLog auditLogAnn) throws Throwable {
        EvaluationContext context = spelContext(joinPoint);
        Object beforeValue = null;
        boolean deferredBefore = auditLogAnn.before().contains("#result");
        try {
            if (!auditLogAnn.before().isBlank() && !deferredBefore) {
                beforeValue = parser.parseExpression(auditLogAnn.before()).getValue(context);
            }
        } catch (Exception e) {
            log.error("审计变更前快照读取失败", e);
        }
        Object result = joinPoint.proceed();
        if (deferredBefore) {
            ((StandardEvaluationContext) context).setVariable("result", result);
            try {
                beforeValue = parser.parseExpression(auditLogAnn.before()).getValue(context);
            } catch (Exception e) {
                log.error("审计返回值中的变更前快照读取失败", e);
            }
        }
        try {
            writeLog(joinPoint, auditLogAnn, result, context, beforeValue);
        } catch (Exception e) {
            log.error("审计日志写入失败", e);
        }
        return result;
    }

    private void writeLog(ProceedingJoinPoint joinPoint, AuditLog ann, Object result,
                          EvaluationContext context, Object beforeValue) {
        edu.fzrj.metrics.module.audit.entity.AuditLog entity =
                new edu.fzrj.metrics.module.audit.entity.AuditLog();
        entity.setOccurredAt(LocalDateTime.now());
        entity.setAction(ann.action());
        entity.setResourceType(ann.resourceType());

        LoginUser loginUser = SecurityUtils.getLoginUserOrNull();
        if (loginUser != null) {
            entity.setOperatorId(loginUser.getUserId());
            entity.setOperatorName(loginUser.getName());
            entity.setUnitId(loginUser.getUnitId());
            try {
                entity.setRoles(objectMapper.writeValueAsString(loginUser.getRoles()));
            } catch (Exception ignored) {
            }
        }

        // #result 暴露方法返回值，供创建类动作取 resourceId（如 resourceId = "#result.id"）
        ((StandardEvaluationContext) context).setVariable("result", result);
        ((StandardEvaluationContext) context).setVariable("before", beforeValue);
        if (!ann.resourceId().isBlank()) {
            Object resourceId = parser.parseExpression(ann.resourceId()).getValue(context);
            if (resourceId instanceof Number number) {
                entity.setResourceId(number.longValue());
            } else if (resourceId != null) {
                entity.setResourceCode(String.valueOf(resourceId));
            }
        }
        if (!ann.reason().isBlank()) {
            Object reason = parser.parseExpression(ann.reason()).getValue(context);
            entity.setReason(reason == null ? null : String.valueOf(reason));
        }
        Object afterValue = null;
        if (!ann.before().isBlank()) {
            entity.setBeforeJson(toJson(beforeValue));
        }
        if (!ann.after().isBlank()) {
            afterValue = parser.parseExpression(ann.after()).getValue(context);
            entity.setAfterJson(toJson(afterValue));
        }
        entity.setStakeholderIds(toJson(stakeholderIds(beforeValue, afterValue != null ? afterValue : result)));

        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            entity.setClientType(request.getHeader("X-Client-Type"));
            entity.setUserAgent(request.getHeader("User-Agent"));
            String xff = request.getHeader("X-Forwarded-For");
            entity.setIp(xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : request.getRemoteAddr());
        }
        entity.setRequestId(MDC.get(RequestIdFilter.MDC_KEY));

        auditLogMapper.insert(entity);
    }

    private EvaluationContext spelContext(ProceedingJoinPoint joinPoint) {
        StandardEvaluationContext context = new StandardEvaluationContext(joinPoint.getTarget());
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = nameDiscoverer.getParameterNames(signature.getMethod());
        Object[] args = joinPoint.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        return context;
    }

    private Set<Long> stakeholderIds(Object... values) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Object value : values) {
            if (value != null) {
                collectStakeholderIds(objectMapper.valueToTree(value), null, ids);
            }
        }
        return ids;
    }

    private void collectStakeholderIds(JsonNode node, String fieldName, Set<Long> ids) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectStakeholderIds(item, fieldName, ids);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (fieldName != null && USER_REFERENCE_FIELDS.contains(fieldName)) {
            addUserId(node.get("id"), ids);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (USER_ID_FIELDS.contains(field.getKey())) {
                addUserId(field.getValue(), ids);
            } else {
                collectStakeholderIds(field.getValue(), field.getKey(), ids);
            }
        }
    }

    private void addUserId(JsonNode value, Set<Long> ids) {
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            return;
        }
        try {
            ids.add(Long.valueOf(value.asText()));
        } catch (NumberFormatException ignored) {
            log.warn("忽略无法解析的审计干系人 ID：{}", value.asText());
        }
    }

    private static class AuditedInvocationException extends RuntimeException {
        private AuditedInvocationException(Throwable cause) {
            super(cause);
        }
    }

    /** 对象 → JSON 字符串（字符串原样返回，null 返回 null）；序列化失败时降级为 String.valueOf */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
