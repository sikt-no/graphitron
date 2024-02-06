package no.fellesstudentsystem.graphql.schema;

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
import java.util.Set;

/**
 * Class for reading schema files from disk.
 */
public class SchemaReader {
    /**
     * Default is 15000 for the GraphQL parser. With a new directive on every field it goes over this limit.
     * If the error about preventing DoS attacks shows up again, increase this value here.
     */
    private final static int MAX_TOKENS = 100000;

    private static Document readSchemas(Set<String> sources) {
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

    /**
     * Read the set of schema paths and combine them to a GraphQL {@link TypeDefinitionRegistry}.
     * @param schemas The schema paths to be used.
     * @return A {@link TypeDefinitionRegistry} from the found schema files.
     */
    public static TypeDefinitionRegistry getTypeDefinitionRegistry(Set<String> schemas) {
        return new SchemaParser().buildRegistry(readSchemas(schemas));
    }
}
