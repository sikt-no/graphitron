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

    /**
     * Relay {@code Node} interface, injected into every test SDL as a harmless orphan unless the
     * test declares its own implementation. {@code @node} typeclassification requires the type
     * to {@code implements Node}; bundling the declaration here keeps test SDL focused on the
     * actual subject.
     */
    private static final String NODE_INTERFACE = "interface Node { id: ID! }\n";

    private TestSchemaHelper() {}

    public static GraphitronSchema buildSchema(String schemaText) {
        return buildSchema(schemaText, TestConfiguration.testContext());
    }

    public static GraphitronSchema buildSchema(String schemaText, RewriteContext ctx) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(prelude(schemaText) + schemaText);
        return GraphitronSchemaBuilder.build(registry, ctx);
    }

    /**
     * The build-time diagnostics rendered one per line as {@code coordinate: message}, for assertions
     * about a cause that is reported at the coordinate carrying it rather than on the consuming
     * field. Input-field failures are minted there, one located diagnostic per failure, while the
     * consuming field keeps a single rejection stating the consequence, so a test that wants the
     * cause text reads this and a test that wants the consequence reads the field's own rejection.
     *
     * <p>The coordinate is rendered from the {@link ValidationError} rather than read out of the
     * message because the typed sub-seal arms ({@code ReflectionError} and siblings) treat
     * {@code prefixedWith} as a no-op by design, so their message carries no coordinate prose.
     */
    public static String diagnosticMessages(GraphitronSchema schema) {
        return schema.diagnostics().stream()
            .map(d -> d.coordinate() + ": " + d.message())
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    public static GraphitronSchemaBuilder.Bundle buildBundle(String schemaText) {
        return buildBundle(schemaText, TestConfiguration.testContext());
    }

    public static GraphitronSchemaBuilder.Bundle buildBundle(String schemaText, RewriteContext ctx) {
        return GraphitronSchemaBuilder.buildBundle(parseRegistryWithPrelude(schemaText), ctx);
    }

    /**
     * The node predicate over the default test context's jOOQ catalog. Test sites that drive
     * {@link SchemaReachability}, the fact traversal, or
     * {@link no.sikt.graphitron.rewrite.schema.federation.KeyNodeSynthesiser} directly, rather than
     * through {@link GraphitronSchemaBuilder}, pass this so their seed set matches production's.
     */
    public static NodeDeclaration nodeDeclaration() {
        return nodeDeclaration(TestConfiguration.testContext());
    }

    /** {@link #nodeDeclaration()} against a caller-supplied context (a fixture jOOQ package). */
    public static NodeDeclaration nodeDeclaration(RewriteContext ctx) {
        return new NodeDeclaration(new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()));
    }

    /**
     * The attribution pipeline production runs, for tests that need the two handles it hands back
     * rather than a bare parse: {@link AttributedRegistry#registry()} after the synthesis rewrites
     * and {@link AttributedRegistry#preSynthesisRegistry()} before them. A test that reconstructs
     * the stage order itself instead of calling this pins its own reading of the pipeline rather
     * than the pipeline.
     */
    public static AttributedRegistry attributedRegistry(RewriteContext ctx) {
        return new GraphQLRewriteGenerator(ctx).loadAttributedRegistry();
    }

    /**
     * Parses {@code schemaText} into a {@link TypeDefinitionRegistry} after prepending the
     * directives prelude and the Relay {@code Node} interface (when not already declared).
     * Exposed so sibling test helpers (e.g. snapshot builders for the classification
     * projection truth-table) can drive the same parser without round-tripping through
     * {@code buildBundle}.
     */
    public static TypeDefinitionRegistry parseRegistryWithPrelude(String schemaText) {
        return new SchemaParser().parse(prelude(schemaText) + schemaText);
    }

    private static String prelude(String schemaText) {
        String out = DIRECTIVES + "\n";
        if (!schemaText.contains("interface Node")) {
            out += NODE_INTERFACE;
        }
        return out;
    }

    /**
     * Number of lines the inline-test prelude prepends to {@code schemaText} before
     * graphql-java parses the combined document. Tests that pin a concrete
     * {@code SourceLocation.getLine()} against a fixture add this offset to the
     * carrier field's user-relative line number.
     */
    public static int preludeLineCount(String schemaText) {
        String p = prelude(schemaText);
        int n = 0;
        for (int i = 0; i < p.length(); i++) if (p.charAt(i) == '\n') n++;
        return n;
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
