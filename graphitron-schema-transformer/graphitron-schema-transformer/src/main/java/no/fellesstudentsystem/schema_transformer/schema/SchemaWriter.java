package no.fellesstudentsystem.schema_transformer.schema;

import com.apollographql.federation.graphqljava._Any;
import com.apollographql.federation.graphqljava._Entity;
import com.apollographql.federation.graphqljava._Service;
import graphql.schema.GraphQLNamedSchemaElement;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphqlTypeComparatorRegistry;
import graphql.schema.idl.SchemaPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Class for writing schema files to disk.
 */
public class SchemaWriter {
    private static final Set<String> FEDERATION_TYPES_TO_REMOVE =
            Set.of(_Entity.fieldName, _Entity.typeName, _Service.fieldName, _Service.typeName, _Any.typeName);

    public static void writeSchemaToDirectory(GraphQLSchema graphQLSchema, String filename, String outputDirectory,
                                              boolean removeFederationDefinitions) throws IOException {
        Files.writeString(
                Paths.get(outputDirectory, filename),
                writeSchemaToString(graphQLSchema, removeFederationDefinitions)
        );
    }

    public static String writeSchemaToString(GraphQLSchema graphQLSchema, boolean removeFederationDefinitions) {
        //need to set "as is" comparator. The default is to order by name, which causes an argument ordering mismatch between the schema and the resolver interfaces
        var printerOptions = SchemaPrinter.Options.defaultOptions()
                .setComparators(GraphqlTypeComparatorRegistry.AS_IS_REGISTRY)
                .includeSchemaDefinition(true)
                .includeSchemaElement(it -> !removeFederationDefinitions || !isFederationSchemaElementToRemove(it));

        return new SchemaPrinter(printerOptions).print(graphQLSchema);
    }

    private static boolean isFederationSchemaElementToRemove(GraphQLSchemaElement it) {
        if (!(it instanceof GraphQLNamedSchemaElement)) {
            return false;
        }
        var name = ((GraphQLNamedSchemaElement) it).getName();
        return FEDERATION_TYPES_TO_REMOVE.contains(name);
    }
}
