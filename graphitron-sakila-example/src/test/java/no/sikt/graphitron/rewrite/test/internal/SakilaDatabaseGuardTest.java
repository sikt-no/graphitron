package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard: an execution test gets its database from {@code SakilaDatabase} and from
 * nowhere else. Provisioning had been copied into every class, each copy a chance to drift; the
 * guard keeps the one home the only home. It catches a container started, a {@code test.db.*}
 * property read, a {@code DriverManager} connection opened or a {@code jdbc:postgresql:} URL
 * written anywhere else in the module's test sources.
 *
 * <p>It does not, and cannot, tell a whole-table count from a filtered read: the rule that a test
 * reads only rows it can name and writes only rows it created is held by review and by
 * {@code testing.adoc}.
 */
@UnitTier
class SakilaDatabaseGuardTest {

    private static final Path HOME =
        Path.of("src/test/java/no/sikt/graphitron/rewrite/test/SakilaDatabase.java");

    /** The guard's own negative case plants what it forbids, so it is the one other file out of scan. */
    private static final Path SELF =
        Path.of("src/test/java/no/sikt/graphitron/rewrite/test/internal/SakilaDatabaseGuardTest.java");

    /** A floor against a walk that reached nothing. */
    private static final int MIN_SCANNED_TEST_FILES = 100;

    private static final List<Pattern> FORBIDDEN = List.of(
        Pattern.compile("new\\s+PostgreSQLContainer"),
        Pattern.compile("getProperty\\(\\s*\"test\\.db\\."),
        Pattern.compile("DriverManager\\s*\\.\\s*getConnection"),
        Pattern.compile("jdbc:postgresql:"));

    @Test
    void noTestProvisionsADatabaseOutsideTheHarness() throws IOException {
        Path root = Path.of("src/test/java");
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (file.equals(HOME) || file.equals(SELF)) continue;
                var hits = violationsIn(Files.readString(file));
                if (!hits.isEmpty()) violations.add(file + " " + hits);
            }
        }

        assertThat(scanned)
            .as("the walk reached almost no test source, so the guard would pass vacuously")
            .isGreaterThan(MIN_SCANNED_TEST_FILES);
        assertThat(violations)
            .as("a test takes its database from SakilaDatabase (url(), user(), password(), dsl(), "
                + "connect(...), tenantUrl(...)) instead of provisioning one")
            .isEmpty();
    }

    @Test
    void theHomeStillProvisionsWhatTheGuardForbidsElsewhere() throws IOException {
        var home = Files.readString(HOME);
        assertThat(FORBIDDEN)
            .as("a pattern the home no longer uses is a stale rule: drop it, or the guard is "
                + "policing something nobody provisions")
            .allSatisfy(p -> assertThat(p.matcher(home).find()).as(p.pattern()).isTrue());
    }

    @Test
    void aPlantedContainerAndAPlantedPropertyReadAreCaught() {
        assertThat(violationsIn("var c = new PostgreSQLContainer(\"postgres:18-alpine\");"))
            .isNotEmpty();
        assertThat(violationsIn("var u = System.getProperty(\"test.db.url\");")).isNotEmpty();
        assertThat(violationsIn("var c = DriverManager.getConnection(url, u, p);")).isNotEmpty();
        assertThat(violationsIn("String u = \"jdbc:postgresql://localhost/x\";")).isNotEmpty();
        assertThat(violationsIn("var d = SakilaDatabase.dsl();")).isEmpty();
    }

    private static List<String> violationsIn(String source) {
        return FORBIDDEN.stream()
            .filter(p -> p.matcher(source).find())
            .map(Pattern::pattern)
            .toList();
    }
}
