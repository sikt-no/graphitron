package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.*;
import graphql.schema.diff.DiffEvent;
import graphql.schema.diff.DiffSet;
import graphql.schema.diff.SchemaDiff;
import graphql.schema.diff.reporting.CapturingReporter;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.SchemaProblem;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.SchemaConfig;
import no.fellesstudentsystem.schema_transformer.schema.SchemaWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.util.TraversalControl.CONTINUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtendedSchemaTest {
    public static final String SRC_TEST_RESOURCES = "src/test/resources/";
    @TempDir
    Path tempOutputDirectory;

    @Test
    void transform_schemaRemovesCodeGenerationDirective() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("extendedSchemaWithDirectives");
    }

    @Test
    void transform_extendedSchemaForResolver() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithLayeredConnection");
    }

    @Test
    void transform_extendedSchemaWithFullRelayBoilerplate() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("queryWithPaginationRelayBoilerplate", "queryWithSingleConnection");
    }

    @Test
    void transform_whenMissingRequiredDirectiveArgument_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/missingDirectiveArgument"))
                .isInstanceOf(SchemaProblem.class)
                .hasMessage("errors=['fieldWithDirective' [@8:5] failed to provide a value for the non null argument 'param' on directive 'directiveWithRequiredParam']");
    }

    @Test
    void transform_whenInvalidDirectiveArgument_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/invalidDirectiveArgument"))
                .isInstanceOf(SchemaProblem.class)
                .hasMessage("errors=['fieldWithDirective' [@8:5] uses an illegal value for the argument 'param' on directive 'directiveWithParam'. Argument value is not a valid value of scalar 'String'.]");
    }

    @Test
    void transform_addsFeaturesBasedOnDirectoryPath() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("addFeatures/schema", "addFeatures", true, false);
    }

    @Test
    void transform_extendsCommentsBasedOnFile() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("addComments/schema", "addComments", true, false);
    }

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String schemaFolder, String expectedOutputFolder) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(schemaFolder, expectedOutputFolder, false, true);
    }

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String schemaFolder, String expectedOutputFolder, boolean setAsRootDir, boolean validateGeneratorSchema) throws IOException {
        Map<String, String> generatedFiles = generateFiles(schemaFolder, setAsRootDir);
        var expectedFileNames = new HashSet<String>();

        Files.walkFileTree(Paths.get(SRC_TEST_RESOURCES + expectedOutputFolder + "/expectedOutput"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path expectedOutputFile, BasicFileAttributes attrs) throws IOException {
                String expectedFileName = expectedOutputFile.getFileName().toString().replace(".txt", "");

                if (!validateGeneratorSchema && expectedFileName.equals(SchemaTransformer.GENERATOR_SCHEMA_NAME)) {
                    return FileVisitResult.CONTINUE;
                }

                expectedFileNames.add(expectedFileName);
                String expectedFileContent = readFileAsString(expectedOutputFile);
                assertThat(generatedFiles.keySet()).contains(expectedFileName);

                String generatedFileContent = generatedFiles.get(expectedFileName);

                GraphQLSchema generatedSchema = schemaStringAsSchema(generatedFileContent);
                GraphQLSchema expectedSchema = schemaStringAsSchema(expectedFileContent);

                CapturingReporter schemaDiffReporter = new CapturingReporter();
                new SchemaDiff(SchemaDiff.Options.defaultOptions().enforceDirectives()).diffSchema(DiffSet.diffSet(expectedSchema, generatedSchema), schemaDiffReporter);
                String diffEventsReport = Stream.concat(schemaDiffReporter.getDangers().stream(), schemaDiffReporter.getBreakages().stream())
                        .map(DiffEvent::toString)
                        .collect(Collectors.joining(",\n "));
                assertThat(schemaDiffReporter.getDangerCount() + schemaDiffReporter.getBreakageCount())
                        .as("Found the following dangerous or breaking differences between the schemas: %s", diffEventsReport)
                        .isZero();

                var actualPatterns = new HashSet<String>();
                new SchemaTraverser().depthFirstFullSchema(new GraphQLTypeVisitorStub() {
                    @Override
                    public TraversalControl visitGraphQLAppliedDirective(GraphQLAppliedDirective node, TraverserContext<GraphQLSchemaElement> context) {
                        actualPatterns.add(getNodePattern(node, context));
                        return CONTINUE;
                    }
                }, generatedSchema);

                var expectedPatterns = new HashSet<String>();
                new SchemaTraverser().depthFirstFullSchema(new GraphQLTypeVisitorStub() {
                    @Override
                    public TraversalControl visitGraphQLAppliedDirective(GraphQLAppliedDirective node, TraverserContext<GraphQLSchemaElement> context) {
                        expectedPatterns.add(getNodePattern(node, context));
                        return CONTINUE;
                    }
                }, expectedSchema);

                assertThat(actualPatterns).containsExactlyInAnyOrderElementsOf(expectedPatterns);
                assertThat(generatedSchema.getDirectivesByName().keySet()).containsExactlyInAnyOrderElementsOf(expectedSchema.getDirectivesByName().keySet());

                return FileVisitResult.CONTINUE;
            }
        });

        if (validateGeneratorSchema) {
            assertThat(expectedFileNames).containsExactlyInAnyOrderElementsOf(generatedFiles.keySet());
        } else {
            Set<String> generatedFileNames = generatedFiles.keySet();
            generatedFileNames.remove(SchemaTransformer.GENERATOR_SCHEMA_NAME);
            assertThat(expectedFileNames).containsExactlyInAnyOrderElementsOf(generatedFileNames);
        }
    }

    private static String getNodePattern(GraphQLNamedSchemaElement node, TraverserContext<GraphQLSchemaElement> context) {
        var string = node.toString();
        int cutIndex = string.indexOf("description=");
        var s2 = string.substring(0, cutIndex > 0 ? cutIndex : string.length());
        return s2 + context.getParentNode().toString()
                .replaceAll("\\s", ""); //fjerner 'white space characters' fra mønsteret for å unngå at testen feiler pga ulik formatering
    }

    private static GraphQLSchema schemaStringAsSchema(String generatedFileContent) {
        TypeDefinitionRegistry typeDefinitionRegistry = new SchemaParser().parse(generatedFileContent);
        return SchemaWriter.assembleSchema(typeDefinitionRegistry);
    }

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String resourceRootFolder) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(resourceRootFolder, resourceRootFolder);
    }

    private Map<String, String> generateFiles(String schemaParentFolder, boolean setAsRootDir) throws IOException {
        var actualParentFolder = SRC_TEST_RESOURCES + schemaParentFolder;
        Set<String> actualParentFolders = Set.of(actualParentFolder);

        List<String> schemaLocations;
        if (setAsRootDir) {
            schemaLocations = SchemaConfig.findSchemaFilesRecursivelyInDirectory(actualParentFolders);
        } else {
            schemaLocations = List.of(
                    actualParentFolder + "/schema.graphql",
                    SRC_TEST_RESOURCES + "default.graphqls");
        }
        var mapping = SchemaConfig.createDescriptionSuffixForFeatureMap(actualParentFolders, "description-suffix.md");

        SchemaTransformer.transformFeatures(schemaLocations, mapping, tempOutputDirectory.toString(), false);
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

    private Map<String, String> generateFiles(String schemaParentFolder) throws IOException {
        return generateFiles(schemaParentFolder, false);
    }

    private static String readFileAsString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
