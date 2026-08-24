package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.plan.ConditionCommands;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage: a {@code @reference(path:)} filter whose terminal column lives on a
 * <em>joined</em> table lowers to a {@link BodyParam.RemoteColumnPredicate} (a correlated EXISTS at
 * emit), on <em>both</em> surfaces:
 *
 * <ul>
 *   <li><b>Surface 1</b> — an input-object {@code filter:} field (the motivating
 *       utdanningsregisteret bug: {@code STATUS_SELVAKKREDITERENDE} on {@code LARESTED} reached from
 *       {@code ORGANISASJON}, modelled here as a {@code country.country} filter on a {@code City}
 *       query reached through {@code city.country_id});</li>
 *   <li><b>Surface 2</b> — a direct scalar {@code ARGUMENT_DEFINITION}.</li>
 * </ul>
 *
 * <p>Assertions are at the model level (the {@link GeneratedConditionFilter}'s body params), per the
 * design principles; the EXISTS body shape itself is locked at the unit tier in
 * the glue renderer's per-arm tests, and semantic correctness at the execution tier in
 * {@code GraphQLQueryTest}. The discrimination guard (a <em>direct</em> nodeId FK-target stays
 * local), the element-less {@code path: []} degenerate case on both surfaces, and condition-join
 * hops in every position (hop 0, terminal, and mixed with FK hops in both orders) round out the
 * matrix. The condition-hop cases are the enforcers of the widened contract: neither surface
 * carries a shape check for those paths any more, and its absence is what they pin.
 */
@PipelineTier
class ReferenceFilterRemoteColumnPipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), "ReferenceFilterRemoteColumnPipelineTest", Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE);

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    // ===== Surface 1: input-object filter field =====

    @Test
    void surface1_inputFilterField_singleHopTable_lowersToRemotePredicate() {
        // The motivating bug shape: a @table input filter field whose @reference reaches a column on
        // a joined table. The terminal column `country.country` is absent from the local `city` table.
        var schema = TestSchemaHelper.buildSchema("""
            type Country @table(name: "country") { name: String @field(name: "country") }
            type City @table(name: "city") { name: String @field(name: "city") }
            input CityFilter {
                countryName: String @reference(path: [{table: "country"}]) @field(name: "country")
            }
            type Query { cities(filter: CityFilter): [City!]! }
            """);

        var remote = onlyRemotePredicate(schema, "cities");
        assertThat(remote.joinPath()).hasSize(1);
        assertThat(remote.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
        assertThat(remote.inner()).isInstanceOf(BodyParam.Eq.class);
        // The inner predicate binds the TERMINAL column, not a local one.
        assertThat(((BodyParam.Eq) remote.inner()).column().sqlName()).isEqualTo("country");
    }

    @Test
    void surface1_inputFilterField_singleHopKey_lowersToRemotePredicate() {
        // Same as above but the FK is named explicitly via {key:} rather than auto-discovered.
        var schema = TestSchemaHelper.buildSchema("""
            type Country @table(name: "country") { name: String @field(name: "country") }
            type City @table(name: "city") { name: String @field(name: "city") }
            input CityFilter {
                countryName: String @reference(path: [{key: "city_country_id_fkey"}]) @field(name: "country")
            }
            type Query { cities(filter: CityFilter): [City!]! }
            """);

        var remote = onlyRemotePredicate(schema, "cities");
        assertThat(remote.joinPath()).hasSize(1);
        assertThat(remote.inner()).isInstanceOf(BodyParam.Eq.class);
    }

    @Test
    void surface1_inputFilterField_multiHop_lowersToTwoHopRemotePredicate() {
        var schema = TestSchemaHelper.buildSchema("""
            type Country @table(name: "country") { name: String @field(name: "country") }
            type Address @table(name: "address") { line: String @field(name: "address") }
            input AddressFilter {
                countryName: String @reference(path: [{table: "city"}, {table: "country"}]) @field(name: "country")
            }
            type Query { addresses(filter: AddressFilter): [Address!]! }
            """);

        var remote = onlyRemotePredicate(schema, "addresses");
        assertThat(remote.joinPath()).hasSize(2);
        assertThat(remote.joinPath()).allMatch(TestFixtures::isFkHop);
        assertThat(((BodyParam.Eq) remote.inner()).column().sqlName()).isEqualTo("country");
    }

    @Test
    void surface1_listFilterField_lowersToRemoteInPredicate() {
        // A list-typed reference filter projects to an In inner (empty-list guard at emit).
        var schema = TestSchemaHelper.buildSchema("""
            type Country @table(name: "country") { name: String @field(name: "country") }
            type City @table(name: "city") { name: String @field(name: "city") }
            input CityFilter {
                countryNames: [String!] @reference(path: [{table: "country"}]) @field(name: "country")
            }
            type Query { cities(filter: CityFilter): [City!]! }
            """);

        var remote = onlyRemotePredicate(schema, "cities");
        assertThat(remote.inner()).isInstanceOf(BodyParam.In.class);
    }

    // ===== Surface 2: direct scalar argument =====

    @Test
    void surface2_scalarArg_singleHopTable_lowersToRemotePredicate() {
        var schema = TestSchemaHelper.buildSchema("""
            type City @table(name: "city") { name: String @field(name: "city") }
            type Query {
                citiesByCountry(
                    countryName: String @reference(path: [{table: "country"}]) @field(name: "country")
                ): [City!]!
            }
            """);

        var remote = onlyRemotePredicate(schema, "citiesByCountry");
        assertThat(remote.joinPath()).hasSize(1);
        assertThat(remote.inner()).isInstanceOf(BodyParam.Eq.class);
        assertThat(((BodyParam.Eq) remote.inner()).column().sqlName()).isEqualTo("country");
    }

    @Test
    void surface2_scalarArg_multiHop_lowersToTwoHopRemotePredicate() {
        var schema = TestSchemaHelper.buildSchema("""
            type Address @table(name: "address") { line: String @field(name: "address") }
            type Query {
                addressesByCountry(
                    countryName: String @reference(path: [{table: "city"}, {table: "country"}]) @field(name: "country")
                ): [Address!]!
            }
            """);

        var remote = onlyRemotePredicate(schema, "addressesByCountry");
        assertThat(remote.joinPath()).hasSize(2);
        assertThat(remote.joinPath()).allMatch(TestFixtures::isFkHop);
    }

    @Test
    void surface2_scalarListArg_lowersToRemoteInPredicate() {
        var schema = TestSchemaHelper.buildSchema("""
            type City @table(name: "city") { name: String @field(name: "city") }
            type Query {
                citiesByCountries(
                    countryNames: [String!] @reference(path: [{table: "country"}]) @field(name: "country")
                ): [City!]!
            }
            """);

        var remote = onlyRemotePredicate(schema, "citiesByCountries");
        assertThat(remote.inner()).isInstanceOf(BodyParam.In.class);
    }

    // ===== Discrimination guard: nodeId FK-target stays local (no EXISTS) =====

    @Test
    void nodeIdFkTargetInputField_staysLocal_notRemote() {
        // A *direct* @nodeId FK-target field lifts the decoded keys to FK-child columns on the
        // input's own table, so it binds FilterBinding.Local and must NOT be wrapped in a
        // RemoteColumnPredicate. This is the proof that the fork is the binding rather than the
        // carrier type: the same carrier reaching a translated FK binds Remote and does wrap
        // (NodeIdPipelineTest's translated-FK cases).
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! @nodeId }
            type Bar @table(name: "bar") { idOne: String @field(name: "ID_1") }
            input BarFilter {
                relatedId: ID @nodeId(typeName: "Baz")
            }
            type Query { bars(filter: BarFilter): [Bar!]! }
            """, FIXTURE_CTX);

        var bodyParams = bodyParams(schema, "bars");
        assertThat(bodyParams).hasSize(1);
        assertThat(bodyParams.get(0))
            .as("nodeId FK-target lifts to a local column predicate, not a remote EXISTS")
            .isInstanceOf(BodyParam.Eq.class)
            .isNotInstanceOf(BodyParam.RemoteColumnPredicate.class);
    }

    // ===== Element-less path: the directive is inert, the binding is Local =====

    @Test
    void surface2_scalarArg_elementLessPath_bindsLocal() {
        // `@reference(path: [])` is legal SDL (`path` is `[ReferenceElement!]!`) and on an argument
        // it is inert: empty-path FK inference needs a target table, and an argument site has none,
        // so the path stays empty and the column resolves against the field's own table. The
        // predicate is therefore the bare local Eq the directive-less arm emits, not an EXISTS with
        // no terminal table to reach. Both halves matter: the emitted shape, and that classification
        // completes at all (binding Remote over an empty path trips the carrier's own invariant and
        // throws out of classify).
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { films(title: String @reference(path: [])): [Film!]! }
            """);

        var bodyParams = bodyParams(schema, "films");
        assertThat(bodyParams).hasSize(1);
        assertThat(bodyParams.get(0))
            .isInstanceOf(BodyParam.Eq.class)
            .isNotInstanceOf(BodyParam.RemoteColumnPredicate.class);
        assertThat(((BodyParam.Eq) bodyParams.get(0)).column().sqlName()).isEqualTo("title");
    }

    @Test
    void surface1_inputFilterField_elementLessPath_bindsLocal() {
        // The input-field sibling of the case above, same reasoning and same answer. Pinned
        // alongside it because the two sites classify the same directive independently, and it was
        // their divergence (one forking on path emptiness, the other asserting Remote outright)
        // that let the argument arm ship a crash.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter { title: String @reference(path: []) }
            type Query { films(filter: FilmFilter): [Film!]! }
            """);

        var bodyParams = bodyParams(schema, "films");
        assertThat(bodyParams).hasSize(1);
        assertThat(bodyParams.get(0))
            .isInstanceOf(BodyParam.Eq.class)
            .isNotInstanceOf(BodyParam.RemoteColumnPredicate.class);
        assertThat(((BodyParam.Eq) bodyParams.get(0)).column().sqlName()).isEqualTo("title");
    }

    // ===== Condition-join reference-filter paths: accepted on both surfaces =====

    @Test
    void surface2_conditionJoinPath_lowersToRemotePredicate() {
        // A {condition:} hop in a filter path is a reach hop like any other: the reach emission
        // dispatches per hop on the On seal, so the developer's predicate becomes the hop's ON
        // inside the same correlated EXISTS an FK hop's column pairs produce. This case was the
        // only rejection pin either surface had; it is now the acceptance pin.
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") { title: String }
            type Query {
                films(
                    actorFirstName: String @reference(path: [
                        {condition: {className: "%s", method: "intermediate"}},
                        {table: "actor"}
                    ]) @field(name: "first_name")
                ): [Film!]!
            }
            """.formatted(STUB));

        var remote = onlyRemotePredicate(schema, "films");
        assertThat(remote.joinPath()).hasSize(2);
        assertThat(remote.joinPath().get(0)).matches(TestFixtures::isConditionHop, "condition-join hop");
        assertThat(remote.joinPath().get(1)).matches(TestFixtures::isFkHop, "FK-derived hop");
        assertThat(((BodyParam.Eq) remote.inner()).column().sqlName()).isEqualTo("first_name");
        assertThat(reachOf(schema, "films").hops()).hasSize(2);
    }

    @Test
    void surface1_inputFilterField_conditionJoinPath_lowersToRemotePredicate() {
        // The input-field surface's first condition-hop case, and its first enforcement in either
        // direction: the retired validator mirror was never test-asserted here.
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                actorFirstName: String @field(name: "first_name") @reference(path: [
                    {condition: {className: "%s", method: "intermediate"}},
                    {table: "actor"}
                ])
            }
            type Query { films(filter: FilmFilter): [Film!]! }
            """.formatted(STUB));

        var remote = onlyRemotePredicate(schema, "films");
        assertThat(remote.joinPath()).hasSize(2);
        assertThat(remote.joinPath().get(0)).matches(TestFixtures::isConditionHop, "condition-join hop");
        assertThat(((BodyParam.Eq) remote.inner()).column().sqlName()).isEqualTo("first_name");
        assertThat(reachOf(schema, "films").hops()).hasSize(2);
    }

    @Test
    void surface2_conditionThenKeyPath_lowersInAuthoredHopOrder() {
        // Mixed path, condition first: the reach carries the hops in authored order, so the
        // renderer's walk-back and its hop-0 correlation land on the hops the author named.
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") { title: String }
            type Query {
                films(
                    actorFirstName: String @field(name: "first_name") @reference(path: [
                        {condition: {className: "%s", method: "intermediate"}},
                        {key: "film_actor_actor_id_fkey"}
                    ])
                ): [Film!]!
            }
            """.formatted(STUB));

        var reach = reachOf(schema, "films");
        assertThat(reach.hop(0).on()).isInstanceOf(On.Predicate.class);
        assertThat(reach.hop(1).on()).isInstanceOf(On.ColumnPairs.class);
        assertThat(reach.hop(1).targetTable().tableName()).isEqualToIgnoringCase("actor");
    }

    @Test
    void surface2_keyThenConditionPath_lowersInAuthoredHopOrder() {
        // The other order: an FK hop-0 correlation with a terminal condition hop, which is the
        // bridging shape the output rail already executes. Both orders matter because hop 0 and
        // the interior hops go through different dispatch points.
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") { title: String }
            type Query {
                films(
                    actorFirstName: String @field(name: "first_name") @reference(path: [
                        {key: "film_actor_film_id_fkey"},
                        {condition: {className: "%s", method: "junctionToActor"}}
                    ])
                ): [Film!]!
            }
            """.formatted(STUB));

        var reach = reachOf(schema, "films");
        assertThat(reach.hop(0).on()).isInstanceOf(On.ColumnPairs.class);
        assertThat(reach.hop(1).on()).isInstanceOf(On.Predicate.class);
        assertThat(reach.hop(1).targetTable().tableName()).isEqualToIgnoringCase("actor");
    }

    // ===== helpers =====

    /**
     * The lowered reach of the coordinate's single remote term: the command-tier view of the
     * classified path, which is what the renderer consumes. Asserting it here rather than the
     * generated body keeps this tier's grain (rows, not code strings).
     */
    private static ReachPath reachOf(GraphitronSchema schema, String queryFieldName) {
        var rows = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE).rows();
        var row = rows.stream()
            .filter(r -> r.coordinate().getFieldName().equals(queryFieldName))
            .findFirst().orElseThrow(() -> new AssertionError("no condition row for " + queryFieldName));
        var generated = row.predicates().stream()
            .filter(Predicate.Generated.class::isInstance)
            .map(Predicate.Generated.class::cast)
            .findFirst().orElseThrow(() -> new AssertionError("no generated predicate on " + queryFieldName));
        assertThat(generated.terms()).hasSize(1);
        return generated.terms().get(0).reach();
    }

    private static List<BodyParam> bodyParams(GraphitronSchema schema, String queryFieldName) {
        var field = (SqlGeneratingField) schema.field("Query", queryFieldName);
        var gcf = (GeneratedConditionFilter) field.filters().stream()
            .filter(GeneratedConditionFilter.class::isInstance)
            .findFirst().orElseThrow(() -> new AssertionError("no GeneratedConditionFilter on " + queryFieldName));
        return gcf.bodyParams();
    }

    private static BodyParam.RemoteColumnPredicate onlyRemotePredicate(GraphitronSchema schema, String queryFieldName) {
        var bps = bodyParams(schema, queryFieldName);
        assertThat(bps).hasSize(1);
        assertThat(bps.get(0)).isInstanceOf(BodyParam.RemoteColumnPredicate.class);
        return (BodyParam.RemoteColumnPredicate) bps.get(0);
    }
}
