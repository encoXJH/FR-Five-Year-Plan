package edu.fzrj.metrics.module.integration.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "mysql.it", matches = "true")
class FeishuBotMySqlIT {

    private static final String HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final String PORT = System.getenv().getOrDefault("DB_PORT", "3306");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "Metrics@2026");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    private final List<String> databases = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String database : databases) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
        databases.clear();
    }

    @Test
    void 全新库迁移到V42并保证通知唯一键与推送抢占原子性() throws Exception {
        String database = createDatabase();
        migrate(database, null);

        assertEquals("42", schemaVersion(database));
        try (Connection connection = connection(database)) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys_setting WHERE setting_key='feishu.pushEnabled' "
                    + "AND setting_value='false' AND value_type='BOOLEAN' AND version=0 AND deleted=0"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys_setting WHERE setting_key='feishu.botEnabled' "
                    + "AND setting_value='false' AND value_type='BOOLEAN' AND version=0 AND deleted=0"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys_setting WHERE setting_key='feishu.logClearedAt' "
                    + "AND setting_value<>'' AND value_type='STRING' AND version=0 AND deleted=0"));
            long id = 920000000000000001L;
            insertNotification(connection, id, "BOT:TEST:CLAIM");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<Boolean>> inserts = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    long candidate = id + i + 1;
                    inserts.add(pool.submit(() -> {
                        ready.countDown();
                        start.await(5, SECONDS);
                        try (Connection concurrent = connection(database)) {
                            insertNotification(concurrent, candidate, "BOT:TEST:CONCURRENT");
                            return true;
                        } catch (java.sql.SQLIntegrityConstraintViolationException duplicate) {
                            return false;
                        }
                    }));
                }
                assertTrue(ready.await(5, SECONDS));
                start.countDown();
                assertEquals(1, inserts.stream().mapToInt(f -> {
                    try {
                        return f.get(10, SECONDS) ? 1 : 0;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).sum());
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM notification WHERE dedup_key='BOT:TEST:CONCURRENT'"));

            ExecutorService claims = Executors.newFixedThreadPool(2);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(claims.submit(() -> {
                        try (Connection concurrent = connection(database); PreparedStatement statement = concurrent.prepareStatement(
                                "UPDATE notification SET feishu_next_retry_at=? WHERE id=? AND feishu_status='PENDING' AND feishu_next_retry_at IS NULL")) {
                            statement.setObject(1, LocalDateTime.now().plusMinutes(5));
                            statement.setLong(2, id);
                            return statement.executeUpdate();
                        }
                    }));
                }
                int claimed = 0;
                for (Future<Integer> result : results) {
                    claimed += result.get(10, SECONDS);
                }
                assertEquals(1, claimed);
            } finally {
                claims.shutdownNow();
            }
        }
    }

    @Test
    void V38可原地升级到V39且推送开关初始关闭() throws Exception {
        String database = createDatabase();
        migrate(database, "38");
        assertEquals("38", schemaVersion(database));

        migrate(database, "39");

        assertEquals("39", schemaVersion(database));
        try (Connection connection = connection(database)) {
            insertNotification(connection, 920000000000000010L, "BOT:REVIEW_DAILY:1:2026-11-24", "REVIEW_TODO");
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA='" + database + "' AND TABLE_NAME='notification' AND COLUMN_NAME='dedup_key'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA='" + database + "' AND TABLE_NAME='notification' AND INDEX_NAME='uk_notification_dedup_key'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys_setting WHERE setting_key='feishu.pushEnabled' "
                    + "AND setting_value='false' AND value_type='BOOLEAN' AND version=0 AND deleted=0"));
        }
    }

    @Test
    void V39升级到V40仅取消存量任务下发待发送记录() throws Exception {
        String database = createDatabase();
        migrate(database, "39");
        try (Connection connection = connection(database)) {
            insertNotification(connection, 920000000000000020L, "TASK:OLD:PENDING", "TASK_DISTRIBUTED");
            insertNotification(connection, 920000000000000021L, "TASK:OLD:SENT", "TASK_DISTRIBUTED");
            insertNotification(connection, 920000000000000022L, "REVIEW:OLD:PENDING", "REVIEW_TODO");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE notification SET feishu_status='SENT' WHERE id=920000000000000021");
            }
        }

        migrate(database, "40");

        assertEquals("40", schemaVersion(database));
        try (Connection connection = connection(database)) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM notification WHERE id=920000000000000020 "
                    + "AND feishu_status='SKIPPED' AND feishu_next_retry_at IS NULL "
                    + "AND feishu_last_error='按用户要求取消学院任务下发外发'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM notification WHERE id=920000000000000021 "
                    + "AND feishu_status='SENT' AND feishu_last_error IS NULL"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM notification WHERE id=920000000000000022 "
                    + "AND feishu_status='PENDING'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM audit_log "
                    + "WHERE request_id='flyway-v40-cancel-task-distributed-notifications' "
                    + "AND action='UPDATE' AND resource_type='notification' "
                    + "AND JSON_EXTRACT(after_json,'$.cancelledCount')=1"));

            insertNotification(connection, 920000000000000023L,
                    "TASK_STAFF_ASSIGNED:10:901:1901", "TASK_DISTRIBUTED");
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM notification WHERE id=920000000000000023 "
                    + "AND feishu_status='PENDING' AND feishu_last_error IS NULL"));
        }
    }

    @Test
    void V40升级到V41新增机器人开关且不改写消息推送状态() throws Exception {
        String database = createDatabase();
        migrate(database, "40");
        try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE sys_setting SET setting_value='true',version=4 "
                    + "WHERE setting_key='feishu.pushEnabled'");
        }

        migrate(database, "41");

        assertEquals("41", schemaVersion(database));
        try (Connection connection = connection(database)) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys_setting WHERE setting_key='feishu.botEnabled' "
                    + "AND setting_value='false' AND value_type='BOOLEAN' AND version=0 AND deleted=0"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM sys_setting WHERE setting_key='feishu.pushEnabled' "
                    + "AND setting_value='true' AND version=4 AND deleted=0"));
        }
    }

    @Test
    void V41升级到V42隐藏既有日志且保留通知并继续显示新日志() throws Exception {
        String database = createDatabase();
        migrate(database, "41");
        try (Connection connection = connection(database)) {
            insertNotification(connection, 920000000000000030L, "BOT:LOG:BEFORE_CLEAR", "REVIEW_TODO");
        }

        migrate(database, "42");

        assertEquals("42", schemaVersion(database));
        try (Connection connection = connection(database)) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM notification WHERE id=920000000000000030"));
            assertEquals(0, countVisibleAfterClear(connection));

            insertNotification(connection, 920000000000000031L, "BOT:LOG:AFTER_CLEAR", "REVIEW_TODO");
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM notification WHERE id=920000000000000030"));
            assertEquals(1, countVisibleAfterClear(connection));
        }
    }

    private int countVisibleAfterClear(Connection connection) throws Exception {
        return count(connection, "SELECT COUNT(*) FROM notification n JOIN sys_setting s "
                + "ON s.setting_key='feishu.logClearedAt' AND s.deleted=0 "
                + "WHERE n.created_at > CAST(s.setting_value AS DATETIME(6))");
    }

    private String createDatabase() throws Exception {
        String database = "metrics_bot_it_" + System.nanoTime();
        try (Connection connection = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        databases.add(database);
        return database;
    }

    private void migrate(String database, String target) throws Exception {
        Path temporary = Files.createTempDirectory("metrics-bot-migrations");
        try {
            try (Stream<Path> paths = Files.list(Path.of("src/main/resources/db/migration"))) {
                for (Path source : paths.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                    String sql = Files.readString(source).replace("metrics_db", database);
                    Files.writeString(temporary.resolve(source.getFileName().toString()), sql);
                }
            }
            FluentConfiguration configuration = Flyway.configure()
                    .dataSource(databaseUrl(database), USER, PASSWORD)
                    .locations("filesystem:" + temporary);
            if (target != null) {
                configuration.target(target);
            }
            configuration.load().migrate();
        } finally {
            try (Stream<Path> paths = Files.walk(temporary)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // 临时目录清理由操作系统兜底，不影响迁移结果。
                    }
                });
            }
        }
    }

    private String schemaVersion(String database) throws Exception {
        try (Connection connection = connection(database); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")) {
            result.next();
            return result.getString(1);
        }
    }

    private void insertNotification(Connection connection, long id, String dedupKey) throws Exception {
        insertNotification(connection, id, dedupKey, "WARNING");
    }

    private void insertNotification(Connection connection, long id, String dedupKey, String type) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO notification(id,user_id,type,title,content,feishu_status,feishu_retry_count,dedup_key) "
                        + "VALUES(?,?,?,?,?,'PENDING',0,?)")) {
            statement.setLong(1, id);
            statement.setLong(2, 1L);
            statement.setString(3, type);
            statement.setString(4, "机器人测试");
            statement.setString(5, "机器人迁移与防重测试");
            statement.setString(6, dedupKey);
            statement.executeUpdate();
        }
    }

    private int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private Connection connection(String database) throws Exception {
        return DriverManager.getConnection(databaseUrl(database), USER, PASSWORD);
    }

    private String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + OPTIONS;
    }

    private String databaseUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database + OPTIONS;
    }
}
