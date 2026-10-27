package edu.fzrj.metrics.module.notify.controller;

import edu.fzrj.metrics.module.notify.dto.NotifyDtos;
import edu.fzrj.metrics.module.notify.service.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的待办（API 约定 15.9）
 */
@Tag(name = "待办")
@RestController
@RequestMapping("/api/v1/todos")
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @Operation(summary = "我的待办：待填报 / 待审核 / 待确认接收 / 待审批",
            description = "已登录；按当前用户角色与数据范围聚合，不分页；items 按 todoSince 升序，summary 为各类型计数；"
                    + "bizId 语义：FILL/ACCEPT=任务 id，REVIEW=填报 id，APPROVAL=调整申请 id")
    @GetMapping
    public NotifyDtos.TodoView todos() {
        return todoService.todos();
    }
}
