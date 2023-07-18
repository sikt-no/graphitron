package no.fellesstudentsystem.graphitron.schema;

import graphql.language.Document;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserOptions;
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
    // Default is 15000. With new directive on every field it goes over this limit.
    // If the error about preventing DoS attacks shows up again, increase this value here.
    private final static int MAX_TOKENS = 60000;

    public static Document readSchemas(List<String> sources) {
        MultiSourceReader.Builder builder = MultiSourceReader.newMultiSourceReader();
        for (String path : sources) {
            String content;
            try {
                content = Files.readString(Paths.get(path), StandardCharsets.UTF_8) + System.lineSeparator();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            builder.string(content, path);
        }

        var parseOptions = ParserOptions.getDefaultParserOptions().transform(build -> build.maxTokens(MAX_TOKENS));
        return new Parser().parseDocument(builder.trackData(true).build(), parseOptions);
    }

    public static TypeDefinitionRegistry getTypeDefinitionRegistry(List<String> schemas) {
        // https://github.com/kobylynskyi/graphql-java-codegen/blob/master/src/main/java/com/kobylynskyi/graphql/codegen/parser/GraphQLDocumentParser.java
        return new SchemaParser().buildRegistry(readSchemas(schemas));
    }
}
