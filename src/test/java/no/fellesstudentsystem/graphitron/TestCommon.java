package no.fellesstudentsystem.graphitron;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron.mojo.GraphQLGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
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

/**
 * Abstractions for functionality that is used across multiple test classes.
 */
public abstract class TestCommon {
    public static final String
            COMMON_SCHEMA_NAME = "default.graphqls",
            DEFAULT_OUTPUT_PACKAGE = "fake.code.generated",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.jooq.generated.testdata",
            SRC_ROOT = "src/test/resources",
            SRC_COMMON_SCHEMA = SRC_ROOT + "/" + COMMON_SCHEMA_NAME,
            SRC_DIRECTIVES = "src/main/resources/schema/directives.graphqls",
            EXPECTED_OUTPUT_NAME = "expectedOutput";

    @TempDir
    protected Path tempOutputDirectory;

    private final String sourceTestPath, subpathSchema;
    protected final boolean checkProcessedSchemaDefault;
    protected ListAppender<ILoggingEvent> logWatcher;

    public TestCommon(String testSubpath) {
        this(testSubpath, true);
    }

    public TestCommon(String testSubpath, boolean checkProcessedSchemaDefault) {
        subpathSchema = SRC_ROOT + "/" + testSubpath + "/" + COMMON_SCHEMA_NAME;
        sourceTestPath = SRC_ROOT + "/" + testSubpath + "/";
        this.checkProcessedSchemaDefault = checkProcessedSchemaDefault;
    }

    public String getSourceTestPath() {
        return sourceTestPath;
    }

    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new FetchDBClassGenerator(processedSchema),
                new FetchResolverClassGenerator(processedSchema)
        );

        return generateFiles(generators);
    }

    public Map<String, List<String>> generateFiles(List<ClassGenerator<? extends GenerationTarget>> generators) throws IOException {
        GraphQLGenerator.generate(generators);
        return readGeneratedFiles(tempOutputDirectory);
    }

    @NotNull
    public static Map<String, List<String>> readGeneratedFiles(Path outputDirectory) throws IOException {
        Map<String, List<String>> generatedFiles = new HashMap<>();
        Files.walkFileTree(outputDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!Files.isDirectory(file)) {
                    generatedFiles.put(file.getFileName().toString(), readFileAsStrings(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return generatedFiles;
    }

    public static void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String expectedOutputFolder, Map<String, List<String>> generatedFiles) throws IOException {
        var expectedFileNames = new HashSet<String>();

        Files.walkFileTree(Paths.get(expectedOutputFolder + "/" + EXPECTED_OUTPUT_NAME), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path expectedOutputFile, BasicFileAttributes attrs) throws IOException {
                String expectedFileName = expectedOutputFile.getFileName().toString();
                expectedFileNames.add(expectedFileName);
                var expectedFile = readFileAsStrings(expectedOutputFile);
                assertThat(generatedFiles.keySet()).contains(expectedFileName);
                var generatedFile = generatedFiles.get(expectedFileName);

                var expectedFileContent = expectedFile.stream().filter(it -> !it.startsWith("import")).collect(Collectors.joining("\n"));
                var generatedFileContent = generatedFile.stream().filter(it -> !it.startsWith("import")).collect(Collectors.joining("\n"));
                var generatedFileContentOutput = "\nGenerated file content:\n" + String.join("\n", generatedFile) + "\n";
                assertThat(generatedFileContent).as(() -> generatedFileContentOutput).isEqualToIgnoringWhitespace(expectedFileContent);

                var expectedFileImports = asImportList(expectedFile);
                var generatedFileImports = asImportList(generatedFile);
                assertThat(generatedFileImports)
                        .as(() -> generatedFileContentOutput)
                        .containsExactlyInAnyOrderElementsOf(expectedFileImports); // Allows us to ignore import order.

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
    protected ProcessedSchema getProcessedSchema(String schemaParentFolder) {
        return getProcessedSchema(schemaParentFolder, checkProcessedSchemaDefault);
    }

    @NotNull
    protected ProcessedSchema getProcessedSchema(String schemaParentFolder, boolean checkTypes) {
        GeneratorConfig.setSchemaFiles(SRC_COMMON_SCHEMA, SRC_DIRECTIVES, subpathSchema, sourceTestPath + schemaParentFolder + "/schema.graphqls");

        var processedSchema = GraphQLGenerator.getProcessedSchema();
        processedSchema.validate(checkTypes);
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

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear(); // To prevent any config from remaining when running multiple tests.
    }

    abstract protected void setProperties();

    @AfterEach
    public void teardown() {
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).detachAndStopAllAppenders();
    }

    protected static List<String> readFileAsStrings(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }
}
