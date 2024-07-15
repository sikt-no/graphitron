package no.fellesstudentsystem.graphitron_newtestorder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import no.fellesstudentsystem.graphitron.configuration.Extension;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.GlobalTransform;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
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
import java.util.*;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstractions for functionality that is used across multiple test classes.
 */
public abstract class GeneratorTest {
    @TempDir
    protected Path tempOutputDirectory;

    private final String sourceTestPath, subpathSchema;
    protected final boolean checkProcessedSchemaDefault;
    protected ListAppender<ILoggingEvent> logWatcher;
    private final Set<ExternalReference> references;
    private final Set<GlobalTransform> globalTransforms;
    private final List<Extension> extendedClasses;

    public GeneratorTest(String testSubpath) {
        this(testSubpath, Set.of());
    }

    public GeneratorTest(String testSubpath, Set<ExternalReference> references) {
        this(testSubpath, references, true);
    }

    public GeneratorTest(String testSubpath, Set<ExternalReference> references, boolean checkProcessedSchemaDefault) {
        this(testSubpath, references, Set.of(), List.of(), checkProcessedSchemaDefault);
    }

    public GeneratorTest(String testSubpath, Set<ExternalReference> references, Set<GlobalTransform> globalTransforms, List<Extension> extendedClasses) {
        this(testSubpath, references, globalTransforms, extendedClasses, true);
    }

    public GeneratorTest(String testSubpath, Set<ExternalReference> references, Set<GlobalTransform> globalTransforms, List<Extension> extendedClasses, boolean checkProcessedSchemaDefault) {
        sourceTestPath = SRC_ROOT + "/" + testSubpath + "/";
        subpathSchema = sourceTestPath + COMMON_SCHEMA_NAME;
        this.checkProcessedSchemaDefault = checkProcessedSchemaDefault;
        this.references = references;
        this.globalTransforms = globalTransforms;
        this.extendedClasses = extendedClasses;
    }

    public String getSourceTestPath() {
        return sourceTestPath;
    }

    protected Map<String, List<String>> generateFiles(String schemaParentFolder) {
        GraphQLGenerator.generate(makeGenerators(getProcessedSchema(schemaParentFolder)));
        return readGeneratedFiles(tempOutputDirectory);
    }

    public static Map<String, List<String>> readGeneratedFiles(Path outputDirectory) {
        Map<String, List<String>> generatedFiles = new HashMap<>();
        try {
            Files.walkFileTree(outputDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!Files.isDirectory(file)) {
                        generatedFiles.put(file.getFileName().toString(), readFileAsStrings(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return generatedFiles;
    }

    public static void assertGeneratedContentMatches(String expectedOutputFolder, Map<String, List<String>> generatedFiles) {
        var expectedFileNames = new HashSet<String>();

        try {
            Files.walkFileTree(Paths.get(expectedOutputFolder + "/" + EXPECTED_OUTPUT_NAME), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path expectedOutputFile, BasicFileAttributes attrs) {
                    String expectedFileName = expectedOutputFile.getFileName().toString();
                    expectedFileNames.add(expectedFileName);
                    var expectedFile = readFileAsStrings(expectedOutputFile);
                    if (!generatedFiles.containsKey(expectedFileName)) {
                        return FileVisitResult.CONTINUE;
                    }

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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        assertThat(generatedFiles.keySet()).containsExactlyInAnyOrderElementsOf(expectedFileNames);
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
    public ProcessedSchema getProcessedSchema(String schemaParentFolder) {
        return TestConfiguration.getProcessedSchema(sourceTestPath + schemaParentFolder, subpathSchema, checkProcessedSchemaDefault);
    }

    protected void assertGeneratedContentMatches(String schemaFolder, String expectedOutputFolder) {
        assertGeneratedContentMatches(sourceTestPath + expectedOutputFolder, generateFiles(schemaFolder));
    }

    protected void assertGeneratedContentMatches(String resourceRootFolder) {
        assertGeneratedContentMatches(resourceRootFolder, resourceRootFolder);
    }

    protected void assertFilesAreGenerated(Set<String> expectedFiles, String schemaFolder) {
        assertThat(generateFiles(schemaFolder).keySet()).containsExactlyInAnyOrderElementsOf(expectedFiles);
    }

    @BeforeEach
    public void setup() {
        ListAppender<ILoggingEvent> logWatch = new ListAppender<>();
        logWatch.start();
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).addAppender(logWatch);
        this.logWatcher = logWatch;

        setProperties(new ArrayList<>(references), new ArrayList<>(globalTransforms), extendedClasses, tempOutputDirectory.toString());
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear(); // To prevent any config from remaining when running multiple tests.
    }

    @AfterEach
    public void teardown() {
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).detachAndStopAllAppenders();
    }

    protected static List<String> readFileAsStrings(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read file: " + file.toFile().getPath(), e);
        }
    }

    protected abstract List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema);
}
