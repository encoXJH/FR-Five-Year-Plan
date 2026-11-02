package edu.fzrj.metrics.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.audit.AuditLogAspect;
import edu.fzrj.metrics.module.audit.mapper.AuditLogMapper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogAspectTest {

    @Test
    void 变更前快照在业务执行前捕获() throws Throwable {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        AuditLogAspect aspect = new AuditLogAspect(mapper, new ObjectMapper(), transactionManager);
        AuditTarget target = new AuditTarget();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        AuditLog annotation = AuditTarget.class.getMethod("execute").getAnnotation(AuditLog.class);

        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(signature.getMethod()).thenReturn(AuditTarget.class.getMethod("execute"));
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            target.state = "after";
            return target.snapshot();
        });

        Object result = aspect.around(joinPoint, annotation);

        assertEquals("after", ((Map<?, ?>) result).get("value"));
        ArgumentCaptor<edu.fzrj.metrics.module.audit.entity.AuditLog> captor =
                ArgumentCaptor.forClass(edu.fzrj.metrics.module.audit.entity.AuditLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals("before", new ObjectMapper().readValue(
                captor.getValue().getBeforeJson(), Map.class).get("value"));
        assertEquals("after", new ObjectMapper().readValue(
                captor.getValue().getAfterJson(), Map.class).get("value"));
        assertEquals(1L, captor.getValue().getResourceId());
        assertEquals("[7,8]", captor.getValue().getStakeholderIds());
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void 返回值携带的变更前快照在业务执行后读取() throws Throwable {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        AuditLogAspect aspect = new AuditLogAspect(mapper, new ObjectMapper(), transactionManager);
        ReturnedSnapshotTarget target = new ReturnedSnapshotTarget();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        AuditLog annotation = ReturnedSnapshotTarget.class.getMethod("execute").getAnnotation(AuditLog.class);
        SystemDtos.SettingListView result = new SystemDtos.SettingListView(
                java.util.List.of(), Map.of("value", "before"), Map.of("value", "after"));

        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(signature.getMethod()).thenReturn(ReturnedSnapshotTarget.class.getMethod("execute"));
        when(joinPoint.proceed()).thenReturn(result);

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<edu.fzrj.metrics.module.audit.entity.AuditLog> captor =
                ArgumentCaptor.forClass(edu.fzrj.metrics.module.audit.entity.AuditLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals("before", new ObjectMapper().readValue(
                captor.getValue().getBeforeJson(), Map.class).get("value"));
        assertEquals("after", new ObjectMapper().readValue(
                captor.getValue().getAfterJson(), Map.class).get("value"));
    }

    static class ReturnedSnapshotTarget {
        @AuditLog(action = "UPDATE", resourceType = "setting",
                before = "#result.auditBefore", after = "#result.auditAfter")
        public Object execute() {
            return null;
        }
    }

    static class AuditTarget {
        private String state = "before";

        @AuditLog(action = "UPDATE", resourceType = "indicator", resourceId = "1",
                before = "snapshot()", after = "#result")
        public Object execute() {
            return null;
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("value", state);
            snapshot.put("owner", Map.of("id", 7L, "name", "负责人"));
            snapshot.put("applicantId", 8L);
            return snapshot;
        }
    }
}
