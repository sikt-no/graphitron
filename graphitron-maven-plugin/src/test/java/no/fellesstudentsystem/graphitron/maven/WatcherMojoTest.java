package no.fellesstudentsystem.graphitron.maven;

import no.fellesstudentsystem.graphitron.mojo.WatcherMojo;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.maven.TestConfiguration.COMMON_TEST_SCHEMA_NAME;
import static no.fellesstudentsystem.graphitron.maven.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.fellesstudentsystem.graphitron.maven.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Watch functionality - Regenerate files when schema changes")
public class WatcherMojoTest extends GeneratorTest {
    private final static long WAIT_DELAY_MILLIS = 50;

    @TempDir
    protected Path tempCodeOutputDirectory;

    @TempDir
    protected Path tempSchemaDirectory;

    @Override
    protected String getSubpath() {
        return "mojo/regenerate";
    }

    @Test
    @Timeout(15) // Wait for a while for the thread, but if nothing happens, terminate the test.
    @Disabled("Test is disabled due to instability with file writing on non-Linux systems. Can still be run manually when working on this.")
    @DisplayName("Regenerate the queries when schema is edited")
    public void regenerate() throws IOException {
        var testPath = getSourceTestPath();

        // Create temporary copy of the schema file. This is to prevent edited test files from remaining changed after test runs.
        var schemaCopyPath = Files.copy(Path.of(testPath + COMMON_TEST_SCHEMA_NAME), Path.of(tempSchemaDirectory + "/" + COMMON_TEST_SCHEMA_NAME));

        var watcherThread = initiateWatcherThread(getMojo(schemaCopyPath.toString(), tempCodeOutputDirectory));

        waitForThread(watcherThread); // Wait until the thread finishes the initial writing of the files.
        assertGeneratedContentMatches( testPath + "before", readGeneratedFiles(tempCodeOutputDirectory));

        // Edit the graph file by removing a field.
        var newFileLines = readFileAsStrings(schemaCopyPath)
                .stream()
                .filter(it -> !it.strip().equals("film: Film"))
                .collect(Collectors.toList());
        Files.write(schemaCopyPath, newFileLines);

        waitForThread(watcherThread); // Wait until the thread notices the changes and regenerates the code.
        assertGeneratedContentMatches(testPath + "after", readGeneratedFiles(tempCodeOutputDirectory));

        assertFalse(watcherThread.isInterrupted()); // Thread shouldn't crash while watching files.
        assertTrue(watcherThread.isAlive());
        watcherThread.interrupt(); // Thread does not end of its own.
    }

    public static Map<String, List<String>> readGeneratedFiles(Path outputDirectory) {
        var generatedFiles = new HashMap<String, List<String>>();
        try {
            Files.walkFileTree(outputDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!Files.isDirectory(file)) {
                        generatedFiles.put(file.getFileName().toString().replace(".java", ""), readFileAsStrings(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return generatedFiles;
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
        var mojo = new WatcherMojo((schema) -> List.of(new QueryTypeFieldsWrappingMockGenerator(schema)));
        mojo.setSchemaFiles(Set.of(targetSchemaFile));
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
