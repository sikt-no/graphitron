package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.*;
import graphql.schema.diff.DiffEvent;
import graphql.schema.diff.SchemaDiff;
import graphql.schema.diff.SchemaDiffSet;
import graphql.schema.diff.reporting.CapturingReporter;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.schema.SchemaReader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.util.TraversalControl.CONTINUE;
import static no.fellesstudentsystem.schema_transformer.SchemaTransformer.assembleSchema;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeSchemaToDirectory;
import static org.assertj.core.api.Assertions.assertThat;

public class AbstractTest {
    public static final String SRC_TEST_RESOURCES = "src/test/resources/";
    @TempDir
    Path temp;

    protected static String readFileAsString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    protected static GraphQLSchema schemaStringAsSchema(String generatedFileContent) {
        TypeDefinitionRegistry typeDefinitionRegistry = new SchemaParser().parse(generatedFileContent);
        return SchemaTransformer.assembleSchema(typeDefinitionRegistry);
    }

    protected Map<String, String> getGeneratedFiles() {
        Map<String, String> generatedFiles = new HashMap<>();
        try {
            Files.walkFileTree(temp, new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                    if (!Files.isDirectory(file)) {
                        generatedFiles.put(file.getFileName().toString(), readFileAsString(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return generatedFiles;
    }

    protected void assertTransformedDirectivesMatch(String expectedOutputFolder, GraphQLSchema schema) {
        try {
            writeSchemaToDirectory(schema, "schema.graphql", temp.toString(), false);
            var generatedFiles = getGeneratedFiles();
            Files.walkFileTree(Paths.get(SRC_TEST_RESOURCES + expectedOutputFolder + "/expected"), new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult visitFile(Path expectedOutputFile, @NotNull BasicFileAttributes attrs) throws IOException {
                    String expectedFileName = expectedOutputFile.getFileName().toString();
                    assertThat(generatedFiles.keySet()).contains(expectedFileName);

                    var expectedSchema = schemaStringAsSchema(readFileAsString(expectedOutputFile));
                    var generatedSchema = schemaStringAsSchema(generatedFiles.get(expectedFileName));
                    assertDirectives(generatedSchema, expectedSchema);

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected static void assertDirectives(GraphQLSchema generatedSchema, GraphQLSchema expectedSchema) {
        assertThat(generatedSchema.getDirectivesByName().keySet()).containsExactlyInAnyOrderElementsOf(expectedSchema.getDirectivesByName().keySet());
        var actualPatterns = getAppliedDirectivePatterns(generatedSchema);
        var expectedPatterns = getAppliedDirectivePatterns(expectedSchema);
        assertThat(actualPatterns).containsExactlyInAnyOrderElementsOf(expectedPatterns);
    }

    protected static HashSet<String> getAppliedDirectivePatterns(GraphQLSchema generatedSchema) {
        var patterns = new HashSet<String>();
        new SchemaTraverser().depthFirstFullSchema(new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLAppliedDirective(GraphQLAppliedDirective node, TraverserContext<GraphQLSchemaElement> context) {
                patterns.add(getNodePattern(node, context));
                return CONTINUE;
            }
        }, generatedSchema);
        return patterns;
    }

    private static String getNodePattern(GraphQLNamedSchemaElement node, TraverserContext<GraphQLSchemaElement> context) {
        var string = node.toString();
        int cutIndex = string.indexOf("description=");
        var s2 = string.substring(0, cutIndex > 0 ? cutIndex : string.length());
        return s2 + context.getParentNode().toString()
                .replaceAll("\\s", ""); //fjerner 'white space characters' fra mønsteret for å unngå at testen feiler pga ulik formatering
    }

    protected void assertTransformedSchemaMatches(String expectedOutputFolder, GraphQLSchema schema) {
        var expectedFileNames = new HashSet<String>();
        try {
            writeSchemaToDirectory(schema, "schema.graphql", temp.toString(), false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var generatedFiles = getGeneratedFiles();
        try {
            Files.walkFileTree(Paths.get(SRC_TEST_RESOURCES + expectedOutputFolder + "/expected"), new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult visitFile(Path expectedOutputFile, @NotNull BasicFileAttributes attrs) throws IOException {
                    String expectedFileName = expectedOutputFile.getFileName().toString();
                    expectedFileNames.add(expectedFileName);
                    assertThat(generatedFiles.keySet()).contains(expectedFileName);

                    GraphQLSchema generatedSchema = schemaStringAsSchema(generatedFiles.get(expectedFileName));
                    GraphQLSchema expectedSchema = schemaStringAsSchema(readFileAsString(expectedOutputFile));

                    CapturingReporter schemaDiffReporter = new CapturingReporter();
                    new SchemaDiff(SchemaDiff.Options.defaultOptions().enforceDirectives()).diffSchema(SchemaDiffSet.diffSetFromSdl(expectedSchema, generatedSchema), schemaDiffReporter);
                    String diffEventsReport = Stream.concat(schemaDiffReporter.getDangers().stream(), schemaDiffReporter.getBreakages().stream())
                            .map(DiffEvent::toString)
                            .collect(Collectors.joining(",\n "));
                    assertThat(schemaDiffReporter.getDangerCount() + schemaDiffReporter.getBreakageCount())
                            .as("Found the following dangerous or breaking differences between the schemas: %s", diffEventsReport)
                            .isZero();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        assertThat(expectedFileNames).containsExactlyInAnyOrderElementsOf(generatedFiles.keySet());
    }

    protected static List<String> findSchemas(Set<String> parentFolders) {
        return SchemaReader.findSchemaFilesRecursivelyInDirectory(parentFolders.stream().map(it -> SRC_TEST_RESOURCES + it).collect(Collectors.toSet()));
    }

    protected static List<String> findSchemas(String parentFolder) {
        var testFile = SRC_TEST_RESOURCES + parentFolder + "/schema.graphql";
        return List.of(testFile);
    }

    protected static GraphQLSchema makeSchema(List<String> schemas) {
        return assembleSchema(getTypeDefinitionRegistry(schemas));
    }

    protected static GraphQLSchema makeSchema(String parentFolder) {
        return assembleSchema(getTypeDefinitionRegistry(findSchemas(parentFolder)));
    }
}
