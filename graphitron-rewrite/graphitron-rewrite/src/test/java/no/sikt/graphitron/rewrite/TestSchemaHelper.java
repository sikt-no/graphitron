package no.sikt.graphitron.rewrite;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared test helper that builds a {@link GraphitronSchema} from inline SDL,
 * loading Graphitron directive definitions from rewrite's own classpath resource.
 */
public final class TestSchemaHelper {

    private static final String DIRECTIVES = loadDirectives();

    private TestSchemaHelper() {}

    public static GraphitronSchema buildSchema(String schemaText) {
        return buildSchema(schemaText, TestConfiguration.testContext());
    }

    public static GraphitronSchema buildSchema(String schemaText, RewriteContext ctx) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(DIRECTIVES + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry, ctx);
    }

    public static GraphitronSchemaBuilder.Bundle buildBundle(String schemaText) {
        return buildBundle(schemaText, TestConfiguration.testContext());
    }

    public static GraphitronSchemaBuilder.Bundle buildBundle(String schemaText, RewriteContext ctx) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(DIRECTIVES + "\n" + schemaText);
        return GraphitronSchemaBuilder.buildBundle(registry, ctx);
    }

    private static String loadDirectives() {
        try (InputStream is = RewriteSchemaLoader.class.getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
