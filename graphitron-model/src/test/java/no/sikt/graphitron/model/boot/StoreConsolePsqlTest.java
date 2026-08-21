package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Every case runs psql with its own {@code HOME} and {@code PSQLRC}, so the machine's real
 * configuration cannot decide the outcome in either direction: a developer's startup file must not
 * fail this test, and must not silently carry it either.
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
    void thePrintedCommandRunsVerbatimAndReturnsRows(@TempDir Path home) throws Exception {
        assumeTrue(psqlPresent(), "psql is not on the PATH");
        try (var console = STORE.handle().console(0)) {
            // The relation is a view, which is what most of the fact schema is, and the count comes
            // off the DDL's own seeded rows rather than anything this test wrote.
            var run = run(console.connectCommand()
                + " -t -A -c \"select count(*) from meta_family\"", home, "");
            assertThat(run.exit())
                .as("the printed connect command runs as printed. stderr: %s", run.error())
                .isZero();
            assertThat(run.output().strip())
                .as("and its whole output is the answer. stderr: %s", run.error())
                .containsOnlyDigits();
            assertThat(Integer.parseInt(run.output().strip()))
                .as("which reads the store's rows through the link")
                .isPositive();
        }
    }

    /**
     * The printed line has to work on a machine whose owner has configured psql, and a startup file
     * written for a real PostgreSQL server is full of statements this server does not have. That is
     * why the command carries {@code -X}: without it the errors below land ahead of every answer,
     * and the developer's first impression of the console is a wall of red.
     */
    @Test
    void aPostgresOnlyStartupFileCannotDisturbThePrintedCommand(@TempDir Path home) throws Exception {
        assumeTrue(psqlPresent(), "psql is not on the PATH");
        Path psqlrc = home.resolve("psqlrc");
        Files.writeString(psqlrc, """
            \\set QUIET 1
            set application_name to graphitron_test
            set bytea_output to escape
            \\timing on
            """, StandardCharsets.UTF_8);
        try (var console = STORE.handle().console(0)) {
            var run = run(console.connectCommand()
                + " -t -A -c \"select count(*) from meta_family\"", home, psqlrc.toString());
            assertThat(run.exit())
                .as("a startup file this server cannot execute must not reach it. stderr: %s",
                    run.error())
                .isZero();
            assertThat(run.output().strip())
                .as("and nothing it would have printed reaches the answer. stderr: %s", run.error())
                .containsOnlyDigits();
            assertThat(run.error())
                .as("nor the error stream, the whole file having been skipped")
                .doesNotContain("application_name");
        }
    }

    @Test
    void aWrongPasswordIsRefusedAtConnect(@TempDir Path home) throws Exception {
        assumeTrue(psqlPresent(), "psql is not on the PATH");
        try (var console = STORE.handle().console(0)) {
            var run = run("PGPASSWORD=not-the-password psql -X -w -h " + console.host()
                + " -p " + console.port() + " -U " + console.user() + " -d " + console.database()
                + " -t -A -c \"select 1\"", home, "");
            assertThat(run.exit())
                .as("the fixed password is a credential the server checks, not decoration. "
                    + "stderr: %s", run.error())
                .isNotZero();
        }
    }

    /**
     * One run's outcome, with the streams kept apart. Merging them is what let a developer's startup
     * file fail this test: psql writes its complaints to standard error and the answer to standard
     * output, so a case that reads one stream reads exactly the answer, and the other is context for
     * the failure message.
     */
    private record Run(int exit, String output, String error) {}

    /**
     * Runs {@code command} with psql's configuration pinned: {@code HOME} at a temporary directory
     * and {@code PSQLRC} at whatever the case wants, empty meaning no startup file at all. Standard
     * error goes to a file rather than a second pipe, which cannot deadlock against the read of
     * standard output however much psql writes.
     */
    private static Run run(String command, Path home, String psqlrc)
        throws IOException, InterruptedException {
        File errors = Files.createTempFile(home, "psql-stderr", ".txt").toFile();
        var builder = new ProcessBuilder("bash", "-c", command);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PSQLRC", psqlrc);
        builder.redirectError(errors);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .as("psql did not finish within %d seconds", TIMEOUT_SECONDS)
            .isTrue();
        return new Run(process.exitValue(), output,
            Files.readString(errors.toPath(), StandardCharsets.UTF_8));
    }

    private static boolean psqlPresent() {
        var builder = new ProcessBuilder("bash", "-c", "command -v psql");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
