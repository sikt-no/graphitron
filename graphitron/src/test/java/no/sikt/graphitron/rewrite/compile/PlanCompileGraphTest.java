package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the plan-projected {@link CompileDependencyGraph} ({@link PlanCompileGraph}) over a
 * realistically classified SDL: the node population (every relation's committed refs, nothing
 * else), the precise per-relation edges (a launcher's projection and WHERE glue, a projection
 * unit's callees and glue calls, a condition row's decode facts, the node-lookup delegation),
 * the declared blanket (frozen scaffolds, wiring hub), and the reverse-edge mirror. The
 * projection's two-directional acceptance against the real emit artifact lives in
 * {@code IncrementalCompileHarnessTest}'s three-leg oracle; this test pins the projected shape
 * as data, without generation.
 */
@PipelineTier
class PlanCompileGraphTest {

    private static final String PKG = "com.example.gen";
    private static final GeneratedUnits UNITS = new GeneratedUnits(PKG);

    /**
     * Root table read with a column filter (launcher row + root condition row), node lookup
     * (fetcher edge row), an encode-carrying {@code @nodeId} id column, an inline single
     * reference (projection {@code Call}), a fetcher-owning nesting type, and an inline list
     * reference filtered by a {@code @nodeId}-decoding input (projection glue + decode-carrying
     * condition row).
     */
    private static final String SDL = """
        type Query {
          films(title: String @field(name: "title")): [Film!]!
          node(id: ID!): Node
        }

        type Film implements Node @table(name: "film") @node {
          id: ID! @nodeId
          title: String
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
          meta: FilmMeta
        }

        type FilmMeta {
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") {
          name: String
          films(filter: FilmFilter): [Film!] @reference(path: [{key: "film_language_id_fkey"}])
        }

        input FilmFilter {
          ids: [ID!] @nodeId(typeName: "Film")
        }
        """;

    private static CompileDependencyGraph graph;

    @BeforeAll
    static void projectGraph() {
        GraphitronSchemaBuilder.Bundle bundle = TestSchemaHelper.buildBundle(SDL);
        EmitPlan plan = EmitPlan.produce(bundle.model(), bundle.federationLink(), bundle.usesOneOf(),
            SessionStateConfig.none(), PKG);
        graph = PlanCompileGraph.fromPlan(plan, bundle.model());
    }

    private static Set<String> nodes() {
        return graph.nodes().stream().map(UnitRef::fqcn).collect(Collectors.toSet());
    }

    private static Set<String> refs(UnitRef unit) {
        return graph.directReferences(unit).stream().map(UnitRef::fqcn).collect(Collectors.toSet());
    }

    private static Set<String> dependents(UnitRef unit) {
        return graph.directDependents(unit).stream().map(UnitRef::fqcn).collect(Collectors.toSet());
    }

    @Test
    void committedUnitsAreNodes() {
        assertThat(nodes()).contains(
            PKG + ".fetchers.FilmFetchers",
            PKG + ".fetchers.QueryFetchers",
            PKG + ".types.Film",
            PKG + ".types.Language",
            PKG + ".schema.FilmType",
            // globals
            PKG + ".util.NodeIdEncoder",
            PKG + ".util.LightFetcher",
            PKG + ".schema.GraphitronSchema",
            PKG + ".fetchers.QueryNodeFetcher",
            PKG + ".Graphitron",
            // condition glue units (the relation's committed refs; no membership predicate here)
            PKG + ".conditions.QueryConditions",
            PKG + ".conditions.LanguageConditions");
    }

    @Test
    void overCollectedNodesOfTheRetiredBuilderAreGone() {
        // The input type is not argument-reachable as a record carrier here and Film hosts no
        // condition row, so neither phantom node of the retired model-sourced builder exists:
        // node membership is committed refs, not per-type possibility.
        assertThat(nodes()).doesNotContain(PKG + ".conditions.FilmConditions");
        // The session hook implementation is committed only when session state is configured.
        assertThat(nodes()).doesNotContain(PKG + ".schema.GraphitronSessionHook");
    }

    @Test
    void launcherRowCarriesProjectionAndWhereGlueEdges() {
        // Query.films is a launcher row: its rows method projects the target's $project and
        // calls the coordinate's glue (the filtered root).
        assertThat(refs(UNITS.fetchers("Query"))).contains(
            PKG + ".types.Film",
            PKG + ".conditions.QueryConditions");
    }

    @Test
    void nodeLookupDelegatesToTheNodeFetcherUnit() {
        // node(id:) delegates to the QueryNodeFetcher dispatch unit (the fetcher edge relation's
        // row); the entry itself does not touch the encoder, so no NodeIdEncoder edge rides it.
        assertThat(refs(UNITS.fetchers("Query"))).contains(PKG + ".fetchers.QueryNodeFetcher");
        assertThat(refs(UNITS.fetchers("Query"))).doesNotContain(PKG + ".util.NodeIdEncoder");
    }

    @Test
    void nodeFetcherAndEntityDispatchCarryTheirFixedEdges() {
        assertThat(refs(UNITS.queryNodeFetcher())).contains(
            PKG + ".util.NodeIdEncoder",
            PKG + ".util.EntityFetcherDispatch",
            PKG + ".schema.GraphitronContext");
        // The dispatch row's schema-dependent targets (the node type joins through the
        // @node-to-@key synthesis) plus its fixed encoder/context edges.
        assertThat(refs(UNITS.singleton(GeneratedUnits.SUB_UTIL, "EntityFetcherDispatch"))).contains(
            PKG + ".types.Film",
            PKG + ".util.NodeIdEncoder",
            PKG + ".schema.GraphitronContext");
    }

    @Test
    void everyFetcherBlanketsTheFrozenScaffolding() {
        assertThat(refs(UNITS.fetchers("Query"))).contains(
            PKG + ".util.LightFetcher",
            PKG + ".util.ConnectionResult",
            PKG + ".schema.Outcome",
            PKG + ".schema.GraphitronContext");
    }

    @Test
    void encodeCarryingFetcherReachesNodeIdEncoderPrecisely() {
        // Film.id is a @nodeId-encoded column: its bound fetcher encodes, so the leaf-derived
        // precise edge lands on FilmFetchers; Language hosts no encode leaf and gets none (the
        // per-type-growing encoder is never blanketed).
        assertThat(refs(UNITS.fetchers("Film"))).contains(PKG + ".util.NodeIdEncoder");
        assertThat(refs(UNITS.fetchers("Language"))).doesNotContain(PKG + ".util.NodeIdEncoder");
    }

    @Test
    void schemaClassWiresFetchersAndFacadeWiresSchema() {
        assertThat(refs(UNITS.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronSchema"))).contains(
            PKG + ".fetchers.QueryFetchers",
            PKG + ".fetchers.FilmFetchers",
            PKG + ".schema.FilmType",
            PKG + ".fetchers.QueryNodeFetcher");
        assertThat(refs(UNITS.rootUnit("Graphitron"))).contains(
            PKG + ".schema.GraphitronSchema",
            PKG + ".schema.GraphitronContext");
    }

    @Test
    void typeClassReferencesInlineProjectionTargetProjection() {
        // Film.language composes Language.$project(...) inline, so the projection relation's
        // Call contribution yields types.Film -> types.Language.
        assertThat(refs(UNITS.typeClass("Film"))).contains(PKG + ".types.Language");
    }

    @Test
    void projectionUnitsBlanketClientExceptionAndSelectionOccurrences() {
        for (String typeName : new String[] {"Film", "Language"}) {
            assertThat(refs(UNITS.typeClass(typeName))).contains(
                PKG + ".schema.GraphitronClientException",
                PKG + ".util.SelectionOccurrences");
        }
    }

    @Test
    void glueUnitReachesNodeIdEncoderOnlyWhenAFilterDecodesANodeId() {
        // Language.films' filter decodes @nodeId ids, so its glue row carries the decode fact:
        // the precise encoder edge (plus the THROW-mode helper's client-error reference) lands
        // on the glue unit. Query.films' title filter decodes nothing, so its glue gets neither.
        assertThat(refs(UNITS.conditions("Language"))).contains(
            PKG + ".util.NodeIdEncoder",
            PKG + ".schema.GraphitronClientException");
        assertThat(refs(UNITS.conditions("Query"))).doesNotContain(PKG + ".util.NodeIdEncoder");
    }

    @Test
    void typeClassReferencesGlueUnitOfInlineFilter() {
        // The inline filtered list on Language emits one glue call inside Language.$project, so
        // the hosting projection unit references the coordinate's conditions unit (keyed by the
        // field's parent, Language).
        assertThat(refs(UNITS.typeClass("Language"))).contains(PKG + ".conditions.LanguageConditions");
    }

    @Test
    void nestingHostedInlineFieldAttributesEdgeToItsNestedUnit() {
        // Film.meta splices the anchor-prefixed nested unit, and the nested field's inline
        // target edge attaches to that unit, never to a phantom types.FilmMeta: the projection
        // relation's rows carry the attribution, with no ancestry recovery anywhere.
        assertThat(refs(UNITS.typeClass("Film"))).contains(PKG + ".types.FilmFilmMeta");
        assertThat(refs(UNITS.nestingUnit("Film", "FilmMeta"))).contains(PKG + ".types.Language");
        assertThat(nodes()).doesNotContain(PKG + ".types.FilmMeta");
    }

    @Test
    void fetcherOwningNestingTypeRegistersFetcherNodeAndWiringEdges() {
        // FilmMeta owns a classified field, so the type-unit relation commits FilmMetaFetchers
        // and its schema shape wires it; the blanket covers the nested fetcher like any other.
        assertThat(nodes()).contains(PKG + ".fetchers.FilmMetaFetchers");
        assertThat(refs(UNITS.schemaShape("FilmMeta"))).contains(PKG + ".fetchers.FilmMetaFetchers");
        assertThat(refs(UNITS.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronSchema")))
            .contains(PKG + ".fetchers.FilmMetaFetchers");
        assertThat(refs(UNITS.fetchers("FilmMeta"))).contains(PKG + ".schema.GraphitronContext");
    }

    @Test
    void reverseEdgesMirrorForwardEdges() {
        assertThat(dependents(UNITS.typeClass("Film")))
            .contains(PKG + ".fetchers.QueryFetchers");
        assertThat(dependents(UNITS.fetchers("Film")))
            .contains(PKG + ".schema.GraphitronSchema");
    }
}
