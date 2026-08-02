package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.facts.ConditionFacts;
import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The population pins for the operation trigger slots landed at the keystone: one fixture
 * exercising every population of the condition, orderBy, lookup, service and write gathers,
 * with each slot's rows asserted against the fixture's authored applications by coordinate.
 * The pagination slot's pins live in {@link PaginationFactPipelineTest}; the fixture here
 * includes coordinates outside every population, so an over-gathering visitor fails as loudly
 * as an empty relation.
 */
@PipelineTier
class TriggerFactPopulationPinTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    private static GatheredFacts gatherFixture() {
        var bundle = TestSchemaHelper.buildBundle("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                title: String
                language(name: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: name"}, override: true)): Language
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            input LookupInput {
                language_id: Int @lookupKey @field(name: "language_id")
                name: String @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: name"})
            }
            enum LangOrderField { NAME @order(primaryKey: true) }
            enum Direction { ASC DESC }
            input LangOrder { sortField: LangOrderField! direction: Direction! }
            type Query {
                films(title: String @field(name: "TITLE")): [Film!]!
                    @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: title"})
                languagesByKey(language_id: [Int] @lookupKey @field(name: "language_id")): [Language!]!
                byInput(in: LookupInput): [Language!]!
                stub: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
                ordered: [Language!]! @defaultOrder(primaryKey: true)
                sorted(order: LangOrder @orderBy): [Language!]!
                untouched: [Language!]!
            }
            type Mutation {
                createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
                deleteFilm(id: ID!): ID @mutation(typeName: DELETE, multiRow: true, table: "film")
            }
            input FilmInput { title: String }
            """.formatted(STUB, STUB, STUB));
        return GatheredFacts.gather(bundle.assembled(), SchemaReachability::walk);
    }

    @Test
    void conditionSlotGathersTheAuthoredPopulations() {
        var facts = gatherFixture();
        assertThat(facts.condition().rows())
            .as("field rows: one per coordinate with a field- or argument-level application")
            .extracting(r -> r.parentTypeName() + "." + r.fieldName())
            .containsExactlyInAnyOrder("Query.films", "Film.language");
        var fieldLevel = facts.condition().rows().stream()
            .filter(r -> r.fieldName().equals("films")).findFirst().orElseThrow();
        assertThat(fieldLevel.onField()).isTrue();
        assertThat(fieldLevel.fieldOverride()).isFalse();
        assertThat(fieldLevel.argSites()).isEmpty();
        var argLevel = facts.condition().rows().stream()
            .filter(r -> r.fieldName().equals("language")).findFirst().orElseThrow();
        assertThat(argLevel.onField()).isFalse();
        assertThat(argLevel.argSites())
            .containsExactly(new ConditionFacts.ArgSite("name", true));
        assertThat(facts.condition().inputRows())
            .as("input-field rows: one per reachable input object field application")
            .extracting(r -> r.inputTypeName() + "." + r.fieldName())
            .containsExactlyInAnyOrder("LookupInput.name");
    }

    @Test
    void orderBySlotGathersBothAuthoredPopulations() {
        var facts = gatherFixture();
        assertThat(facts.orderBy().rows())
            .extracting(r -> r.parentTypeName() + "." + r.fieldName() + ":"
                + r.orderByArgs() + ":" + r.defaultOrder())
            .containsExactlyInAnyOrder("Query.ordered:[]:true", "Query.sorted:[order]:false");
    }

    @Test
    void lookupSlotGathersArgumentAndInputFieldSites() {
        var facts = gatherFixture();
        assertThat(facts.lookup().rows())
            .extracting(r -> r.parentTypeName() + "." + r.fieldName() + ":" + r.lookupArgs())
            .containsExactlyInAnyOrder("Query.languagesByKey:[language_id]");
        assertThat(facts.lookup().inputRows())
            .extracting(r -> r.inputTypeName() + "." + r.fieldName())
            .containsExactlyInAnyOrder("LookupInput.language_id");
    }

    @Test
    void serviceSlotGathersTheApplicationSites() {
        var facts = gatherFixture();
        assertThat(facts.service().rows())
            .extracting(r -> r.parentTypeName() + "." + r.fieldName())
            .containsExactlyInAnyOrder("Query.stub");
    }

    @Test
    void writeSlotGathersVerbBulkAndTableSurface() {
        var facts = gatherFixture();
        assertThat(facts.write().rows())
            .extracting(r -> r.parentTypeName() + "." + r.fieldName() + ":"
                + r.verb().orElse("-") + ":" + r.multiRow() + ":" + r.table().orElse("-"))
            .containsExactlyInAnyOrder(
                "Mutation.createFilm:INSERT:false:-",
                "Mutation.deleteFilm:DELETE:true:film");
    }
}
