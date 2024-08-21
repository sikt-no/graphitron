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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.MultipleFailuresError;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Abstractions for functionality that is used across multiple test classes.
 */
public abstract class GeneratorTest {
    private final String sourceTestPath;
    private final Set<SchemaComponent> components;
    protected final boolean checkProcessedSchemaDefault;
    protected ListAppender<ILoggingEvent> logWatcher;
    private final Set<ExternalReference> references;
    private final Set<GlobalTransform> globalTransforms;
    private final List<Extension> extendedClasses;

    public GeneratorTest() {
        sourceTestPath = SRC_ROOT + "/" + getSubpath() + "/";
        this.checkProcessedSchemaDefault = getCheckProcessedSchemaDefault();
        this.components = getComponents();
        this.references = getExternalReferences();
        this.globalTransforms = getGlobalTransforms();
        this.extendedClasses = getExtendedClasses();
    }

    protected Map<String, List<String>> generateFiles(String schemaParentFolder) {
        return generateFiles(schemaParentFolder, Set.of());
    }

    protected Map<String, List<String>> generateFiles(String schemaParentFolder, Set<SchemaComponent> extraComponents) {
        return GraphQLGenerator.generateAsStrings(makeGenerators(getProcessedSchema(schemaParentFolder, extraComponents)));
    }

    public static void assertGeneratedContentMatches(String expectedOutputFolder, Map<String, List<String>> generatedFiles) {
        var path = Paths.get(expectedOutputFolder + "/" + EXPECTED_OUTPUT_NAME);
        if (!Files.exists(path)) {
            assertThat(generatedFiles).isEmpty();
            return;
        }

        var expectedFileNames = new HashSet<String>();
        var assertList = new ArrayList<Executable>();
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path expectedOutputFile, BasicFileAttributes attrs) {
                    String expectedFileName = expectedOutputFile.getFileName().toString().replace(".java", "");
                    expectedFileNames.add(expectedFileName);
                    var expectedFile = readFileAsStrings(expectedOutputFile);
                    if (!generatedFiles.containsKey(expectedFileName)) {
                        return FileVisitResult.CONTINUE;
                    }

                    var generatedFile = generatedFiles.get(expectedFileName);
                    var expected = formatExpectedFile(expectedFile);
                    assertList.add(
                            () -> assertThat(formatGeneratedFile(generatedFile))
                                    .withFailMessage(
                                            "\u001B[33;1mExpected the generated code:\u001B[0;35m\n%s\n\n\u001B[33;1mto be equivalent with excluding imports:\u001B[0;35m\n%s\n\u001B[0m",
                                            String.join("\n", generatedFile),
                                            expected
                                    )
                                    .isEqualToIgnoringWhitespace(expected)
                    );

                    var expectedFileImports = asImportList(expectedFile);
                    var generatedFileImports = asImportList(generatedFile);
                    assertList.add(
                            () -> assertThat(simplifyImports(generatedFileImports))
                                    .withFailMessage(
                                            "\u001B[33;1mExpected the generated imports:\u001B[0;35m\n%s\n\n\u001B[33;1mto be equivalent with:\u001B[0;35m\n%s\n\u001B[0m",
                                            String.join("\n", generatedFileImports),
                                            String.join("\n", expectedFileImports)
                                    )
                                    .containsExactlyInAnyOrderElementsOf(simplifyImports(expectedFileImports)) // Allows us to ignore import order.
                    );

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        assertList.add(() -> assertThat(generatedFiles.keySet()).containsExactlyInAnyOrderElementsOf(expectedFileNames));
        assertAllWithReducedStackTrace(assertList);
    }

    private static String formatGeneratedFile(List<String> generatedFile) {
        return generatedFile
                .stream()
                .filter(it -> !it.startsWith("import") && !it.startsWith("package"))
                .collect(Collectors.joining("\n"));
    }

    private static String formatExpectedFile(List<String> expectedFile) {
        var expectedFileNoImports = expectedFile
                .stream()
                .filter(it -> !it.startsWith("import") && !it.startsWith("package"))
                .collect(Collectors.toList());
        var trimmedExpectedFile = new ArrayList<String>();
        var isText = false;
        for (var line : expectedFileNoImports) {
            if (!line.isEmpty()) {
                isText = true;
            }
            if (isText) {
                trimmedExpectedFile.add(line);
            }
        }
        return String.join("\n", trimmedExpectedFile);
    }

    private static void assertAllWithReducedStackTrace(ArrayList<Executable> assertList) {
        try {
            assertAll(assertList);
        } catch (AssertionError e) {
            throw new MultipleFailuresError(e.getMessage(), List.of());
        }
    }

    private static List<String> simplifyImports(List<String> lines) {
        return lines.stream().map(it -> it.replaceFirst("import ", "")).collect(Collectors.toList());
    }

    private static List<String> asImportList(List<String> expectedFile) {
        return expectedFile
                .stream()
                .filter(it -> it.startsWith("import"))
                .collect(Collectors.toList());
    }

    public ProcessedSchema getProcessedSchema(String schemaParentFolder, SchemaComponent... extraComponents) {
        return getProcessedSchema(schemaParentFolder, Set.of(extraComponents));
    }

    public ProcessedSchema getProcessedSchema(String schemaParentFolder, Set<SchemaComponent> extraComponents) {
        return TestConfiguration.getProcessedSchema(sourceTestPath + schemaParentFolder, mergeComponentsAndSetConfig(extraComponents), checkProcessedSchemaDefault);
    }

    protected Set<String> mergeComponentsAndSetConfig(Set<SchemaComponent> extraComponents) {
        var allComponents = Stream.concat(components.stream(), extraComponents.stream()).collect(Collectors.toSet());
        var allPaths = allComponents.stream().flatMap(it -> it.getPaths().stream()).collect(Collectors.toSet());
        var allReferences = Stream.concat(references.stream(), allComponents.stream().flatMap(it -> makeReferences(it.getReferences()).stream())).collect(Collectors.toSet());

        setProperties(new ArrayList<>(allReferences), new ArrayList<>(globalTransforms), extendedClasses);
        return allPaths;
    }

    protected void assertGeneratedContentMatches(String resourceRootFolder) {
        assertGeneratedContentMatches(sourceTestPath + resourceRootFolder, generateFiles(resourceRootFolder));
    }

    protected void assertGeneratedContentMatches(String resourceRootFolder, SchemaComponent... extraComponents) {
        assertGeneratedContentMatches(sourceTestPath + resourceRootFolder, generateFiles(resourceRootFolder, Set.of(extraComponents)));
    }


    protected static void contains(Map<String, List<String>> generatedFiles, String... expected) {
        if (expected.length < 1) {
            return;
        }
        var allFileContent = processFileContent(generatedFiles);
        var asserts = new ArrayList<Executable>();
        Stream.of(expected).forEach(it ->
                asserts.add(() -> assertThat(allFileContent)
                        .withFailMessage("\u001B[33;1mExpected to find\u001B[0;35m\n%s\n\u001B[33;1min:\u001B[0;35m\n%s\n\u001B[0m", it, allFileContent)
                        .containsIgnoringWhitespaces(it)
                ));
        assertAllWithReducedStackTrace(asserts);
    }

    private static String processFileContent(Map<String, List<String>> generatedFiles) {
        return generatedFiles
                .values()
                .stream()
                .flatMap(Collection::stream)
                .filter(it -> !it.isEmpty() && !it.startsWith("import") && !it.startsWith("package"))
                .collect(Collectors.joining("\n")); // Add newlines for readability when the test fails.
    }

    protected void assertGeneratedContentContains(String resourceRootFolder, Set<SchemaComponent> extraComponents, String... expected) {
        contains(generateFiles(resourceRootFolder, extraComponents), expected);
    }

    protected void assertGeneratedContentContains(String resourceRootFolder, String... expected) {
        assertGeneratedContentContains(resourceRootFolder, Set.of(), expected);
    }


    // Avoid using these three methods if possible, they have worse performance than the ones above.
    protected static void doesNotContain(Map<String, List<String>> generatedFiles, String... expected) {
        if (expected.length < 1) {
            return;
        }
        var allFileContent = processFileContent(generatedFiles);
        var asserts = new ArrayList<Executable>();
        Stream.of(expected).forEach(it ->
                asserts.add(() -> assertThat(allFileContent)
                        .withFailMessage("\u001B[33;1mUnexpectedly found\u001B[0;35m\n%s\n\u001B[33;1min:\u001B[0;35m\n%s\n\u001B[0m", it, allFileContent)
                        .doesNotContain(it)
                ) // Note, does not ignore whitespaces. There is no method for that.
        );
        assertAllWithReducedStackTrace(asserts);
    }

    protected void resultDoesNotContain(String resourceRootFolder, Set<SchemaComponent> extraComponents, String... expected) {
        doesNotContain(generateFiles(resourceRootFolder, extraComponents), expected);
    }

    protected void resultDoesNotContain(String resourceRootFolder, String... expected) {
        resultDoesNotContain(resourceRootFolder, Set.of(), expected);
    }


    protected void assertFilesAreGenerated(String schemaFolder, String... expectedFiles) {
        assertThat(generateFiles(schemaFolder).keySet()).containsExactlyInAnyOrderElementsOf(Set.of(expectedFiles));
    }

    protected void assertFilesAreGenerated(String schemaFolder, Set<SchemaComponent> extraComponents, String... expectedFiles) {
        assertThat(generateFiles(schemaFolder, extraComponents).keySet()).containsExactlyInAnyOrderElementsOf(Set.of(expectedFiles));
    }

    @BeforeEach
    public void setup() {
        ListAppender<ILoggingEvent> logWatch = new ListAppender<>();
        logWatch.start();
        ((Logger) LoggerFactory.getLogger(GraphQLGenerator.class)).addAppender(logWatch);
        this.logWatcher = logWatch;
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

    protected Set<ExternalReference> makeReferences(ReferencedEntry... entries) {
        return makeReferences(Set.of(entries));
    }

    protected Set<ExternalReference> makeReferences(Set<ReferencedEntry> entries) {
        return entries.stream().map(ReferencedEntry::get).collect(Collectors.toSet());
    }

    protected Set<SchemaComponent> makeComponents(SchemaComponent... entries) {
        return Stream.of(entries).collect(Collectors.toSet());
    }

    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of();
    }

    protected String getSubpath() {
        return "";
    }

    protected boolean getCheckProcessedSchemaDefault() {
        return true;
    }

    protected Set<SchemaComponent> getComponents() {
        return Set.of();
    }

    protected Set<ExternalReference> getExternalReferences() {
        return Set.of();
    }

    protected Set<GlobalTransform> getGlobalTransforms() {
        return Set.of();
    }

    protected List<Extension> getExtendedClasses() {
        return List.of();
    }
}
