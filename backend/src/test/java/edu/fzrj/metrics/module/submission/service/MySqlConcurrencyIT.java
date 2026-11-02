package edu.fzrj.metrics.module.submission.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.adjustment.dto.AdjustmentDtos;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentDecisionService;
import edu.fzrj.metrics.module.adjustment.service.AdjustmentService;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.service.support.AnnualTargetGuard;
import edu.fzrj.metrics.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "app.feishu.push-enabled=false",
        "app.reminder.enabled=false"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "mysql.it", matches = "true")
class MySqlConcurrencyIT {

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private AdjustmentService adjustmentService;
    @Autowired
    private AdjustmentDecisionService adjustmentDecisionService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoSpyBean
    private AnnualTargetGuard annualTargetGuard;

    private final List<Long> createdPlanIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (int i = createdPlanIds.size() - 1; i >= 0; i--) {
            deletePlan(createdPlanIds.get(i));
        }
        createdPlanIds.clear();
    }

    @Test
    void 并发六加六审核不能绕过年度目标十() throws Exception {
        Fixture fixture = fixture("INCREMENT", "HIGHER_BETTER", "10");
        TaskFixture task = addTask(fixture, "C01", "10");
        long first = addSubmission(task, "6", null);
        long second = addSubmission(task, "6", null);

        CountDownLatch bothBeforeLock = new CountDownLatch(2);
        doAnswer(invocation -> {
            bothBeforeLock.countDown();
            assertTrue(bothBeforeLock.await(10, SECONDS));
            return invocation.callRealMethod();
        }).when(annualTargetGuard).checkReview(any(), any(), any());

        List<Throwable> results = reviewConcurrently(List.of(first, second));

        long success = results.stream().filter(java.util.Objects::isNull).count();
        assertEquals(1, success);
        BizException rejected = results.stream().filter(java.util.Objects::nonNull)
                .map(e -> assertInstanceOf(BizException.class, e)).findFirst().orElseThrow();
        assertEquals(60007, rejected.getErrorCode().getCode());
        assertDecimal("6", taskYearValue(task.taskYearId, "actual_value"));
        assertEquals(1, taskYearValue(task.taskYearId, "version", Integer.class));
        assertEquals(1, count("review", "submission_id in (?, ?)", first, second));
        assertEquals(1, count("notification", "biz_type = 'submission' and biz_id in (?, ?)", first, second));
        assertEquals(1, count("submission", "id in (?, ?) and status = 'APPROVED'", first, second));
        assertEquals(1, count("submission", "id in (?, ?) and status = 'PENDING_REVIEW'", first, second));
    }

    @Test
    void 越小越好并发审核后完成值版本审核记录和通知一致() throws Exception {
        Fixture fixture = fixture("INCREMENT", "LOWER_BETTER", "10");
        TaskFixture task = addTask(fixture, "C01", "10");
        long first = addSubmission(task, "4", null);
        long second = addSubmission(task, "3", null);

        List<Throwable> results = reviewConcurrently(List.of(first, second));

        results.forEach(e -> assertNull(e));
        assertDecimal("7", taskYearValue(task.taskYearId, "actual_value"));
        assertEquals(2, taskYearValue(task.taskYearId, "version", Integer.class));
        assertEquals(2, count("review", "submission_id in (?, ?)", first, second));
        assertEquals(2, count("notification", "biz_type = 'submission' and biz_id in (?, ?)", first, second));
        assertEquals(2, count("submission", "id in (?, ?) and status = 'APPROVED'", first, second));
    }

    @Test
    void 是否型并发审核后完成值版本审核记录和通知一致() throws Exception {
        Fixture fixture = fixture("BOOLEAN", "HIGHER_BETTER", "1");
        TaskFixture task = addTask(fixture, "C01", "1");
        long first = addSubmission(task, null, true);
        long second = addSubmission(task, null, false);

        List<Throwable> results = reviewConcurrently(List.of(first, second));

        results.forEach(e -> assertNull(e));
        assertDecimal("1", taskYearValue(task.taskYearId, "actual_value"));
        assertEquals(2, taskYearValue(task.taskYearId, "version", Integer.class));
        assertEquals(2, count("review", "submission_id in (?, ?)", first, second));
        assertEquals(2, count("notification", "biz_type = 'submission' and biz_id in (?, ?)", first, second));
        assertEquals(2, count("submission", "id in (?, ?) and status = 'APPROVED'", first, second));
    }

    @Test
    void 交叠转移的生效后快照与最新落库目标一致() throws Exception {
        login();
        Fixture fixture = fixture("INCREMENT", "HIGHER_BETTER", "10");
        TaskFixture firstSource = addTask(fixture, "C01", "5");
        TaskFixture secondSource = addTask(fixture, "C02", "5");
        TaskFixture receiver = addTask(fixture, "C03", "0");

        AdjustmentDtos.Detail first = adjustmentService.create(
                transferRequest(firstSource.taskId, fixture.unitId("C03"), "1"));
        AdjustmentDtos.Detail second = adjustmentService.create(
                transferRequest(secondSource.taskId, fixture.unitId("C03"), "2"));
        approve(first.id());
        approve(second.id());

        assertDecimal("3", taskYearValue(receiver.taskYearId, "target"));
        assertEquals(3, taskYearValue(receiver.taskYearId, "target_version", Integer.class));
        String json = jdbc.queryForObject("select snapshot_after from adjustment where id = ?",
                String.class, second.id());
        List<AdjustmentDtos.SnapshotEntry> snapshot = objectMapper.readValue(json, new TypeReference<>() {
        });
        AdjustmentDtos.SnapshotEntry receiverEntry = snapshot.stream()
                .filter(e -> receiver.code.equals(e.taskCode()) && e.year().equals(2026))
                .findFirst().orElseThrow();
        assertDecimal("3", receiverEntry.target());
        assertDecimal("4", taskYearValue(firstSource.taskYearId, "target"));
        assertDecimal("3", taskYearValue(secondSource.taskYearId, "target"));
    }

    private List<Throwable> reviewConcurrently(List<Long> submissionIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(submissionIds.size());
        CountDownLatch ready = new CountDownLatch(submissionIds.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        try {
            for (Long id : submissionIds) {
                futures.add(executor.submit(() -> {
                    login();
                    ready.countDown();
                    start.await(10, SECONDS);
                    try {
                        reviewService.review(id, reviewRequest());
                        return null;
                    } catch (Throwable e) {
                        return e;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertTrue(ready.await(10, SECONDS));
            start.countDown();
            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                results.add(future.get(30, SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture fixture(String caliber, String direction, String target) {
        long planId = IdWorker.getId();
        jdbc.update("insert into plan(id, name, start_year, end_year, baseline_year, status, "
                        + "is_receiver_confirm_required) values (?, ?, 2026, 2030, 2025, 'ACTIVE', 0)",
                planId, "MySQL并发回归-" + planId);
        createdPlanIds.add(planId);
        for (int year = 2026; year <= 2030; year++) {
            jdbc.update("insert into plan_year(id, plan_id, year, status) values (?, ?, ?, 'ACTIVE')",
                    IdWorker.getId(), planId, year);
        }
        long directionId = IdWorker.getId();
        jdbc.update("insert into direction(id, plan_id, subject_type, name) values (?, ?, 'SCHOOL', ?)",
                directionId, planId, "并发回归方向");
        long indicatorId = IdWorker.getId();
        String code = "IT" + Long.toUnsignedString(indicatorId).substring(12);
        jdbc.update("insert into indicator(id, plan_id, direction_id, level, code, name, nature, "
                        + "measure_type, stat_caliber, unit_label, target_direction, rollup_mode, "
                        + "is_quantifiable, total_target, owner_id, status) "
                        + "values (?, ?, ?, 'A', ?, ?, 'DEVELOPMENT', ?, ?, '项', ?, 'AUTO_SUM', 1, ?, 1, 'DISTRIBUTED')",
                indicatorId, planId, directionId, code, "并发回归指标",
                "BOOLEAN".equals(caliber) ? "BOOLEAN" : "NUMBER", caliber, direction, target);
        jdbc.update("insert into indicator_year_target(id, indicator_id, year, target) values (?, ?, 2026, ?)",
                IdWorker.getId(), indicatorId, target);
        return new Fixture(planId, indicatorId);
    }

    private TaskFixture addTask(Fixture fixture, String unitCode, String target) {
        long taskId = IdWorker.getId();
        long unitId = fixture.unitId(unitCode);
        String code = jdbc.queryForObject("select code from indicator where id = ?", String.class,
                fixture.indicatorId) + "-" + unitCode;
        jdbc.update("insert into task(id, plan_id, indicator_id, top_indicator_id, unit_id, code, owner_id, "
                        + "status, distributed_at) values (?, ?, ?, ?, ?, ?, 1, 'IN_PROGRESS', now())",
                taskId, fixture.planId, fixture.indicatorId, fixture.indicatorId, unitId, code);
        long taskYearId = IdWorker.getId();
        jdbc.update("insert into task_year(id, task_id, year, plan_id, indicator_id, unit_id, target, "
                        + "original_target, year_status) values (?, ?, 2026, ?, ?, ?, ?, ?, 'IN_PROGRESS')",
                taskYearId, taskId, fixture.planId, fixture.indicatorId, unitId, target, target);
        return new TaskFixture(taskId, taskYearId, code);
    }

    private long addSubmission(TaskFixture task, String amount, Boolean achieved) {
        long id = IdWorker.getId();
        jdbc.update("insert into submission(id, task_id, task_year_id, year, plan_id, indicator_id, unit_id, "
                        + "stat_caliber, amount, achieved, status, revision_no, submitted_at, submitted_by, created_by) "
                        + "select ?, task_id, id, year, plan_id, indicator_id, unit_id, "
                        + "(select stat_caliber from indicator where id = task_year.indicator_id), ?, ?, "
                        + "'PENDING_REVIEW', 1, now(), 1, 1 from task_year where id = ?",
                id, amount, achieved, task.taskYearId);
        return id;
    }

    private AdjustmentDtos.CreateRequest transferRequest(Long fromTaskId, Long toUnitId, String amount) {
        AdjustmentDtos.ItemRequest item = new AdjustmentDtos.ItemRequest();
        item.setFromYear(2026);
        item.setToYear(2026);
        item.setAmount(new BigDecimal(amount));
        AdjustmentDtos.CreateRequest request = new AdjustmentDtos.CreateRequest();
        request.setType("TRANSFER");
        request.setTaskId(fromTaskId);
        request.setToUnitId(toUnitId);
        request.setReason("MySQL交叠调整回归");
        request.setItems(List.of(item));
        return request;
    }

    private void approve(Long adjustmentId) {
        AdjustmentDtos.Detail current = adjustmentService.detail(adjustmentId);
        AdjustmentDtos.DecisionRequest owner = new AdjustmentDtos.DecisionRequest();
        owner.setDecision("APPROVE");
        owner.setVersion(current.version());
        adjustmentDecisionService.decide(adjustmentId, owner);
        current = adjustmentService.detail(adjustmentId);
        AdjustmentDtos.DecisionRequest leader = new AdjustmentDtos.DecisionRequest();
        leader.setDecision("APPROVE");
        leader.setVersion(current.version());
        adjustmentDecisionService.decide(adjustmentId, leader);
    }

    private SubmissionDtos.ReviewRequest reviewRequest() {
        SubmissionDtos.ReviewRequest request = new SubmissionDtos.ReviewRequest();
        request.setResult("APPROVED");
        request.setVersion(0);
        request.setComment("MySQL并发回归");
        return request;
    }

    private void login() {
        LoginUser user = new LoginUser(1L, "admin", "管理员", null,
                List.of("ADMIN", "LEADER"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private BigDecimal taskYearValue(Long id, String column) {
        return taskYearValue(id, column, BigDecimal.class);
    }

    private <T> T taskYearValue(Long id, String column, Class<T> type) {
        if (!List.of("actual_value", "version", "target", "target_version").contains(column)) {
            throw new IllegalArgumentException("不允许查询的字段：" + column);
        }
        return jdbc.queryForObject("select " + column + " from task_year where id = ?", type, id);
    }

    private int count(String table, String condition, Object... args) {
        if (!List.of("review", "notification", "submission").contains(table)) {
            throw new IllegalArgumentException("不允许统计的表：" + table);
        }
        Integer count = jdbc.queryForObject("select count(*) from " + table + " where " + condition,
                Integer.class, args);
        return count == null ? 0 : count;
    }

    private void deletePlan(Long planId) {
        jdbc.update("delete from notification where (biz_type = 'submission' and biz_id in "
                + "(select id from submission where plan_id = ?)) or (biz_type = 'adjustment' and biz_id in "
                + "(select id from adjustment where plan_id = ?))", planId, planId);
        jdbc.update("delete from review where submission_id in (select id from submission where plan_id = ?)", planId);
        jdbc.update("delete from approval_step where adjustment_id in (select id from adjustment where plan_id = ?)", planId);
        jdbc.update("delete from adjustment_item where adjustment_id in (select id from adjustment where plan_id = ?)", planId);
        jdbc.update("delete from adjustment where plan_id = ?", planId);
        jdbc.update("delete from submission where plan_id = ?", planId);
        jdbc.update("delete from task_year where plan_id = ?", planId);
        jdbc.update("delete from task where plan_id = ?", planId);
        jdbc.update("delete from indicator_year_target where indicator_id in (select id from indicator where plan_id = ?)", planId);
        jdbc.update("delete from indicator where plan_id = ?", planId);
        jdbc.update("delete from plan_year where plan_id = ?", planId);
        jdbc.update("delete from direction where plan_id = ?", planId);
        jdbc.update("delete from plan where id = ?", planId);
    }

    private void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private final class Fixture {
        private final Long planId;
        private final Long indicatorId;

        private Fixture(Long planId, Long indicatorId) {
            this.planId = planId;
            this.indicatorId = indicatorId;
        }

        private Long unitId(String code) {
            return jdbc.queryForObject("select id from org_unit where code = ? and deleted = 0", Long.class, code);
        }
    }

    private record TaskFixture(Long taskId, Long taskYearId, String code) {
    }
}
