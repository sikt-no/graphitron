package no.fellesstudentsystem.schema_transformer.schema;

import com.apollographql.federation.graphqljava.Federation;
import com.apollographql.federation.graphqljava.FederationDirectives;
import graphql.language.SDLNamedDefinition;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLNamedSchemaElement;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphqlTypeComparatorRegistry;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class for writing schema files to disk.
 */
public class SchemaWriter {

    // @connection-direktivet fra kodegeneratoren kolliderer med connection-direktivet til Relay.
    private static final List<String> EXCLUDED_DIRECTIVES = List.of("connection");
    private static final Set<String> FEDERATION_SPEC_DEFINITIONS =
            FederationDirectives.loadFederationSpecDefinitions(Federation.FEDERATION_SPEC_V2_9).stream()
                    .map(SDLNamedDefinition::getName)
                    .collect(Collectors.toSet());

    public static void writeSchemaToDirectory(GraphQLSchema graphQLSchema, String filename, String outputDirectory, boolean keepDirectives) throws IOException {
        writeSchemaToDirectory(graphQLSchema, filename, outputDirectory, keepDirectives, true);
    }

    public static void writeSchemaToDirectory(GraphQLSchema graphQLSchema, String filename, String outputDirectory) throws IOException {
        writeSchemaToDirectory(graphQLSchema, filename, outputDirectory, false);
    }

    /**
     * Writes a GraphQL schema to a specified directory, filtering out federation definitions (directives, scalars, enums)
     * to prevent conflicts with the federation library's own definitions.
     * Note that applied federation directives are retained to ensure federation functionality.
     */
    public static void writeFederationSchemaToDirectory(GraphQLSchema graphQLSchema, String filename, String outputDirectory) throws IOException {
        writeSchemaToDirectory(graphQLSchema, filename, outputDirectory, false, false);
    }

    public static String writeSchemaToString(GraphQLSchema graphQLSchema, boolean keepDirectives) {
        return writeSchemaToString(graphQLSchema, keepDirectives, true);
    }

    public static String writeSchemaToString(GraphQLSchema graphQLSchema) {
        return writeSchemaToString(graphQLSchema, false, true);
    }

    private static void writeSchemaToDirectory(GraphQLSchema graphQLSchema, String filename, String outputDirectory, boolean keepDirectives, boolean keepFederationDefinitions) throws IOException {
        Files.write(
                Paths.get(outputDirectory, filename),
                writeSchemaToString(graphQLSchema, keepDirectives, keepFederationDefinitions).getBytes(StandardCharsets.UTF_8)
        );
    }

    static String writeSchemaToString(GraphQLSchema graphQLSchema, boolean keepDirectives, boolean keepFederationDefinitions) {
        //need to set "as is" comparator. The default is to order by name, which causes an argument ordering mismatch between the schema and the resolver interfaces
        var printerOptions = SchemaPrinter.Options.defaultOptions()
                .setComparators(GraphqlTypeComparatorRegistry.AS_IS_REGISTRY)
                .includeSchemaDefinition(true)
                .includeDirectives(it -> (keepDirectives || !EXCLUDED_DIRECTIVES.contains(it)))
                .includeSchemaElement(it -> keepFederationDefinitions || !isFederationSchemaElement(it));

        return new SchemaPrinter(printerOptions).print(graphQLSchema);
    }

    private static boolean isFederationSchemaElement(GraphQLSchemaElement it) {
        return (it instanceof GraphQLDirective || it instanceof GraphQLScalarType || it instanceof GraphQLEnumType)
                && FEDERATION_SPEC_DEFINITIONS.contains(((GraphQLNamedSchemaElement) it).getName());
    }

    /**
     * Will detect and throw exceptions if there are problems in assembling a valid schema,
     * such as missing/invalid directive arguments.
     * The runtimeWiring is only used to validate the schema and is thus incomplete
     * (e.g. the type resolvers are just dummies), it contains a bare minimum needed for validation.
     *
     * @return an executable schema
     */
    public static GraphQLSchema assembleSchema(TypeDefinitionRegistry typeDefinitionRegistry) {
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(typeDefinitionRegistry);
    }
}
