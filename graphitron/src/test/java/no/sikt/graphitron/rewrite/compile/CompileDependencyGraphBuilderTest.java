package no.sikt.graphitron.rewrite.compile;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410 slice 2 — unit coverage of {@link CompileDependencyGraphBuilder} over a hand-built classified
 * model, exercising the node switch (per-type file contributions + singletons), the structural edge
 * projection (a fetcher references its target type's projection class and its parent conditions), the
 * blanket frozen-scaffolding edges, and the wiring-hub edges (schema class references every fetcher;
 * facade references the schema). The exhaustiveness of both switches is a compile-time guarantee; this
 * test pins the per-leaf edge/node payloads.
 */
@UnitTier
class CompileDependencyGraphBuilderTest {

    private static final String PKG = "com.example.gen";

    private static TableRef filmTable() {
        return new TableRef(
            "film", "FILM",
            ClassName.get("jooq.tables", "Film"),
            ClassName.get("jooq.tables.records", "FilmRecord"),
            ClassName.get("jooq", "Tables"),
            List.of(),
            List.of());
    }

    /** A minimal two-type schema: {@code type Film @table} + {@code Query { films: [Film] }}. */
    private static GraphitronSchema filmQuerySchema() {
        var types = new LinkedHashMap<String, GraphitronType>();
        types.put("Film", new GraphitronType.TableType("Film", null, filmTable()));
        types.put("Query", new GraphitronType.RootType("Query", null));

        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Query", "films"),
            new QueryField.QueryTableField(
                "Query", "films", null,
                new ReturnTypeRef.TableBoundReturnType("Film", filmTable(),
                    new FieldWrapper.List(false, true)),
                List.of(), new OrderBySpec.None(), null));

        return new GraphitronSchema(types, fields);
    }

    @Test
    void perTypeFilesAndSingletonsAreNodes() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        assertThat(g.nodes()).contains(
            PKG + ".fetchers.FilmFetchers",
            PKG + ".types.Film",
            PKG + ".schema.FilmType",
            PKG + ".fetchers.QueryFetchers",
            // singletons
            PKG + ".util.NodeIdEncoder",
            PKG + ".util.LightFetcher",
            PKG + ".schema.GraphitronSchema",
            PKG + ".Graphitron");
    }

    @Test
    void queryFetcherReferencesTargetProjectionAndConditions() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        // The spec's slice-2 acceptance example: "a fetcher unit references its type unit and its
        // conditions unit." Query.films is a QueryTableField (SQL-generating), so QueryFetchers
        // references the Film projection class and the QueryConditions class.
        assertThat(g.directReferences(PKG + ".fetchers.QueryFetchers")).contains(
            PKG + ".types.Film",
            PKG + ".conditions.QueryConditions");
    }

    @Test
    void everyFetcherBlanketsTheFrozenScaffolding() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        assertThat(g.directReferences(PKG + ".fetchers.QueryFetchers")).contains(
            PKG + ".util.LightFetcher",
            PKG + ".util.ConnectionResult",
            PKG + ".schema.Outcome");
        // NodeIdEncoder is per-type-growing: it is NOT blanketed (no encode used in this schema).
        assertThat(g.directReferences(PKG + ".fetchers.QueryFetchers"))
            .doesNotContain(PKG + ".util.NodeIdEncoder");
    }

    @Test
    void schemaClassWiresFetchersAndFacadeWiresSchema() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        assertThat(g.directReferences(PKG + ".schema.GraphitronSchema")).contains(
            PKG + ".fetchers.QueryFetchers",
            PKG + ".fetchers.FilmFetchers",
            PKG + ".schema.FilmType");
        assertThat(g.directReferences(PKG + ".Graphitron")).contains(
            PKG + ".schema.GraphitronSchema",
            PKG + ".schema.GraphitronContext");
    }

    @Test
    void reverseEdgesMirrorForwardEdges() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        // FilmType is referenced by the schema class, so the schema class is among its dependents.
        assertThat(g.directDependents(PKG + ".types.Film"))
            .contains(PKG + ".fetchers.QueryFetchers");
        assertThat(g.directDependents(PKG + ".fetchers.FilmFetchers"))
            .contains(PKG + ".schema.GraphitronSchema");
    }
}
