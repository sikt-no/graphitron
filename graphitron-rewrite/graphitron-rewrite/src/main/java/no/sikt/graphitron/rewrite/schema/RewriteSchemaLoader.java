package no.sikt.graphitron.rewrite.schema;

import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;

/**
 * Builds a {@link TypeDefinitionRegistry} from a set of user-supplied schema file paths,
 * auto-injecting Graphitron's canonical {@code directives.graphqls} from this module's
 * own classpath. Rewrite's build-time entry point for schema parsing; replaces the
 * legacy {@code SchemaReadingHelper} for in-rewrite callers.
 *
 * <p>User schema inputs are read from the filesystem as streaming {@link Reader}s.
 * The directives source is a same-package classpath resource and therefore does not
 * require a consumer pom to list it. Callers must not include a {@code directives.graphqls}
 * entry in their user-schema list; doing so would re-declare every directive and fail
 * schema parse.
 */
public final class RewriteSchemaLoader {

    private static final String DIRECTIVES_RESOURCE = "directives.graphqls";

    private RewriteSchemaLoader() {}

    public static TypeDefinitionRegistry load(Collection<String> userSchemaPaths) {
        var builder = MultiSourceReader.newMultiSourceReader();
        addDirectivesSource(builder);
        userSchemaPaths.forEach(path -> builder.reader(openSource(path), path));
        try (var multi = builder.trackData(true).build()) {
            var document = new Parser().parseDocument(
                ParserEnvironment.newParserEnvironment()
                    .parserOptions(ParserOptions.getDefaultSdlParserOptions())
                    .document(multi)
                    .build());
            return new SchemaParser().buildRegistry(document);
        } catch (IOException e) {
            throw new RuntimeException("Schema parse failed", e);
        }
    }

    private static void addDirectivesSource(MultiSourceReader.Builder builder) {
        var stream = RewriteSchemaLoader.class.getResourceAsStream(DIRECTIVES_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(DIRECTIVES_RESOURCE + " not found on classpath");
        }
        builder.reader(new InputStreamReader(stream, StandardCharsets.UTF_8), DIRECTIVES_RESOURCE);
    }

    private static Reader openSource(String path) {
        var filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new RuntimeException("Schema file not found: " + path);
        }
        try {
            return Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Schema file unreadable: " + path, e);
        }
    }
}
