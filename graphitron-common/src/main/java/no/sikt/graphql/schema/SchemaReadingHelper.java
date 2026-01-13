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


/**
 * Class for reading schema files from disk and classpath.
 */
public class SchemaReadingHelper {

    public static Document readSchemas(Collection<String> sources) {
        MultiSourceReader.Builder builder = MultiSourceReader.newMultiSourceReader();
        sources.forEach(path -> builder.string(readContent(path) + System.lineSeparator(), path));

        var multiSourceReader = builder.trackData(true).build();
        return new Parser()
                .parseDocument(
                        ParserEnvironment.newParserEnvironment()
                                .parserOptions(ParserOptions.getDefaultSdlParserOptions())
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
        // Normalize path for classloader (no leading slash)
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;

        String content = readFromClasspath(normalizedPath);
        if (content != null) {
            return content;
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
     * Read content from classpath resource using the context classloader.
     * This ensures resources are found correctly in frameworks like Quarkus dev mode
     * where the application's resources may be in a different classloader than this library.
     */
    private static String readFromClasspath(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
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
