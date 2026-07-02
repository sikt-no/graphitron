package no.sikt.graphitron.rewrite.compile;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410 slice 2 — pipeline-tier coverage that a realistically classified SDL yields the expected
 * file-level edges through {@link CompileDependencyGraphBuilder#fromModel}. Exercises the model
 * switch against the classifier's real leaf output (not hand-built records): a root query field
 * references its target type's projection and the root conditions; a table-navigating child field
 * references its target type's projection and its parent conditions. This is the spec's slice-2
 * acceptance ("a fetcher unit references its type unit and its conditions unit"), on the sakila
 * catalog.
 */
@PipelineTier
class CompileDependencyGraphPipelineTest {

    @Test
    void rootAndChildFetchersReferenceTargetProjectionsAndConditions() {
        var built = graphFor("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
              title: String
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { films: [Film!]! }
            """);
        var g = built.graph();
        String pkg = built.outputPackage();

        // Nodes: per-type files for the classified table types.
        assertThat(g.nodes()).contains(
            pkg + ".fetchers.FilmFetchers",
            pkg + ".fetchers.QueryFetchers",
            pkg + ".types.Film",
            pkg + ".types.Language",
            pkg + ".schema.FilmType");

        // Root query field: QueryFetchers references the Film projection and the QueryConditions class.
        assertThat(g.directReferences(pkg + ".fetchers.QueryFetchers")).contains(
            pkg + ".types.Film",
            pkg + ".conditions.QueryConditions");

        // Table-navigating child field (Film.language): FilmFetchers references the Language projection
        // and the FilmConditions class.
        assertThat(g.directReferences(pkg + ".fetchers.FilmFetchers")).contains(
            pkg + ".types.Language",
            pkg + ".conditions.FilmConditions");
    }

    private record Built(CompileDependencyGraph graph, String outputPackage) {}

    private static Built graphFor(String schemaText) {
        RewriteContext ctx = TestConfiguration.testContext();
        String prelude = directivesPrelude()
            + (schemaText.contains("interface Node") ? "" : "interface Node { id: ID! }\n");
        TypeDefinitionRegistry registry = new SchemaParser().parse(prelude + schemaText);
        GraphitronSchema model = GraphitronSchemaBuilder.buildBundle(registry, ctx).model();
        return new Built(CompileDependencyGraphBuilder.fromModel(model, ctx.outputPackage()), ctx.outputPackage());
    }

    private static String directivesPrelude() {
        try (InputStream is = RewriteSchemaLoader.class.getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not load directives", e);
        }
    }
}
