package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.*;
import graphql.schema.diff.DiffEvent;
import graphql.schema.diff.SchemaDiff;
import graphql.schema.diff.SchemaDiffSet;
import graphql.schema.diff.reporting.CapturingReporter;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import no.fellesstudentsystem.schema_transformer.schema.SchemaReader;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.util.TraversalControl.CONTINUE;
import static no.fellesstudentsystem.schema_transformer.SchemaTransformer.assembleSchema;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaHelpers.isInternal;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;
import static org.assertj.core.api.Assertions.assertThat;

public class AbstractTest {
    public static final String SRC_TEST_RESOURCES = "src/test/resources/";

    protected static void assertDirectives(GraphQLSchema generatedSchema, GraphQLSchema expectedSchema) {
        assertThat(generatedSchema.getDirectivesByName().keySet()).containsExactlyInAnyOrderElementsOf(expectedSchema.getDirectivesByName().keySet());
        var actualPatterns = getAppliedDirectivePatterns(generatedSchema);
        var expectedPatterns = getAppliedDirectivePatterns(expectedSchema);
        assertThat(actualPatterns).containsExactlyElementsOf(expectedPatterns);
    }

    protected static List<String> getAppliedDirectivePatterns(GraphQLSchema generatedSchema) {
        var patterns = new LinkedHashSet<String>();
        new SchemaTraverser().depthFirstFullSchema(new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLAppliedDirective(GraphQLAppliedDirective node, TraverserContext<GraphQLSchemaElement> context) {
                if (!isInternal(context)) {
                    patterns.add(getAppliedDirectivePattern(node, context));
                }
                return CONTINUE;
            }
        }, generatedSchema);
        return patterns.stream().sorted().toList();
    }

    private static String getAppliedDirectivePattern(GraphQLAppliedDirective node, TraverserContext<GraphQLSchemaElement> context) {
        var path = getNodePath(context);
        var dirName = node.getName();
        var arguments = node
                .getArguments()
                .stream()
                .filter(it -> it.getValue() != null)
                .map(it -> it.getName() + " - " + it.getValue().toString())
                .collect(Collectors.joining(", "));
        return "\u001B[33;1m" + dirName
                + " \u001B[0mon\u001B[0;35m " + path
                + (arguments.isEmpty() ? "" : " \u001B[0mwith arguments\u001B[0;35m " + arguments)
                + "\u001B[0m"; // Includes ANSI escape codes to colour-code the output.
    }

    protected static String getNodePath(TraverserContext<GraphQLSchemaElement> context) {
        var path = new ArrayList<String>();
        var wrapperFound = false;
        var startNode = context.thisNode();
        if (startNode instanceof GraphQLNamedSchemaElement namedNode && !(namedNode instanceof GraphQLAppliedDirective)) {
            path.add(namedNode.getName());
        }

        while (!wrapperFound) {
            context = context.getParentContext();
            var node = context.thisNode();
            if (node instanceof GraphQLNamedSchemaElement namedNode) {
                path.add(namedNode.getName());
                wrapperFound = namedNode instanceof GraphQLCompositeType || namedNode instanceof GraphQLInputFieldsContainer || namedNode instanceof GraphQLEnumType;
            } else {
                wrapperFound = true;
            }
        }

        Collections.reverse(path);
        return String.join(", ", path);
    }

    protected void assertTransformedSchemaMatches(GraphQLSchema generatedSchema, GraphQLSchema expectedSchema) {
        CapturingReporter schemaDiffReporter = new CapturingReporter();
        new SchemaDiff(SchemaDiff.Options.defaultOptions().enforceDirectives()).diffSchema(SchemaDiffSet.diffSetFromSdl(expectedSchema, generatedSchema), schemaDiffReporter);
        String diffEventsReport = Stream.concat(schemaDiffReporter.getDangers().stream(), schemaDiffReporter.getBreakages().stream())
                .map(DiffEvent::toString)
                .collect(Collectors.joining(",\n "));
        assertThat(schemaDiffReporter.getDangerCount() + schemaDiffReporter.getBreakageCount())
                .as("Found the following dangerous or breaking differences between the schemas:\n%s", diffEventsReport)
                .isZero();
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

    protected static GraphQLSchema makeExpectedSchema(String path) {
        return makeSchema(findSchemas(Set.of(path + "/expected")));
    }

    protected static GraphQLSchema makeTestSchema(String path) {
        return makeSchema(findSchemas(Set.of(path + "/schema")));
    }
}
