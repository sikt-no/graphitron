package no.sikt.graphql.schema;

import graphql.language.Document;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static graphql.parser.ParserOptions.MAX_QUERY_CHARACTERS;
import static graphql.parser.ParserOptions.MAX_QUERY_TOKENS;

/**
 * Class for reading schema files from disk and classpath.
 */
public class SchemaReadingHelper {
    /**
     * Default is 15000 for the GraphQL parser. With a new directive on every field it goes over this limit.
     * If the errors about preventing DoS attacks show up again, increase the values in this file.
     * DoS is not relevant here since this is only used for reading local schema files.
     */
    private final static int MAX_TOKENS = MAX_QUERY_TOKENS * 12;
    /**
     * Default is 1 MB (1024 * 1024) for the GraphQL parser.
     */
    private final static int MAX_CHARACTERS = MAX_QUERY_CHARACTERS * 3;

    public static Document readSchemas(Collection<String> sources) {
        MultiSourceReader.Builder builder = MultiSourceReader.newMultiSourceReader();
        sources.forEach(path -> builder.string(readContent(path) + System.lineSeparator(), path));

        var parseOptions = ParserOptions
                .getDefaultParserOptions()
                .transform(build -> build.maxTokens(MAX_TOKENS).maxCharacters(MAX_CHARACTERS));
        var multiSourceReader = builder.trackData(true).build();
        return new Parser()
                .parseDocument(
                        ParserEnvironment.newParserEnvironment()
                                .parserOptions(parseOptions)
                                .document(multiSourceReader)
                                .build());
    }

    /**
     * Read the set of schema paths and combine them to a GraphQL {@link TypeDefinitionRegistry}.
     * @param schemas The schema paths to be used.
     * @return A {@link TypeDefinitionRegistry} from the found schema files.
     */
    public static TypeDefinitionRegistry getTypeDefinitionRegistry(Set<String> schemas) {
        return new SchemaParser().buildRegistry(readSchemas(schemas));
    }

    /**
     * Tries to read content from classpath first, then falls back to filesystem
     */
    private static String readContent(String path) {

        // Try classpath as-is
        String content = readFromClasspath(path);
        if (content != null) {
            return content;
        }

        // Try with leading slash if not present
        if (!path.startsWith("/")) {
            content = readFromClasspath("/" + path);
            if (content != null) {
                return content;
            }
        }

        try {
            Path filePath = Paths.get(path);
            if (Files.exists(filePath)) {
                return fileAsString(filePath);
            }
        } catch (Exception ignored) {
            // exception thrown below
        }
        throw new RuntimeException("Schema file not found: " + path);
    }

    /**
     * Read content from classpath resource, returns null if not found
     */
    private static String readFromClasspath(String resourcePath) {
        try (InputStream is = SchemaReadingHelper.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static String fileAsString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
