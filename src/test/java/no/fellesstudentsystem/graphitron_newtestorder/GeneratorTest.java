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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;

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

                    var expectedFileContent = expectedFile.stream().filter(it -> !it.startsWith("import") && !it.startsWith("package")).collect(Collectors.joining("\n"));
                    var generatedFileContent = generatedFile.stream().filter(it -> !it.startsWith("import") && !it.startsWith("package")).collect(Collectors.joining("\n"));
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
    public ProcessedSchema getProcessedSchema(String schemaParentFolder, SchemaComponent... extraComponents) {
        return getProcessedSchema(schemaParentFolder, Set.of(extraComponents));
    }

    @NotNull
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

    public static void assertGeneratedContentContains(Map<String, List<String>> generatedFiles, String... expected) {
        if (expected.length < 1) {
            return;
        }
        var allFileContent = processFileContent(generatedFiles);

        var expectedList = Stream.of(expected).collect(Collectors.toList());
        var expectedNoWhitespace = expectedList.stream().map(it -> it.replaceAll("\\s+", "")).collect(Collectors.toList());

        var found = findHashesByRegex(expectedNoWhitespace, allFileContent.replaceAll("\\s+", ""));
        for (int i = 0; i < expected.length; i++) {
            assertThat(found)
                    .overridingErrorMessage("Expected to find \"%s\" in\n%s\n", expectedList.get(i), allFileContent)
                    .contains(expectedNoWhitespace.get(i).hashCode());
        }
    }

    private static String processFileContent(Map<String, List<String>> generatedFiles) {
        return generatedFiles
                .values()
                .stream()
                .flatMap(Collection::stream)
                .filter(it -> !it.isEmpty() && !it.startsWith("import") && !it.startsWith("package"))
                .collect(Collectors.joining("\n")); // Add newlines for readability when the test fails.
    }

    private static ArrayList<Integer> findHashesByRegex(List<String> toFind, String searchSpace) {
        var m = Pattern
                .compile(toFind.stream().map(Pattern::quote).collect(Collectors.joining("|")))
                .matcher(searchSpace);
        var found = new ArrayList<Integer>();
        while (m.find()) {
            found.add(m.group(0).hashCode());
        }
        return found;
    }

    protected void assertGeneratedContentContains(String resourceRootFolder, Set<SchemaComponent> extraComponents, String... expected) {
        assertGeneratedContentContains(generateFiles(resourceRootFolder, extraComponents), expected);
    }

    protected void assertGeneratedContentContains(String resourceRootFolder, String... expected) {
        assertGeneratedContentContains(resourceRootFolder, Set.of(), expected);
    }

    // Avoid using these three methods if possible, they have worse performance than the ones above.
    public static void resultDoesNotContain(Map<String, List<String>> generatedFiles, String... expected) {
        if (expected.length < 1) {
            return;
        }

        var allFileContent = processFileContent(generatedFiles);
        var expectedList = Stream.of(expected).collect(Collectors.toList());
        var expectedNoWhitespace = expectedList.stream().map(it -> it.replaceAll("\\s+","")).collect(Collectors.toList());

        var found = findHashesByRegex(expectedNoWhitespace, allFileContent.replaceAll("\\s+",""));
        for (int i = 0; i < expected.length; i++) {
            assertThat(found)
                    .overridingErrorMessage("Unexpectedly found \"%s\" in\n%s\n", expectedList.get(i), allFileContent)
                    .doesNotContain(expectedNoWhitespace.get(i).hashCode());
        }
    }

    protected void resultDoesNotContain(String resourceRootFolder, Set<SchemaComponent> extraComponents, String... expected) {
        resultDoesNotContain(generateFiles(resourceRootFolder, extraComponents), expected);
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
