package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class TestCommon {
    public static final String
            DIRECTIVES_NAME = "default.graphqls",
            DEFAULT_OUTPUT_PACKAGE = "fake.code.example.package",
            SRC_ROOT = "src/test/resources",
            SRC_DIRECTIVES = SRC_ROOT + "/" + DIRECTIVES_NAME;
    @TempDir
    protected Path tempOutputDirectory;
    private final String sourceTestPath, subpathDirectives;
    protected ListAppender<ILoggingEvent> logWatcher;

    public TestCommon(String testSubpath) {
        subpathDirectives = SRC_ROOT + "/" + testSubpath + "/" + DIRECTIVES_NAME;
        sourceTestPath = SRC_ROOT + "/" + testSubpath + "/";
    }

    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        return generateFiles(schemaParentFolder, false);
    }

    protected Map<String, List<String>> generateFiles(String schemaParentFolder, boolean warnDirectives) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder, warnDirectives);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new FetchDBClassGenerator(processedSchema),
                new FetchResolverClassGenerator(processedSchema)
        );

        return generateFiles(generators);
    }

    public Map<String, List<String>> generateFiles(List<ClassGenerator<? extends GenerationTarget>> generators) throws IOException {
        GraphQLGenerator.generate(generators);
        Map<String, List<String>> generatedFiles = new HashMap<>();
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

    protected static void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String expectedOutputFolder, Map<String, List<String>> generatedFiles) throws IOException {
        var expectedFileNames = new HashSet<String>();

        Files.walkFileTree(Paths.get(expectedOutputFolder + "/expectedOutput"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path expectedOutputFile, BasicFileAttributes attrs) throws IOException {
                String expectedFileName = expectedOutputFile.getFileName().toString().replace(".txt", "");
                expectedFileNames.add(expectedFileName);
                var expectedFile = readFileAsString(expectedOutputFile);
                assertThat(generatedFiles.keySet()).contains(expectedFileName);
                var generatedFile = generatedFiles.get(expectedFileName);

                var expectedFileImports = asImportList(expectedFile);
                var generatedFileImports = asImportList(generatedFile);
                assertThat(generatedFileImports).containsExactlyInAnyOrderElementsOf(expectedFileImports); // Allows us to ignore import order.

                var expectedFileContent = expectedFile.stream().filter(it -> !it.startsWith("import")).collect(Collectors.joining("\n"));
                var generatedFileContent = generatedFile.stream().filter(it -> !it.startsWith("import")).collect(Collectors.joining("\n"));

                assertThat(generatedFileContent).isEqualToIgnoringWhitespace(expectedFileContent);
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(expectedFileNames).containsExactlyInAnyOrderElementsOf(generatedFiles.keySet());
    }

    @NotNull
    private static List<String> asImportList(List<String> expectedFile) {
        return expectedFile
                .stream()
                .filter(it -> it.startsWith("import"))
                .map(it -> it.replaceFirst("import ", ""))
                .collect(Collectors.toList());
    }

    @NotNull
    protected ProcessedSchema getProcessedSchema(String schemaParentFolder, boolean warnDirectives) {
        GeneratorConfig.setSchemaFiles(SRC_DIRECTIVES, subpathDirectives, sourceTestPath + schemaParentFolder + "/schema.graphqls");

        var processedSchema = GraphQLGenerator.getProcessedSchema(warnDirectives);
        processedSchema.validate();
        return processedSchema;
    }

    protected void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String schemaFolder, String expectedOutputFolder) throws IOException {
        var generatedFiles = generateFiles(schemaFolder);
        TestCommon.assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(sourceTestPath + expectedOutputFolder, generatedFiles);
    }

    protected void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String resourceRootFolder) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(resourceRootFolder, resourceRootFolder);
    }

    @BeforeEach
    public void setup() {
        ListAppender<ILoggingEvent> logWatch = new ListAppender<>();
        logWatch.start();
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).addAppender(logWatch);
        this.logWatcher = logWatch;

        setProperties();
    }

    abstract protected void setProperties();

    @AfterEach
    public void teardown() {
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).detachAndStopAllAppenders();
    }

    protected static List<String> readFileAsString(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }
}
