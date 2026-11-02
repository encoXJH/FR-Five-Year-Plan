package edu.fzrj.metrics.module.task.service.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 年度粒度的 FS 依赖图：Kahn 拓扑与 CPM 关键路径，时间复杂度 O(V+E)。 */
public final class TaskScheduleGraph {

    public static final String EMPTY = "EMPTY";
    public static final String OK = "OK";
    public static final String NO_DEPENDENCIES = "NO_DEPENDENCIES";
    public static final String UNSCHEDULED = "UNSCHEDULED";
    public static final String CYCLE_DETECTED = "CYCLE_DETECTED";
    public static final String YEAR_CONFLICT = "YEAR_CONFLICT";

    private TaskScheduleGraph() {
    }

    public record Node(Long taskId, Integer startYear, Integer endYear) {
        private boolean isScheduled() {
            return taskId != null && startYear != null && endYear != null && endYear >= startYear;
        }

        private int duration() {
            return endYear - startYear + 1;
        }
    }

    public record Edge(Long id, Long predecessorTaskId, Long successorTaskId) {
    }

    public record Result(String status, List<Long> taskIds, List<Long> dependencyIds) {
        private static Result of(String status) {
            return new Result(status, List.of(), List.of());
        }
    }

    public static Result analyze(List<Node> nodes, List<Edge> edges) {
        Map<Long, Node> nodeMap = new LinkedHashMap<>();
        if (nodes != null) {
            for (Node node : nodes) {
                if (node != null && node.taskId() != null) {
                    nodeMap.putIfAbsent(node.taskId(), node);
                }
            }
        }
        if (nodeMap.isEmpty()) {
            return Result.of(EMPTY);
        }
        List<Edge> validEdges = edges == null ? List.of() : edges.stream()
                .filter(edge -> edge != null
                        && nodeMap.containsKey(edge.predecessorTaskId())
                        && nodeMap.containsKey(edge.successorTaskId()))
                .toList();
        if (validEdges.isEmpty()) {
            return Result.of(NO_DEPENDENCIES);
        }

        Graph graph = new Graph(nodeMap.keySet().stream().toList(), validEdges);
        graph.applySchedules(nodeMap);
        List<Integer> topological = graph.topologicalOrder();
        if (topological.size() != nodeMap.size()) {
            return Result.of(CYCLE_DETECTED);
        }
        Set<Integer> active = graph.activeNodes();
        for (int index : active) {
            if (!graph.nodes.get(index).isScheduled()) {
                return Result.of(UNSCHEDULED);
            }
        }
        for (Edge edge : validEdges) {
            Node predecessor = nodeMap.get(edge.predecessorTaskId());
            Node successor = nodeMap.get(edge.successorTaskId());
            if (successor.startYear() <= predecessor.endYear()) {
                return Result.of(YEAR_CONFLICT);
            }
        }
        return criticalPath(graph, topological, active, validEdges);
    }

    /** 在既有边与候选边的集合上检测环，不要求任务已排期。 */
    public static boolean wouldCreateCycle(List<Edge> edges) {
        Map<Long, Node> nodes = new LinkedHashMap<>();
        List<Edge> safe = edges == null ? List.of() : edges.stream().filter(edge -> edge != null).toList();
        for (Edge edge : safe) {
            if (edge.predecessorTaskId() != null) {
                nodes.putIfAbsent(edge.predecessorTaskId(), new Node(edge.predecessorTaskId(), null, null));
            }
            if (edge.successorTaskId() != null) {
                nodes.putIfAbsent(edge.successorTaskId(), new Node(edge.successorTaskId(), null, null));
            }
        }
        if (nodes.isEmpty()) {
            return false;
        }
        Graph graph = new Graph(nodes.keySet().stream().toList(), safe);
        return graph.topologicalOrder().size() != nodes.size();
    }

    private static Result criticalPath(Graph graph, List<Integer> topological,
                                       Set<Integer> active, List<Edge> edges) {
        int size = graph.nodes.size();
        int[] earliestStart = new int[size];
        int[] earliestFinish = new int[size];
        int projectDuration = 0;
        for (int index : topological) {
            if (!active.contains(index)) {
                continue;
            }
            earliestFinish[index] = earliestStart[index] + graph.nodes.get(index).duration();
            projectDuration = Math.max(projectDuration, earliestFinish[index]);
            for (int successor : graph.successors.get(index)) {
                if (active.contains(successor)) {
                    earliestStart[successor] = Math.max(earliestStart[successor], earliestFinish[index]);
                }
            }
        }

        int[] latestStart = new int[size];
        int[] latestFinish = new int[size];
        for (int index : active) {
            latestFinish[index] = projectDuration;
        }
        for (int i = topological.size() - 1; i >= 0; i--) {
            int index = topological.get(i);
            if (!active.contains(index)) {
                continue;
            }
            for (int successor : graph.successors.get(index)) {
                if (active.contains(successor)) {
                    latestFinish[index] = Math.min(latestFinish[index], latestStart[successor]);
                }
            }
            latestStart[index] = latestFinish[index] - graph.nodes.get(index).duration();
        }

        Set<Integer> criticalNodes = new HashSet<>();
        for (int index : active) {
            if (latestStart[index] == earliestStart[index]) {
                criticalNodes.add(index);
            }
        }
        List<Long> taskIds = topological.stream()
                .filter(criticalNodes::contains)
                .map(index -> graph.nodes.get(index).taskId())
                .toList();
        List<Long> dependencyIds = new ArrayList<>();
        for (Edge edge : edges) {
            int predecessor = graph.indexOf(edge.predecessorTaskId());
            int successor = graph.indexOf(edge.successorTaskId());
            if (criticalNodes.contains(predecessor) && criticalNodes.contains(successor)
                    && earliestStart[successor] == earliestFinish[predecessor]) {
                dependencyIds.add(edge.id());
            }
        }
        return new Result(OK, taskIds, List.copyOf(dependencyIds));
    }

    private static final class Graph {
        private final List<Node> nodes;
        private final Map<Long, Integer> indexes;
        private final List<List<Integer>> successors;
        private final int[] indegrees;

        private Graph(List<Long> taskIds, List<Edge> edges) {
            nodes = new ArrayList<>(taskIds.size());
            indexes = new LinkedHashMap<>();
            successors = new ArrayList<>(taskIds.size());
            indegrees = new int[taskIds.size()];
            for (Long taskId : taskIds) {
                indexes.put(taskId, nodes.size());
                nodes.add(new Node(taskId, null, null));
                successors.add(new ArrayList<>());
            }
            for (Edge edge : edges) {
                Integer predecessor = indexes.get(edge.predecessorTaskId());
                Integer successor = indexes.get(edge.successorTaskId());
                if (predecessor == null || successor == null) {
                    continue;
                }
                successors.get(predecessor).add(successor);
                indegrees[successor]++;
            }
        }

        private void applySchedules(Map<Long, Node> schedules) {
            for (int i = 0; i < nodes.size(); i++) {
                Node schedule = schedules.get(nodes.get(i).taskId());
                if (schedule != null) {
                    nodes.set(i, schedule);
                }
            }
        }

        private int indexOf(Long taskId) {
            return indexes.get(taskId);
        }

        private Set<Integer> activeNodes() {
            Set<Integer> active = new HashSet<>();
            for (List<Integer> targets : successors) {
                for (int target : targets) {
                    active.add(target);
                }
            }
            for (int i = 0; i < successors.size(); i++) {
                if (!successors.get(i).isEmpty()) {
                    active.add(i);
                }
            }
            return active;
        }

        private List<Integer> topologicalOrder() {
            int[] remaining = indegrees.clone();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int i = 0; i < remaining.length; i++) {
                if (remaining[i] == 0) {
                    queue.add(i);
                }
            }
            List<Integer> result = new ArrayList<>(nodes.size());
            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                result.add(index);
                for (int successor : successors.get(index)) {
                    if (--remaining[successor] == 0) {
                        queue.addLast(successor);
                    }
                }
            }
            return result;
        }
    }
}
