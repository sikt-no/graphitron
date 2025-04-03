package no.fellesstudentsystem.schema_transformer.schema;

import graphql.schema.GraphQLSchema;
import graphql.schema.GraphqlTypeComparatorRegistry;
import graphql.schema.idl.SchemaPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Class for writing schema files to disk.
 */
public class SchemaWriter {
    public static void writeSchemaToDirectory(GraphQLSchema graphQLSchema, String filename, String outputDirectory) throws IOException {
        Files.writeString(
                Paths.get(outputDirectory, filename),
                writeSchemaToString(graphQLSchema)
        );
    }

    public static String writeSchemaToString(GraphQLSchema graphQLSchema) {
        //need to set "as is" comparator. The default is to order by name, which causes an argument ordering mismatch between the schema and the resolver interfaces
        var printerOptions = SchemaPrinter.Options.defaultOptions()
                .setComparators(GraphqlTypeComparatorRegistry.AS_IS_REGISTRY)
                .includeSchemaDefinition(true);

        return new SchemaPrinter(printerOptions).print(graphQLSchema);
    }
}
