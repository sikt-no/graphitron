package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.mojo.WatcherMojo;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.TestCommon.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WatcherMojoTest {
    private static final String
            SRC_TEST_RESOURCES_PATH = "mojo",
            SRC_TEST_RESOURCES = SRC_ROOT + "/" + SRC_TEST_RESOURCES_PATH + "/",
            SCHEMA_NAME = "schema.graphql",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.jooq.generated.testdata";

    private final static long WAIT_DELAY_MILLIS = 50;

    @TempDir
    protected Path tempCodeOutputDirectory;

    @TempDir
    protected Path tempSchemaDirectory;

    @Test
    @Timeout(15) // Wait for a while for the thread, but if nothing happens, terminate the test.
    @Disabled // Test is disabled due to instability with file writing on non-Linux systems. Can still be run manually when working on this.
    public void regenerate_ShouldRunWhenSchemaChanges() throws IOException {
        var testPath = SRC_TEST_RESOURCES + "regenerate/";

        // Create temporary copy of the schema file. This is to prevent edited test files from remaining changed after test runs.
        var schemaCopyPath = Files.copy(Path.of(testPath + SCHEMA_NAME), Path.of(tempSchemaDirectory + "/" + SCHEMA_NAME));

        var watcherThread = initiateWatcherThread(getMojo(schemaCopyPath.toString(), tempCodeOutputDirectory));

        waitForThread(watcherThread); // Wait until the thread finishes the initial writing of the files.
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder( testPath + "before", readGeneratedFiles(tempCodeOutputDirectory));

        // Edit the graph file by removing a field.
        var newFileLines = readFileAsStrings(schemaCopyPath)
                .stream()
                .filter(it -> !it.strip().equals("length: Int"))
                .collect(Collectors.toList());
        Files.write(schemaCopyPath, newFileLines);

        waitForThread(watcherThread); // Wait until the thread notices the changes and regenerates the code.
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(testPath + "after", readGeneratedFiles(tempCodeOutputDirectory));

        assertFalse(watcherThread.isInterrupted()); // Thread shouldn't crash while watching files.
        assertTrue(watcherThread.isAlive());
        watcherThread.interrupt(); // Thread does not end of its own.
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear(); // To prevent any config from remaining when running multiple tests.
    }

    @NotNull
    private Thread initiateWatcherThread(WatcherMojo mojo) {
        // Set the thread to check for changes much more often than usual so test runs faster.
        var watcherThread = new Thread(() -> mojo.executeWithDelay(WAIT_DELAY_MILLIS));
        watcherThread.start();
        return watcherThread;
    }

    @NotNull
    private WatcherMojo getMojo(String targetSchemaFile, Path tempDir) {
        var mojo = new WatcherMojo();
        mojo.setSchemaFiles(Set.of(SRC_DIRECTIVES, targetSchemaFile));
        mojo.setTopPackage(DEFAULT_SYSTEM_PACKAGE);
        mojo.setOutputPath(tempDir.toString());
        mojo.setOutputPackage(DEFAULT_OUTPUT_PACKAGE);
        mojo.setGeneratedSchemaCodePackage(DEFAULT_OUTPUT_PACKAGE);
        mojo.setJooqGeneratedPackage(DEFAULT_JOOQ_PACKAGE);
        return mojo;
    }

    private static void waitForThread(Thread thread) {
        try {
            synchronized (thread) {
                thread.wait();
            }
        } catch (InterruptedException e) {
            thread.interrupt();
            System.exit(0);
        }
    }
}
