package edu.fzrj.metrics.module.training.service;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "mysql.it", matches = "true")
class TrainingV45MySqlIT {

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
    void 全新库迁移到V45创建培训分配与附件约束() throws Exception {
        String database = createDatabase();

        migrate(database, "45");

        assertEquals("45", schemaVersion(database));
        assertTrainingSchema(database);
    }

    @Test
    void V44可原地升级到V45且保留既有约束名称() throws Exception {
        String database = createDatabase();
        migrate(database, "44");
        assertEquals("44", schemaVersion(database));
        assertEquals(0, count(database, "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='training_sheet_staff'"));

        migrate(database, "45");

        assertEquals("45", schemaVersion(database));
        assertTrainingSchema(database);
    }

    private void assertTrainingSchema(String database) throws Exception {
        assertEquals(1, count(database, "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='training_sheet_staff'"));
        assertEquals(1, count(database, "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='training_sheet_staff' "
                + "AND INDEX_NAME='uk_training_sheet_staff' AND NON_UNIQUE=0"));
        assertEquals(1, count(database, "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='training_sheet_staff' "
                + "AND INDEX_NAME='idx_training_sheet_staff_staff_sheet'"));
        assertTrue(checkClause(database, "file_info", "chk_file_biz_type").contains("TRAINING_ATTACHMENT"));
        assertTrue(checkClause(database, "file_ref", "chk_file_ref_biz").contains("TRAINING_PROJECT"));
    }

    private String checkClause(String database, String table, String constraint) throws Exception {
        try (Connection connection = connection(database); PreparedStatement statement = connection.prepareStatement(
                "SELECT cc.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS cc "
                        + "JOIN information_schema.TABLE_CONSTRAINTS tc "
                        + "ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME "
                        + "WHERE tc.TABLE_SCHEMA=? AND tc.TABLE_NAME=? AND tc.CONSTRAINT_NAME=?")) {
            statement.setString(1, database);
            statement.setString(2, table);
            statement.setString(3, constraint);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String createDatabase() throws Exception {
        String database = "metrics_v45_it_" + Long.toString(System.nanoTime(), 36);
        try (Connection connection = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        databases.add(database);
        return database;
    }

    private void migrate(String database, String target) throws Exception {
        Path temporaryMigrations = Files.createTempDirectory("metrics-v45-migrations");
        try {
            try (Stream<Path> scripts = Files.list(Path.of("src/main/resources/db/migration"))) {
                for (Path script : scripts.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                    String sql = Files.readString(script)
                            .replace("CREATE DATABASE IF NOT EXISTS metrics_db",
                                    "CREATE DATABASE IF NOT EXISTS `" + database + "`")
                            .replace("USE metrics_db;", "USE `" + database + "`;");
                    Files.writeString(temporaryMigrations.resolve(script.getFileName().toString()), sql);
                }
            }
            FluentConfiguration configuration = Flyway.configure()
                    .dataSource(serverUrl() + database + OPTIONS, USER, PASSWORD)
                    .locations("filesystem:" + temporaryMigrations.toString().replace('\\', '/'))
                    .target(target);
            configuration.load().migrate();
        } finally {
            try (Stream<Path> paths = Files.walk(temporaryMigrations)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private String schemaVersion(String database) throws Exception {
        try (Connection connection = connection(database); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version FROM flyway_schema_history "
                     + "WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private int count(String database, String sql) throws Exception {
        try (Connection connection = connection(database); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private static Connection connection(String database) throws Exception {
        return DriverManager.getConnection(serverUrl() + database + OPTIONS, USER, PASSWORD);
    }

    private static String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/";
    }
}
