package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.HashMap;
import java.util.Map;

/**
 * Wires a Postgres datasource for the Quarkus smoke test before the application boots.
 *
 * <p>If the surrounding build runs under {@code -Plocal-db}, surefire passes
 * {@code -Dtest.db.url=...} and we forward those as Quarkus datasource system properties so
 * the booting app picks them up. Otherwise, start a Testcontainers Postgres with
 * {@code init.sql} from {@code graphitron-sakila-db}; this matches the behaviour of the
 * in-process query-to-database tests and avoids spinning a second Postgres for the smoke
 * check.
 */
public class SmokeTestPostgresResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> container;

    @Override
    public Map<String, String> start() {
        Map<String, String> overrides = new HashMap<>();
        String localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            overrides.put("quarkus.datasource.jdbc.url", localUrl);
            overrides.put("quarkus.datasource.username",
                System.getProperty("test.db.username", "postgres"));
            overrides.put("quarkus.datasource.password",
                System.getProperty("test.db.password", "postgres"));
        } else {
            container = new PostgreSQLContainer<>("postgres:18-alpine").withInitScript("init.sql");
            container.start();
            overrides.put("quarkus.datasource.jdbc.url", container.getJdbcUrl());
            overrides.put("quarkus.datasource.username", container.getUsername());
            overrides.put("quarkus.datasource.password", container.getPassword());
        }
        return overrides;
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
