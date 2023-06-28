package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TestCommon {
    public static final String
            DEFAULT_NAME = "default.graphqls",
            SRC_ROOT = "src/test/resources/",
            SRC_DEFAULT = SRC_ROOT + DEFAULT_NAME;

    private final Path tempOutputDirectory;

    private List<ClassGenerator<? extends GenerationTarget>> generators;

    public TestCommon(String schemaParentFolder, String testSubpath, Path tempOutputDirectory) {
        this.tempOutputDirectory = tempOutputDirectory;
        var schemaFiles = SRC_DEFAULT + "," + SRC_ROOT + "/" + testSubpath + "/" + DEFAULT_NAME + "," + SRC_ROOT + "/" + testSubpath + "/" + schemaParentFolder + "/schema.graphqls";
        System.setProperty(GeneratorConfig.PROPERTY_SCHEMA_FILES, schemaFiles);
        System.setProperty(GeneratorConfig.PROPERTY_OUTPUT_DIRECTORY, tempOutputDirectory.toString());
    }

    public Map<String, String> generateFiles() throws IOException {
        GraphQLGenerator.generate(generators);
        Map<String, String> generatedFiles = new HashMap<>();
        Files.walkFileTree(tempOutputDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!Files.isDirectory(file)) {
                    generatedFiles.put(file.getFileName().toString(), readFileAsString(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return generatedFiles;
    }

    public static void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String expectedOutputFolder, Map<String, String> generatedFiles) throws IOException {
        var expectedFileNames = new HashSet<String>();

        Files.walkFileTree(Paths.get(expectedOutputFolder + "/expectedOutput"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path expectedOutputFile, BasicFileAttributes attrs) throws IOException {
                String expectedFileName = expectedOutputFile.getFileName().toString().replace(".txt", "");
                expectedFileNames.add(expectedFileName);
                String expectedFileContent = readFileAsString(expectedOutputFile);
                assertThat(generatedFiles.keySet()).contains(expectedFileName);

                String generatedFileContent = generatedFiles.get(expectedFileName);
                assertThat(generatedFileContent).isEqualToIgnoringWhitespace(expectedFileContent);
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(expectedFileNames).containsExactlyInAnyOrderElementsOf(generatedFiles.keySet());
    }

    public void setGenerators(List<ClassGenerator<? extends GenerationTarget>> generators) {
        this.generators = generators;
    }

    public static ListAppender<ILoggingEvent> setup() {
        ListAppender<ILoggingEvent> logWatcher = new ListAppender<>();
        logWatcher.start();
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).addAppender(logWatcher);
        return logWatcher;
    }

    public static void teardown() {
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).detachAndStopAllAppenders();
        System.clearProperty(GeneratorConfig.PROPERTY_SCHEMA_FILES);
    }

    public static String readFileAsString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
