package no.fellesstudentsystem.graphitron;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstractions for functionality that is used across multiple test classes.
 */
public abstract class GeneratorTest {
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

    public GeneratorTest(String testSubpath) {
        sourceTestPath = SRC_ROOT + "/" + testSubpath + "/";
        subpathSchema = SRC_ROOT + "/" + testSubpath + "/" + COMMON_SCHEMA_NAME;
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
    protected ProcessedSchema getProcessedSchema(String schemaParentFolder) {
        GeneratorConfig.setSchemaFiles(SRC_COMMON_SCHEMA, SRC_DIRECTIVES, subpathSchema, sourceTestPath + schemaParentFolder + "/schema.graphqls");

        var processedSchema = GraphQLGenerator.getProcessedSchema();
        processedSchema.validate(true);
        return processedSchema;
    }

    protected void assertGeneratedContentMatches(String schemaFolder, String expectedOutputFolder) {
        assertGeneratedContentMatches(sourceTestPath + expectedOutputFolder, generateFiles(schemaFolder));
    }

    protected void assertGeneratedContentMatches(String resourceRootFolder) {
        assertGeneratedContentMatches(resourceRootFolder, resourceRootFolder);
    }

    private void setProperties(List<ExternalReference> references, List<GlobalTransform> globalTransforms, List<Extension> extendedClasses) {
        GeneratorConfig.setProperties(
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                references,
                Set.of(
                    "no.fellesstudentsystem.graphitron.conditions",
                    "no.fellesstudentsystem.graphitron.enums",
                    "no.fellesstudentsystem.graphitron.records",
                    "no.fellesstudentsystem.graphitron.services"
                ),
                globalTransforms,
                extendedClasses
        );
    }

    @BeforeEach
    public void setup() {
        setProperties(List.of(), List.of(), List.of());
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear(); // To prevent any config from remaining when running multiple tests.
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
