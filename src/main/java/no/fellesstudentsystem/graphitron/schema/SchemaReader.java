package no.fellesstudentsystem.graphitron.schema;

import graphql.language.Document;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Class for reading schema files from disk.
 */
public class SchemaReader {
    public static Document readSchemas(List<String> sources) throws IOException {
        MultiSourceReader.Builder builder = MultiSourceReader.newMultiSourceReader();
        for (String path : sources) {
            String content = Files.readString(Paths.get(path), StandardCharsets.UTF_8) + System.lineSeparator();
            builder.string(content, path);
        }
        return new Parser().parseDocument(builder.trackData(true).build());
    }

    public static TypeDefinitionRegistry getTypeDefinitionRegistry(List<String> schemas) throws IOException {
        // https://github.com/kobylynskyi/graphql-java-codegen/blob/master/src/main/java/com/kobylynskyi/graphql/codegen/parser/GraphQLDocumentParser.java
        return new SchemaParser().buildRegistry(readSchemas(schemas));
    }
}
