package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The protocol pin: the command the dev session prints is run, as printed, by a real client.
 *
 * <p>Running the printed line is the whole value here. Every other claim about the console can be
 * asserted in-process, and a test that reassembled the command from the handle's fields would pass
 * while the line a developer actually copies was wrong: with an ephemeral port, the log is the only
 * place that port exists.
 *
 * <p>{@code psql} rather than a driver, and that is not a preference. pgjdbc cannot connect to H2's
 * PostgreSQL server at all: its startup queries include {@code SET extra_float_digits = 2}, which
 * H2 rejects as a syntax error, and no combination of {@code assumeMinServerVersion},
 * {@code preferQueryMode} or {@code options} gets past it. Swapping this shell-out for a JDBC
 * connection therefore proves the console broken when it is not.
 *
 * <p>Skipped where {@code psql} is not installed, so a contributor without it still gets a green
 * build; CI installs it for the execution tier and so runs this.
 */
class StoreConsolePsqlTest {

    /** Long enough that a loaded machine is not the reason a case fails, short enough to fail. */
    private static final int TIMEOUT_SECONDS = 30;

    /** One store for the class, a boot being the fact schema's two thousand statements. */
    @RegisterExtension
    static final FactStores.ClassStore STORE = FactStores.perClass();

    @Test
    void thePrintedCommandRunsVerbatimAndReturnsRows() throws Exception {
        assumeTrue(psqlPresent(), "psql is not on the PATH");
        try (var console = STORE.handle().console(0)) {
            // The relation is a view, which is what most of the fact schema is, and the count comes
            // off the DDL's own seeded rows rather than anything this test wrote.
            var run = run(console.connectCommand() + " -w -t -A -c \"select count(*) from meta_family\"");
            assertThat(run.exit())
                .as("the printed connect command runs as printed. Output: %s", run.output())
                .isZero();
            assertThat(run.output().strip()).containsOnlyDigits();
            assertThat(Integer.parseInt(run.output().strip()))
                .as("and reads the store's rows through the link")
                .isPositive();
        }
    }

    @Test
    void aWrongPasswordIsRefusedAtConnect() throws Exception {
        assumeTrue(psqlPresent(), "psql is not on the PATH");
        try (var console = STORE.handle().console(0)) {
            var run = run("PGPASSWORD=not-the-password psql -w -h " + console.host()
                + " -p " + console.port() + " -U " + console.user() + " -d " + console.database()
                + " -t -A -c \"select 1\"");
            assertThat(run.exit())
                .as("the fixed password is a credential the server checks, not decoration. "
                    + "Output: %s", run.output())
                .isNotZero();
        }
    }

    private record Run(int exit, String output) {}

    private static Run run(String command) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("bash", "-c", command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .as("psql did not finish within %d seconds", TIMEOUT_SECONDS)
            .isTrue();
        return new Run(process.exitValue(), output);
    }

    private static boolean psqlPresent() {
        try {
            return run("command -v psql").exit() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
