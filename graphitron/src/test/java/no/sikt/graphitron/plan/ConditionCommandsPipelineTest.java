package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.MatchKind;
import no.sikt.graphitron.command.OuterLift;
import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier assertions on the condition command relation over classified fixture schemas:
 * the produced rows, their keys, the committed subset, and the producer-decided data (glue refs,
 * locals, lifts, facet fragments). Renderer behaviour is covered by
 * {@code ConditionGluePipelineTest} and the execution tier; this class pins what the producer
 * mints, so it asserts on rows, never on emitted code.
 */
@PipelineTier
class ConditionCommandsPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    @Test
    void filteredRootCoordinate_producesOneCommittedRowCarryingBothArms() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argCondition"})): [Language!]!
                unfiltered: [Language!]!
            }
            """.formatted(STUB));

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // Non-vacuity and bounding: exactly one covered coordinate; the filterless one has no row.
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);
        assertThat(row.coordinate()).isEqualTo(FieldCoordinates.coordinates("Query", "languages"));
        assertThat(row.table().tableName()).isEqualTo("language");

        // Conjunct order is today's fold order: the generated predicate first, then authored.
        assertThat(row.predicates()).hasSize(2);
        var generated = (Predicate.Generated) row.predicates().get(0);
        assertThat(generated.terms()).hasSize(1);
        var term = generated.terms().get(0);
        assertThat(term.match()).isEqualTo(MatchKind.EQUALITY);
        assertThat(term.columns()).hasSize(1);
        assertThat(term.reach()).isEmpty();
        assertThat(term.binding().localName()).isEqualTo("cityNames");
        var authored = (Predicate.Authored) row.predicates().get(1);
        assertThat(authored.method().methodName()).isEqualTo("argCondition");
        assertThat(authored.reach()).isEmpty();
        assertThat(authored.bindings()).extracting(b -> b.localName()).containsExactly("cityNames");

        // Glue is total and minted from the naming vocabulary; the row is committed (root rows
        // are the slice's committed set) and the committed unit is the glue owner.
        assertThat(row.glue().owner().fqcn()).isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".conditions.QueryConditions");
        assertThat(row.glue().methodName()).isEqualTo("languagesCondition");
        assertThat(relation.committedRows()).containsExactly(row);
        assertThat(relation.committedUnits()).containsExactly(row.glue().owner());
    }

    @Test
    void facetedCoordinate_fragmentsAreMaskedPredicateListsAndTheOuterLifts() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                title: [String!] @field(name: "title") @asFacet
                length: [Int] @field(name: "length") @asFacet
                releaseYear: [Int!] @field(name: "release_year")
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);

        // Three nested bindings share the `filter` outer, so the row lifts it once.
        assertThat(row.lifts()).containsExactly(new OuterLift("filter", "filterMap"));

        // The fragment set is data: one base plus one per facet, methods minted from the naming
        // vocabulary; the base keeps exactly the non-facet term, each per-facet fragment exactly
        // its own (the partition the row's constructor enforces).
        assertThat(row.facets()).extracting(f -> f.method().methodName()).containsExactly(
            "filmsFacetBaseCondition", "filmsFacet_titleCondition", "filmsFacet_lengthCondition");
        var base = row.facets().get(0);
        assertThat(generatedTermLocals(base.predicates())).containsExactly("releaseYear");
        // One retained binding: no lift in the base fragment.
        assertThat(base.lifts()).isEmpty();
        assertThat(generatedTermLocals(row.facets().get(1).predicates())).containsExactly("title");
        assertThat(generatedTermLocals(row.facets().get(2).predicates())).containsExactly("length");
    }

    @Test
    void lookupCoordinate_authoredFilterProducesAnUncommittedRow() {
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query {
                customersByKey(
                    customer_id: [Int!]! @lookupKey,
                    email: String @field(name: "email")
                        @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: email"}, override: true)
                ): [Customer!]!
            }
            """.formatted(STUB));

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // Lookup keys ride the VALUES join and are not predicates: the row carries only the
        // authored non-key filter. The lookup fold has not converged onto glue, so the row is
        // minted but not committed; nothing renders for it this slice.
        assertThat(relation.rows()).hasSize(1);
        var row = relation.rows().get(0);
        assertThat(row.coordinate()).isEqualTo(FieldCoordinates.coordinates("Query", "customersByKey"));
        assertThat(row.predicates()).hasSize(1);
        assertThat(row.predicates().get(0)).isInstanceOf(Predicate.Authored.class);
        assertThat(relation.committedRows()).isEmpty();
        assertThat(relation.committedUnits()).isEmpty();
    }

    @Test
    void polymorphicRoot_expandsToOneUncommittedRowPerParticipantTable() {
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Occupant = Customer | Staff
            type Query {
                occupants(firstName: [String!] @field(name: "first_name")): [Occupant!]!
            }
            """);

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        // The expansion is the fact, not a special case: the coordinate's filters live per
        // participant, so the row count equals the participant count and the rows share the
        // coordinate while differing in resolved table, which is why the key needs no second
        // column. Participant rows disambiguate by minted method name and stay uncommitted until
        // the polymorphic branch folds converge onto glue.
        assertThat(relation.rows()).hasSize(2);
        assertThat(relation.rows())
            .allMatch(r -> r.coordinate().equals(FieldCoordinates.coordinates("Query", "occupants")));
        assertThat(relation.rows()).extracting(r -> r.table().tableName())
            .containsExactlyInAnyOrder("customer", "staff");
        assertThat(relation.rows()).extracting(r -> r.glue().methodName())
            .containsExactlyInAnyOrder(
                "occupantsParticipant_CustomerCondition", "occupantsParticipant_StaffCondition");
        assertThat(relation.committedRows()).isEmpty();
    }

    @Test
    void contextBoundConditionOnCommittedCoordinate_rejectsAtValidateTime() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argConditionWithContext"}, contextArguments: ["tenantId"])): [Language!]!
            }
            """.formatted(STUB));

        // The env-bound rejection reads the producer's own committed-set predicate, so it fires
        // exactly on the coordinates whose glue would have nothing to read the context from.
        // (The retired shim emitted a graphitronContext(env) call its class never carried; this
        // build-time rejection replaces that uncompilable output.)
        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("Query.languages")
                && m.contains("@condition(contextArguments:) on a glue-rendered coordinate is not yet emitted"));
    }

    @Test
    void nestedGeneratedFilter_rejectsAtValidateTime() {
        // A filterable arg on a field nested inside a plain-object nesting type classifies to a
        // GeneratedConditionFilter whose conditions method no walk can emit (the nested
        // coordinate has no fields() entry). Rejected until nesting types become walkable
        // projection units; the same fixture flips to producing a row when they do.
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type FilmMeta {
                languages(name: String @field(name: "name")): [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") {
                meta: FilmMeta
            }
            type Query { films: [Film!]! }
            """);

        assertThat(new GraphitronSchemaValidator().validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("languages")
                && m.contains("generated column filters on a field nested inside a plain-object nesting type"));
    }

    private static List<String> generatedTermLocals(List<Predicate> predicates) {
        return predicates.stream()
            .filter(p -> p instanceof Predicate.Generated)
            .flatMap(p -> ((Predicate.Generated) p).terms().stream())
            .map(ColumnTerm::binding)
            .map(b -> b.localName())
            .toList();
    }
}
