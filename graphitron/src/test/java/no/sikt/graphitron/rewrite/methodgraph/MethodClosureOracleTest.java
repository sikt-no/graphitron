package no.sikt.graphitron.rewrite.methodgraph;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The level-1 closure oracle over the emit. The model's central falsifiable invariant is that
 * the emit target is a <em>referentially-closed</em> graph of emitted methods: every method-name
 * reference in every emitted body resolves to a method the generator also emitted. This harness
 * generates over a schema exercising the load-bearing seam families, walks the emitted
 * {@link TypeSpec}s with {@link EmittedMethodClosure}, and asserts closure.
 *
 * <p>Level 1 proves only that names, however derived, resolve. Two invariants this oracle
 * deliberately does <b>not</b> cover: the correlation-key projection contract (facts, not
 * method names; the gated arms in {@code ProjectionCommands} and the capability-to-membership
 * census own it) and how the names themselves are derived.
 *
 * <p>The schema spans the seam families: root select and child reference (Fetcher, Projection),
 * {@code @splitQuery} (rows-method, scatter), a Relay connection with {@code @orderBy}
 * (pagination, order-by), {@code @lookupKey} (input-rows), a {@code @nodeId} filter input
 * (conditions, node codec), a nesting type, a table-bound {@code @service} child, and a DML
 * insert projecting its {@code @table} return (record instantiation, mutation reentry).
 */
@PipelineTier
class MethodClosureOracleTest {

    private static final String OUTPUT_PACKAGE = TestConfiguration.DEFAULT_OUTPUT_PACKAGE;

    private static final String SCHEMA = """
        interface Node { id: ID! }

        type Query {
          film: Film
          films: [Film!]! @asConnection
          node(id: ID!): Node
          consumeFilm(in: FilmRecordInput): String @service(
            service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "consumeFilmRecord"}
          )
        }

        input FilmRecordInput { title: String }

        type Film implements Node @table(name: "film") @node {
          id: ID! @nodeId
          title: String
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
          actors: [Actor!]! @splitQuery
              @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
          actorsConnection(
              order: [ActorOrderBy] @orderBy, first: Int, last: Int, after: String, before: String
          ): ActorsConnection! @splitQuery
              @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
              @defaultOrder(primaryKey: true)
          details: FilmDetails
        }

        enum ActorSort { ACTOR_ID @order(primaryKey: true) }
        input ActorOrderBy { field: ActorSort! direction: SortDirection }
        type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
        type ActorsEdge { node: Actor! cursor: String! }
        type ActorsConnection { edges: [ActorsEdge!]! nodes: [Actor!]! }

        type FilmDetails {
          actorsByLookup(actor_id: [Int!] @lookupKey): [Actor!]! @reference(path: [
            {key: "film_actor_film_id_fkey"},
            {key: "film_actor_actor_id_fkey"}
          ])
        }

        type Language @table(name: "language") {
          name: String
          films(filter: FilmFilter): [Film!] @reference(path: [{key: "film_language_id_fkey"}])
          filmsViaService: [Film!]! @service(
            service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"}
          )
        }

        input FilmFilter {
          ids: [ID!] @nodeId(typeName: "Film")
        }

        input FilmInput { title: String }

        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
        }
        """;

    private static EmittedMethodClosure walk;

    @BeforeAll
    static void generateAndWalk(@TempDir Path workDir) throws Exception {
        Path schemaFile = workDir.resolve("schema.graphqls");
        Files.writeString(schemaFile, SCHEMA);
        RewriteContext ctx = new RewriteContext(
            List.of(SchemaInput.file(schemaFile)),
            workDir, "MethodClosureOracleTest",
            workDir.resolve("generated-sources"),
            OUTPUT_PACKAGE,
            TestConfiguration.DEFAULT_JOOQ_PACKAGE);
        walk = EmittedMethodClosure.walk(new GraphQLRewriteGenerator(ctx).generate().emittedUnits());
    }

    @Test
    void everyQualifiedCalleeResolvesToAnEmittedMethod() {
        assertThat(walk.unresolved())
            .as("callee names referencing generated classes with no emitted method behind them%n"
                + "(a violation means an emitter minted or retyped a callee name the emit does not declare)")
            .isEmpty();
    }

    /**
     * Non-vacuity floor for the <b>edge relation</b>: the walk actually sees the cross-unit seam
     * families whose callee names are class-qualified, so a scan regression cannot pass the
     * closure check by seeing nothing. Pinned at family granularity (a witness filter per family,
     * not an exhaustive edge list): the closure assertion is the gate, these pins only prove the
     * gate is fed, and family granularity keeps them stable across code-shape changes.
     */
    @Test
    void walkSeesTheLoadBearingSeamFamilies() {
        String pkg = OUTPUT_PACKAGE;

        // Fetcher -> Projection: a fetcher launches a SELECT over the target type's
        // projection (QueryFetchers -> types.Film#$project).
        assertThat(walk.edges().stream()
            .filter(e -> e.fromUnit().startsWith(pkg + ".fetchers."))
            .filter(e -> e.methodName().equals("$project")))
            .as("Fetcher -> Projection ($project) edges").isNotEmpty();

        // Projection -> Projection: an inline reference composes the nested type's $project
        // from inside another type class (types.Film <-> types.Language).
        assertThat(walk.edges().stream()
            .filter(e -> e.fromUnit().startsWith(pkg + ".types."))
            .filter(e -> e.targetUnit().startsWith(pkg + ".types."))
            .filter(e -> e.methodName().equals("$project")))
            .as("Projection -> Projection (recursive $project) edges").isNotEmpty();

        // Condition: a generated condition method is called by name, from the root
        // fetcher (QueryConditions) and from an inline reference arm (FilmConditions).
        assertThat(walk.edges().stream()
            .filter(e -> e.targetUnit().endsWith("Conditions")))
            .as("-> Conditions edges").isNotEmpty();

        // Node codec: the @nodeId encode on the read side, decode on the filter side, both
        // crossing into the util.NodeIdEncoder unit by name.
        assertThat(walk.edges().stream()
            .filter(e -> e.targetUnit().equals(pkg + ".util.NodeIdEncoder")))
            .as("-> NodeIdEncoder codec edges").isNotEmpty();

        // Fetcher wiring: the schema-shape classes reference the per-type fetcher
        // classes' field methods (schema.FilmType -> fetchers.FilmFetchers#actors, ...).
        assertThat(walk.edges().stream()
            .filter(e -> e.fromUnit().startsWith(pkg + ".schema."))
            .filter(e -> e.targetUnit().endsWith("Fetchers")))
            .as("schema shape -> Fetchers wiring edges").isNotEmpty();

        // Reentry: a table-bound @service child and a DML insert both re-project their
        // @table return through the target type's $project.
        assertThat(walk.hasEdge(pkg + ".fetchers.LanguageFetchers", pkg + ".types.Film", "$project"))
            .as("service reentry: LanguageFetchers -> types.Film#$project").isTrue();
        assertThat(walk.hasEdge(pkg + ".fetchers.MutationFetchers", pkg + ".types.Film", "$project"))
            .as("DML reentry: MutationFetchers -> types.Film#$project").isTrue();
    }

    /**
     * Non-vacuity floor for the <b>node relation</b>, covering the families whose call sites
     * are <em>same-class and unqualified</em> in the emit and therefore invisible to the
     * level-1 edge scan: the rows-methods, scatter, order-by, the service load method, and
     * the record-instantiation helpers. Level 1 pins them as emitted nodes; their call edges
     * are not assertable at this level.
     */
    @Test
    void nodeRelationCarriesTheCommandGranularities() {
        String pkg = OUTPUT_PACKAGE;

        // Type-granular fold and field-granular fetchers (1:1, total).
        assertThat(declaredIn(pkg + ".types.Film")).contains("$project");
        assertThat(declaredIn(pkg + ".fetchers.QueryFetchers")).contains("film", "films", "node");

        // Anchor-granular rows-methods, dedup-by-class scatter, per-field order-by
        // (the @splitQuery and connection family, same-class callees today).
        assertThat(declaredIn(pkg + ".fetchers.FilmFetchers"))
            .contains("actors", "rowsActors", "scatterByIdx",
                "actorsConnection", "rowsActorsConnection", "scatterConnectionByIdx",
                "actorsConnectionOrderBy");

        // The service reentry load method.
        assertThat(declaredIn(pkg + ".fetchers.LanguageFetchers"))
            .contains("filmsViaService", "loadFilmsViaService");

        // Boundary helpers: the jOOQ-record instantiation pair at the @service
        // parameter position, and the input carrier's fromMap factory.
        assertThat(declaredIn(pkg + ".fetchers.QueryFetchers"))
            .contains("createFilmRecord", "createFilmRecordList");
        assertThat(declaredIn(pkg + ".inputs.FilmInput")).contains("fromMap");
    }

    private java.util.Set<String> declaredIn(String unit) {
        Map<String, java.util.Set<String>> byPath = walk.declaredMethods().get(unit);
        assertThat(byPath).as("emitted unit %s", unit).isNotNull();
        return byPath.get("");
    }
}
