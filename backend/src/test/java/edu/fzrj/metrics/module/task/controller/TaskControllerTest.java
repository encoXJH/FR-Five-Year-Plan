package edu.fzrj.metrics.module.task.controller;

import edu.fzrj.metrics.module.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    @Mock
    private TaskService taskService;
    @InjectMocks
    private TaskController taskController;

    @Test
    void 干事审计快照委托任务服务() {
        when(taskService.staffAuditSnapshot(7L)).thenReturn(Map.of("staffIds", List.of(301L, 302L)));

        Map<String, List<Long>> snapshot = taskController.staffAuditSnapshot(7L);

        assertEquals(List.of(301L, 302L), snapshot.get("staffIds"));
    }
}
