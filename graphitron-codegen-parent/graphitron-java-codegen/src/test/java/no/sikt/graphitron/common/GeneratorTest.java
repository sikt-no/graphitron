package no.sikt.graphitron.common;

import no.sikt.graphitron.common.configuration.ReferencedEntry;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.configuration.Extension;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.validation.ValidationHandler;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;
import org.junit.ComparisonFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.regex.Pattern.DOTALL;
import static no.sikt.graphitron.common.configuration.TestConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Abstractions for functionality that is used across multiple test classes.
 */
public abstract class GeneratorTest {
    private final String sourceTestPath;
    private final Set<SchemaComponent> components;
    protected final boolean checkProcessedSchemaDefault;
    private final Set<ExternalReference> references;
    private final Set<GlobalTransform> globalTransforms;
    private final List<Extension> extendedClasses;

    private final static String
            MSG_CONTAIN_EXPECT = "Expected to find",
            MSG_NOT_CONTAIN_EXPECT = "Unexpectedly found",
            MSG_CONTAIN_ACTUAL = "in:",

            MSG_GENERATED_EXPECT = "Expected the generated code:",
            MSG_GENERATED_ACTUAL = "to be equivalent with excluding imports:",
            MSG_IMPORT_EXPECT = "Expected the generated imports:",
            MSG_IMPORT_ACTUAL = "to be equivalent with:",
            MSG_EXPECT = String.format("(%s)", String.join("|", MSG_CONTAIN_EXPECT, MSG_NOT_CONTAIN_EXPECT, MSG_GENERATED_EXPECT, MSG_IMPORT_EXPECT)),
            MSG_ACTUAL = String.format("(%s)", String.join("|", MSG_CONTAIN_ACTUAL, MSG_GENERATED_ACTUAL, MSG_IMPORT_ACTUAL));
    private final static Pattern
            PATTERN_EXP = Pattern.compile(MSG_EXPECT + "(.*?)" + MSG_ACTUAL, DOTALL),
            PATTERN_ACTUAL = Pattern.compile(MSG_ACTUAL + "(.*?)(java\\.lang\\.|$)", DOTALL);

    public GeneratorTest() {
        sourceTestPath = SRC_ROOT + "/" + getSubpath() + "/";
        this.checkProcessedSchemaDefault = getCheckProcessedSchemaDefault();
        this.components = getComponents();
        this.references = getExternalReferences();
        this.globalTransforms = getGlobalTransforms();
        this.extendedClasses = getExtendedClasses();
    }

    public String getSourceTestPath() {
        return sourceTestPath;
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
                public @NotNull FileVisitResult visitFile(@NotNull Path expectedOutputFile, @NotNull BasicFileAttributes attrs) {
                    String expectedFileName = expectedOutputFile.getFileName().toString().replace(".java", "");
                    expectedFileNames.add(expectedFileName);
                    var expectedFile = readFileAsStrings(expectedOutputFile);
                    if (!generatedFiles.containsKey(expectedFileName)) {
                        return FileVisitResult.CONTINUE;
                    }

                    var generatedFile = generatedFiles.get(expectedFileName);
                    var expected = formatExpectedFile(expectedFile);
                    assertList.add(
                            () -> {
                                String actual = formatGeneratedFile(generatedFile);
                                assertThat(actual)
                                        .withFailMessage(
                                                "\u001B[33;1m%s\u001B[0;35m\n%s\n\n\u001B[33;1m%s\u001B[0;35m\n%s\n\u001B[0m",
                                                MSG_GENERATED_EXPECT,
                                                expected,
                                                MSG_GENERATED_ACTUAL,
                                                actual
                                        )
                                        .isEqualToIgnoringWhitespace(expected);
                            }
                    );

                    var expectedFileImports = asImportList(expectedFile);
                    var generatedFileImports = asImportList(generatedFile);
                    assertList.add(
                            () -> assertThat(simplifyImports(generatedFileImports))
                                    .withFailMessage(
                                            "\u001B[33;1m%s\u001B[0;35m\n%s\n\n\u001B[33;1m%s\u001B[0;35m\n%s\n\u001B[0m",
                                            MSG_IMPORT_EXPECT,
                                            String.join("\n", generatedFileImports),
                                            MSG_IMPORT_ACTUAL,
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
                .toList();
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
            var message = e.getMessage();
            var messageNoColour = message.replaceAll("\u001B\\[[;\\d]*m", "");

            var matcherExp = PATTERN_EXP.matcher(messageNoColour);
            var expected = new ArrayList<String>();
            while (matcherExp.find()) {
               expected.add(matcherExp.group(2).strip());
            }

            var matcherActual = PATTERN_ACTUAL.matcher(messageNoColour);
            var actual = new ArrayList<String>();
            while (matcherActual.find()) {
                var match = matcherActual.group(2).strip();
                if (actual.stream().noneMatch(it -> it.equals(match))) {
                    actual.add(match);
                }
            }

            // Prints the failures to the console twice, so that IDE comparison will show up.
            throw new ComparisonFailure(message, String.join("\n\n", expected), String.join("\n\n", actual));

            // Prints the failures to the console.
            // throw new MultipleFailuresError(e.getMessage(), List.of());
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
        return TestConfiguration.getProcessedSchema(sourceTestPath + schemaParentFolder, mergeComponentsAndSetConfig(extraComponents), validateSchema(), checkProcessedSchemaDefault);
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
                        .withFailMessage(
                                "\u001B[33;1m%s\u001B[0;35m\n%s\n\u001B[33;1m%s\u001B[0;35m\n%s\n\u001B[0m",
                                MSG_CONTAIN_EXPECT,
                                it,
                                MSG_CONTAIN_ACTUAL,
                                allFileContent
                        )
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
                        .withFailMessage(
                                "\u001B[33;1m%s\u001B[0;35m\n%s\n\u001B[33;1m%s\u001B[0;35m\n%s\n\u001B[0m",
                                MSG_NOT_CONTAIN_EXPECT,
                                it,
                                MSG_CONTAIN_ACTUAL,
                                allFileContent
                        )
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
        assertFilesAreGenerated(schemaFolder, Set.of(), expectedFiles);
    }

    protected void assertFilesAreGenerated(String schemaFolder, Set<SchemaComponent> extraComponents, String... expectedFiles) {
        assertThat(generateFiles(schemaFolder, extraComponents).keySet()).containsExactlyInAnyOrderElementsOf(Set.of(expectedFiles));
    }


    protected void assertNothingGenerated(String schemaFolder) {
        assertNothingGenerated(schemaFolder, Set.of());
    }

    protected void assertNothingGenerated(String schemaFolder, Set<SchemaComponent> extraComponents) {
        assertThat(generateFiles(schemaFolder, extraComponents).keySet()).isEmpty();
    }

    @BeforeEach
    public void setup() {
        // Reset ValidationHandler state before each test to prevent cross-test contamination
        ValidationHandler.resetErrorMessages();
        ValidationHandler.resetWarningMessages();
    }
    
    @AfterEach
    public void teardown() {
        GeneratorConfig.clear(); // To prevent any config from remaining when running multiple tests.
        ValidationHandler.resetErrorMessages();
        ValidationHandler.resetWarningMessages();
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

    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of();
    }

    protected String getSubpath() {
        return "";
    }

    protected boolean validateSchema() {
        return false;
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
