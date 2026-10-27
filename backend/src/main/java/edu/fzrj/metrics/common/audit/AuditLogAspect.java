package edu.fzrj.metrics.common.audit;

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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 审计切面：@AuditLog 方法执行成功后写 audit_log（只追加，库层触发器禁改删）。
 * 审计写入失败不影响主业务；before/after/stakeholders 由后续阶段按需补充。
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    public AuditLogAspect(AuditLogMapper auditLogMapper, ObjectMapper objectMapper) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(auditLogAnn)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLogAnn) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            writeLog(joinPoint, auditLogAnn, result);
        } catch (Exception e) {
            log.error("审计日志写入失败", e);
        }
        return result;
    }

    private void writeLog(ProceedingJoinPoint joinPoint, AuditLog ann, Object result) {
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

        EvaluationContext context = spelContext(joinPoint);
        // #result 暴露方法返回值，供创建类动作取 resourceId（如 resourceId = "#result.id"）
        ((StandardEvaluationContext) context).setVariable("result", result);
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
        if (!ann.before().isBlank()) {
            entity.setBeforeJson(toJson(parser.parseExpression(ann.before()).getValue(context)));
        }
        if (!ann.after().isBlank()) {
            entity.setAfterJson(toJson(parser.parseExpression(ann.after()).getValue(context)));
        }

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
        StandardEvaluationContext context = new StandardEvaluationContext();
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
