package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
 * {@code TypeConditionsGeneratorTest}, and semantic correctness at the execution tier in
 * {@code GraphQLQueryTest}. The discrimination guard (nodeId FK-target stays local) and the
 * condition-join-path rejection round out the matrix.
 */
@PipelineTier
class ReferenceFilterRemoteColumnPipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of());

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    // ===== Surface 1: input-object filter field =====

    @Test
    void surface1_inputFilterField_singleHopTable_lowersToRemotePredicate() {
        // The motivating bug shape: a @table input filter field whose @reference reaches a column on
        // a joined table. The terminal column `country.country` is absent from the local `city` table.
        var schema = TestSchemaHelper.buildSchema("""
            type Country @table(name: "country") { name: String @field(name: "country") }
            type City @table(name: "city") { name: String @field(name: "city") }
            input CityFilter @table(name: "city") {
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
            input CityFilter @table(name: "city") {
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
            input AddressFilter @table(name: "address") {
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
            input CityFilter @table(name: "city") {
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
        // An @nodeId FK-target field lifts the decoded keys to FK-child columns on the input's *own*
        // table (no join needed); it must NOT be wrapped in a RemoteColumnPredicate. This is the
        // proof the nodeId-vs-plain-@reference fork (Direct vs NodeIdDecodeKeys extraction) holds.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz @table(name: "baz") { id: ID! }
            type Bar @table(name: "bar") { idOne: String @field(name: "ID_1") }
            input BarFilter @table(name: "bar") {
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

    // ===== Condition-join reference-filter path: clean rejection =====

    @Test
    void surface2_conditionJoinPath_isRejected() {
        // v1 supports Fk-join reference-filter paths only; a {condition:} hop is deferred and must
        // surface as a clean (typed) rejection rather than an emitter IllegalStateException. Uses a
        // resolvable *intermediate* condition hop (film -> film_actor) with an FK terminal so the
        // path parses to a condition-join step and the classifier's foreign-key-only guard fires.
        var f = field("""
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
            """.formatted(STUB), "Query", "films");
        assertThat(f).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) f).reason())
            .contains("condition-join")
            .contains("foreign key");
    }

    // ===== helpers =====

    private static GraphitronField field(String sdl, String type, String name) {
        return TestSchemaHelper.buildSchema(sdl).field(type, name);
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
