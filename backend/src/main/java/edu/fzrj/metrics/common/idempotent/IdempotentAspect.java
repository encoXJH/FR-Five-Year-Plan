package edu.fzrj.metrics.common.idempotent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.Sha256;
import edu.fzrj.metrics.module.system.entity.IdempotencyRecord;
import edu.fzrj.metrics.module.system.mapper.IdempotencyRecordMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 幂等切面（API 约定 5.6）：
 * Key 保存 24 小时；同 Key 同请求体重放首次结果；同 Key 异请求体返回 409+10004。
 * 先插占位记录（靠 uk 防并发），成功后回写响应，异常时删除记录允许重试。
 */
@Slf4j
@Aspect
@Component
public class IdempotentAspect {

    private static final String HEADER = "Idempotency-Key";
    private static final int EXPIRE_HOURS = 24;

    private final IdempotencyRecordMapper recordMapper;
    private final ObjectMapper objectMapper;

    public IdempotentAspect(IdempotencyRecordMapper recordMapper, ObjectMapper objectMapper) {
        this.recordMapper = recordMapper;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs == null ? null : attrs.getRequest();
        String idemKey = request == null ? null : request.getHeader(HEADER);
        if (idemKey == null || idemKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少请求头 Idempotency-Key");
        }
        Long userId = SecurityUtils.getUserId();
        String methodPath = request.getMethod() + " " + request.getRequestURI();
        String requestHash = Sha256.hex(methodPath + "\n" + argsJson(joinPoint.getArgs()));
        LocalDateTime now = LocalDateTime.now();

        IdempotencyRecord existing = recordMapper.selectOne(new LambdaQueryWrapper<IdempotencyRecord>()
                .eq(IdempotencyRecord::getUserId, userId)
                .eq(IdempotencyRecord::getIdemKey, idemKey));
        if (existing != null) {
            if (existing.getExpiresAt().isBefore(now)) {
                recordMapper.deleteById(existing.getId());
            } else {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new BizException(ErrorCode.IDEMPOTENT_CONFLICT);
                }
                if (existing.getResponseBody() == null) {
                    throw new BizException(ErrorCode.IDEMPOTENT_CONFLICT, "相同请求正在处理中，请稍后重试");
                }
                throw new IdempotentReplayException(existing);
            }
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setUserId(userId);
        record.setIdemKey(idemKey);
        record.setMethodPath(methodPath);
        record.setRequestHash(requestHash);
        record.setResponseStatus(0);
        record.setExpiresAt(now.plusHours(EXPIRE_HOURS));
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.IDEMPOTENT_CONFLICT, "相同请求正在处理中，请稍后重试");
        }

        try {
            Object result = joinPoint.proceed();
            record.setResponseStatus(200);
            record.setResponseBody(objectMapper.writeValueAsString(result));
            recordMapper.updateById(record);
            return result;
        } catch (Throwable t) {
            // 业务失败不落幂等记录，允许客户端修正后重试
            recordMapper.deleteById(record.getId());
            throw t;
        }
    }

    /** 请求参数序列化（排除不可序列化的 web 对象） */
    private String argsJson(Object[] args) {
        try {
            Object[] clean = Arrays.stream(args)
                    .map(arg -> (arg instanceof HttpServletRequest || arg instanceof MultipartFile) ? null : arg)
                    .toArray();
            return objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            return Arrays.toString(args);
        }
    }
}
