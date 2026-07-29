package no.sikt.graphitron.plan;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The condition relation's membership enforcer: the relation's {@code (coordinate, table)} key
 * set equals the schema-derived covered set (every coordinate whose live filter surface is
 * nonempty), computed here by an independent walk over the classified fields, participant
 * filters, and the nesting tree. Since call-site convergence, every consumer emits a glue call
 * from its own "filters nonempty" read, so a coordinate the producer misses is a call to a
 * method that is never generated, surfacing at the consumer's javac; this pin turns that gap
 * into a build-time failure with the missing key named.
 */
@PipelineTier
class ConditionMembershipTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";

    @Test
    void relationKeySetEqualsTheDerivedCoveredSet() {
        // One coordinate per covered population: filtered root, authored-on-lookup root,
        // participant-expanded union root, inline child, split child, nested-inside-nesting-type
        // child, plus a filterless coordinate that must contribute nothing.
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Occupant = Customer | Staff
            type FilmMeta {
                languages(name: String @field(name: "name")
                    @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: name"}, override: true)):
                    [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") {
                meta: FilmMeta
                language(name: String @field(name: "name")): Language
                    @reference(path: [{key: "film_language_id_fkey"}])
                splitLanguages(name: String @field(name: "name")): [Language!]! @splitQuery
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query {
                films(title: String @field(name: "TITLE")): [Film!]!
                unfiltered: [Language!]!
                languagesByKey(
                    language_id: [Int] @lookupKey @field(name: "language_id"),
                    name: String @field(name: "name")
                        @condition(condition: {className: "%s", method: "argCondition", argMapping: "cityNames: name"}, override: true)
                ): [Language!]!
                occupants(firstName: [String!] @field(name: "first_name")): [Occupant!]!
            }
            """.formatted(STUB, STUB));

        var relation = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        var produced = new LinkedHashSet<String>();
        relation.rows().forEach(r -> produced.add(r.coordinate() + "@" + r.table().tableName()));

        var derived = deriveCoveredSet(schema);
        assertThat(produced).containsExactlyInAnyOrderElementsOf(derived);

        // Non-vacuity floor: the fixture exercises every population, so an enforcer comparing
        // two accidentally-empty sets cannot pass.
        assertThat(derived).containsExactlyInAnyOrder(
            "Query.films@film",
            "Query.languagesByKey@language",
            "Query.occupants@customer",
            "Query.occupants@staff",
            "Film.language@language",
            "Film.splitLanguages@language",
            "FilmMeta.languages@language");
    }

    /**
     * The covered set, derived independently of the producer: walk every classified field (and
     * the nesting tree the coordinate index cannot see), collecting one key per coordinate with
     * a nonempty filter surface, expanded per participant table for the polymorphic roots.
     */
    private static Set<String> deriveCoveredSet(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        var keys = new LinkedHashSet<String>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                collect(field, keys);
            }
        }
        return keys;
    }

    private static void collect(GraphitronField field, Set<String> keys) {
        switch (field) {
            case QueryField.QueryInterfaceField f -> f.participantFilters().forEach(pf -> {
                if (!pf.filters().isEmpty()) {
                    keys.add(key(f.parentTypeName(), f.name(), pf.participant().table().tableName()));
                }
            });
            case QueryField.QueryUnionField f -> f.participantFilters().forEach(pf -> {
                if (!pf.filters().isEmpty()) {
                    keys.add(key(f.parentTypeName(), f.name(), pf.participant().table().tableName()));
                }
            });
            case ChildField.NestingField nf -> nf.nestedFields().forEach(nested -> collect(nested, keys));
            default -> { }
        }
        if (field instanceof SqlGeneratingField sgf && !sgf.filters().isEmpty()) {
            keys.add(key(field.parentTypeName(), field.name(), sgf.returnType().table().tableName()));
        }
    }

    private static String key(String parent, String fieldName, String tableName) {
        // Matches FieldCoordinates' canonical string form (Parent.field), the same rendering the
        // relation's own key-distinctness check stringifies.
        return parent + "." + fieldName + "@" + tableName;
    }
}
