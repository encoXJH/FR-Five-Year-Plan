package edu.fzrj.metrics.module.task.service.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskScheduleGraphTest {

    @Test
    void 空图和无依赖图返回明确状态() {
        assertEquals(TaskScheduleGraph.EMPTY, TaskScheduleGraph.analyze(List.of(), List.of()).status());
        TaskScheduleGraph.Result result = TaskScheduleGraph.analyze(List.of(node(1, 2026, 2027)), List.of());
        assertEquals(TaskScheduleGraph.NO_DEPENDENCIES, result.status());
        assertTrue(result.taskIds().isEmpty());
    }

    @Test
    void 链式依赖全部节点和边位于关键路径() {
        TaskScheduleGraph.Result result = TaskScheduleGraph.analyze(
                List.of(node(1, 2026, 2026), node(2, 2027, 2027), node(3, 2028, 2029)),
                List.of(edge(11, 1, 2), edge(12, 2, 3)));

        assertEquals(TaskScheduleGraph.OK, result.status());
        assertEquals(List.of(1L, 2L, 3L), result.taskIds());
        assertEquals(List.of(11L, 12L), result.dependencyIds());
    }

    @Test
    void 菱形图只标记零时差分支() {
        TaskScheduleGraph.Result result = TaskScheduleGraph.analyze(
                List.of(node(1, 2026, 2026), node(2, 2027, 2027),
                        node(3, 2027, 2028), node(4, 2029, 2029)),
                List.of(edge(11, 1, 2), edge(12, 1, 3), edge(13, 2, 4), edge(14, 3, 4)));

        assertEquals(TaskScheduleGraph.OK, result.status());
        assertEquals(List.of(1L, 3L, 4L), result.taskIds());
        assertEquals(List.of(12L, 14L), result.dependencyIds());
        assertFalse(result.taskIds().contains(2L));
    }

    @Test
    void 等长多条关键路径全部保留() {
        TaskScheduleGraph.Result result = TaskScheduleGraph.analyze(
                List.of(node(1, 2026, 2026), node(2, 2027, 2027),
                        node(3, 2027, 2027), node(4, 2028, 2028)),
                List.of(edge(11, 1, 2), edge(12, 1, 3), edge(13, 2, 4), edge(14, 3, 4)));

        assertEquals(TaskScheduleGraph.OK, result.status());
        assertEquals(List.of(1L, 2L, 3L, 4L), result.taskIds());
        assertEquals(List.of(11L, 12L, 13L, 14L), result.dependencyIds());
    }

    @Test
    void 自环和长环均被识别() {
        assertTrue(TaskScheduleGraph.wouldCreateCycle(List.of(edge(1, 1, 1))));
        assertTrue(TaskScheduleGraph.wouldCreateCycle(
                List.of(edge(1, 1, 2), edge(2, 2, 3), edge(3, 3, 4), edge(4, 4, 2))));
        assertFalse(TaskScheduleGraph.wouldCreateCycle(
                List.of(edge(1, 1, 2), edge(2, 1, 3), edge(3, 2, 4), edge(4, 3, 4))));
        assertEquals(TaskScheduleGraph.CYCLE_DETECTED, TaskScheduleGraph.analyze(
                List.of(node(1, 2026, 2026), node(2, 2027, 2027)),
                List.of(edge(11, 1, 2), edge(12, 2, 1))).status());
    }

    @Test
    void 未排期与FS年度冲突不计算伪关键路径() {
        TaskScheduleGraph.Result unscheduled = TaskScheduleGraph.analyze(
                List.of(node(1, 2026, 2026), node(2, null, null)), List.of(edge(11, 1, 2)));
        assertEquals(TaskScheduleGraph.UNSCHEDULED, unscheduled.status());
        assertTrue(unscheduled.taskIds().isEmpty());

        TaskScheduleGraph.Result conflict = TaskScheduleGraph.analyze(
                List.of(node(1, 2026, 2027), node(2, 2027, 2028)), List.of(edge(11, 1, 2)));
        assertEquals(TaskScheduleGraph.YEAR_CONFLICT, conflict.status());
        assertTrue(conflict.dependencyIds().isEmpty());
    }

    @Test
    void 千节点千边保持线性计算并标记主链() {
        List<TaskScheduleGraph.Node> nodes = new ArrayList<>();
        List<TaskScheduleGraph.Edge> edges = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            nodes.add(node(i + 1, 1000 + i, 1000 + i));
            if (i > 0) {
                edges.add(edge(i, i, i + 1));
            }
        }
        edges.add(edge(1000, 1, 1000));

        TaskScheduleGraph.Result result = TaskScheduleGraph.analyze(nodes, edges);

        assertEquals(TaskScheduleGraph.OK, result.status());
        assertEquals(1000, result.taskIds().size());
        assertEquals(999, result.dependencyIds().size());
        assertFalse(result.dependencyIds().contains(1000L));
    }

    private static TaskScheduleGraph.Node node(long id, Integer start, Integer end) {
        return new TaskScheduleGraph.Node(id, start, end);
    }

    private static TaskScheduleGraph.Edge edge(long id, long predecessor, long successor) {
        return new TaskScheduleGraph.Edge(id, predecessor, successor);
    }
}
