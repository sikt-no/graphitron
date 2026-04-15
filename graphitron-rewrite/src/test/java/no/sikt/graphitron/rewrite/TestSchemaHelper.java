package no.sikt.graphitron.rewrite;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared test helper that builds a {@link GraphitronSchema} from inline SDL,
 * loading Graphitron directive definitions from the classpath (via {@code graphitron-common})
 * instead of a hardcoded filesystem path.
 */
public final class TestSchemaHelper {

    private static final String DIRECTIVES = loadDirectives();

    private TestSchemaHelper() {}

    public static GraphitronSchema buildSchema(String schemaText) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(DIRECTIVES + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }

    private static String loadDirectives() {
        try (InputStream is = TestSchemaHelper.class.getClassLoader().getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
