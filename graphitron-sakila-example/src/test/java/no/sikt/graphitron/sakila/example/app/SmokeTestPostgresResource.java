package no.sikt.graphitron.sakila.example.app;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;

import java.util.HashMap;
import java.util.Map;

/**
 * Wires the shared Sakila test database into the Quarkus smoke test before the application boots,
 * forwarding {@link SakilaDatabase}'s connection as Quarkus datasource properties.
 */
public class SmokeTestPostgresResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("quarkus.datasource.jdbc.url", SakilaDatabase.url());
        overrides.put("quarkus.datasource.username", SakilaDatabase.user());
        overrides.put("quarkus.datasource.password", SakilaDatabase.password());
        return overrides;
    }

    @Override
    public void stop() {
        // The database belongs to SakilaDatabase and lives as long as the JVM.
    }
}
