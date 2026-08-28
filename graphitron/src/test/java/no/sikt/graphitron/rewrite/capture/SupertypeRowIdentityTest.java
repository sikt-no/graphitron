package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.jooq.Record;
import org.jooq.Result;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repointed relations return what they returned when readers reconstructed them by union.
 *
 * <p>Both relations used to be a union over the per-site tables that carry one half of a fact each,
 * and are now a read of the relation capture writes that fact into. That is the one class of defect
 * the change can introduce: a supertype capture fills differently from the union it replaces returns
 * the same shape and different rows, and every reader above it is quietly wrong rather than broken.
 *
 * <p>Covers the two supertypes only. The stored bean property slice 2 adds is anchored by
 * {@code ClassMemberSlotScanTest}, which was already pinning the rule's output row by row against a
 * real compiler's census, including the two spellings of one property and the leading-acronym case;
 * a second anchor here would be a weaker copy of it against a fixture with no accessors in reach.
 *
 * <p>The anchor is the union itself, written out below as the SQL it was, rather than a row count or
 * a golden file. A count agrees by accident; a golden file is a second thing to maintain and says
 * nothing about which row moved. The union cannot drift away from what it is anchoring because it is
 * not derived from the schema: if capture stops writing a site, or writes it under a different
 * discriminator, the two sides differ and this test names the site.
 */
@PipelineTier
class SupertypeRowIdentityTest {

    /**
     * A result as a set of value lists, which is what makes the two sides comparable: they are the
     * same columns in the same order from two different statements, so a record's own table
     * identity is noise and only the values are the claim.
     */
    private static Set<List<Object>> rowSet(Result<Record> result) {
        return result.stream()
            .map(row -> java.util.Arrays.asList(row.intoArray()))
            .collect(Collectors.toSet());
    }

    /**
     * Exercises the sites the two supertypes span: a spelling written by {@code @table}, by
     * {@code @mutation}, by {@code @routine} and by a {@code @reference} path element, and an
     * argMapping pair written at a field condition, an argument condition, an input-field
     * condition, a service, a routine and a reference step. A fixture reaching fewer sites would
     * let a site's rows vanish from both sides at once, which is the way this kind of test passes
     * while seeing nothing.
     */
    private static final String FIXTURE = """
        type Query {
          films(title: String @field(name: "title")): [Film!]!
          languages(
            name: String @field(name: "name")
              @condition(condition: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService",
                                     method: "argCondition", argMapping: "cityNames: name"}, override: true)
          ): [Language!]!
          actors: [Actor!]!
        }

        type Mutation {
          dropFilm(id: ID!): Film @mutation(type: DELETE, table: "film")
        }

        type Film @table(name: "film") {
          title: String
          language: Language @reference(path: [{table: "language"}])
          staff(unit: String): [Staff!]!
            @condition(condition: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService",
                                   method: "argCondition", argMapping: "cityNames: unit"})
        }

        type Language @table(name: "language") {
          name: String
          films: [Film!]! @service(
            service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService",
                      method: "getFilmsMapped", argMapping: "titles: name"})
        }

        type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
        # Bare, so the spelling is the type's own name and no column on the site holds it.
        type Actor @table { actorId: Int @field(name: "actor_id") }

        input FilmFilter {
          title: String
            @condition(condition: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService",
                                   method: "argCondition", argMapping: "cityNames: title"})
        }
        """;

    /**
     * The union {@code intent_argmapping_pair_live} was, arm for arm. Nine values of the
     * discriminator over seven per-site tables: the field-condition table answers twice because a
     * condition on an input field is a different site from one on an object field, and the argument
     * {@code @referenceFor} step has no arm because no reader ever reached it, which is the one arm
     * this side cannot supply and the reason the assertion below is directional.
     */
    private static final String ARG_MAPPING_UNION = """
        SELECT p.graph_name, 'ROUTINE' AS site, p.type_name, p.field_name,
               CAST(NULL AS VARCHAR) AS argument_name, p.ordinal, CAST(NULL AS INT) AS step_position,
               p.position, p.param_name, p.argument_path
          FROM graphitron_routine_arg_mapping_pair p
          JOIN graphitron_routine d ON d.graph_name = p.graph_name AND d.type_name = p.type_name
           AND d.field_name = p.field_name AND d.ordinal = p.ordinal
        UNION ALL
        SELECT p.graph_name, 'SERVICE', p.type_name, p.field_name, NULL, NULL, NULL,
               p.position, p.param_name, p.argument_path
          FROM graphitron_service_arg_mapping_pair p
          JOIN graphitron_service d ON d.graph_name = p.graph_name AND d.type_name = p.type_name
           AND d.field_name = p.field_name
        UNION ALL
        SELECT p.graph_name, 'FIELD_CONDITION', p.type_name, p.field_name, NULL, NULL, NULL,
               p.position, p.param_name, p.argument_path
          FROM graphitron_field_condition_arg_mapping_pair p
          JOIN graphitron_field_condition d ON d.graph_name = p.graph_name AND d.type_name = p.type_name
           AND d.field_name = p.field_name
          JOIN graphql_type t ON t.graph_name = p.graph_name AND t.type_name = p.type_name
         WHERE t.kind <> 'INPUT_OBJECT'
        UNION ALL
        SELECT p.graph_name, 'INPUT_FIELD_CONDITION', p.type_name, p.field_name, NULL, NULL, NULL,
               p.position, p.param_name, p.argument_path
          FROM graphitron_field_condition_arg_mapping_pair p
          JOIN graphitron_field_condition d ON d.graph_name = p.graph_name AND d.type_name = p.type_name
           AND d.field_name = p.field_name
          JOIN graphql_type t ON t.graph_name = p.graph_name AND t.type_name = p.type_name
         WHERE t.kind = 'INPUT_OBJECT'
        UNION ALL
        SELECT p.graph_name, 'ARGUMENT_CONDITION', p.type_name, p.field_name, p.argument_name, NULL, NULL,
               p.position, p.param_name, p.argument_path
          FROM graphitron_argument_condition_arg_mapping_pair p
          JOIN graphitron_argument_condition d ON d.graph_name = p.graph_name AND d.type_name = p.type_name
           AND d.field_name = p.field_name AND d.argument_name = p.argument_name
        UNION ALL
        SELECT p.graph_name, 'FIELD_REFERENCE_STEP', p.type_name, p.field_name, NULL, p.ordinal,
               p.step_position, p.position, p.param_name, p.argument_path
          FROM graphitron_field_reference_step_arg_mapping_pair p
          JOIN graphitron_field_reference d ON d.graph_name = p.graph_name AND d.type_name = p.type_name
           AND d.field_name = p.field_name AND d.ordinal = p.ordinal
        UNION ALL
        SELECT p.graph_name, 'ARGUMENT_REFERENCE_STEP', p.type_name, p.field_name, p.argument_name,
               p.ordinal, p.step_position, p.position, p.param_name, p.argument_path
          FROM graphitron_argument_reference_step_arg_mapping_pair p
          JOIN graphitron_argument_reference d ON d.graph_name = p.graph_name AND d.type_name = p.type_name
           AND d.field_name = p.field_name AND d.argument_name = p.argument_name AND d.ordinal = p.ordinal
        UNION ALL
        SELECT p.graph_name, 'REFERENCE_FOR_STEP', p.type_name, p.field_name, NULL, p.ordinal,
               p.step_position, p.position, p.param_name, p.argument_path
          FROM graphitron_reference_for_step_arg_mapping_pair p
          JOIN graphitron_reference_for d ON d.graph_name = p.graph_name AND d.type_name = p.type_name
           AND d.field_name = p.field_name AND d.ordinal = p.ordinal
        """;

    /** The distinct-spelling union {@code intent_spelled_table_live} used to resolve against. */
    private static final String SPELLING_UNION = """
        SELECT graph_name, COALESCE(table_ref, type_name) AS spelling,
               table_ref_namespace_part_upper AS namespace_part_upper,
               COALESCE(table_ref_name_part_upper, type_name_upper) AS name_part_upper
          FROM graphitron_table
        UNION
        SELECT graph_name, table_ref, table_ref_namespace_part_upper, table_ref_name_part_upper
          FROM graphitron_field_reference_step WHERE table_ref IS NOT NULL
        UNION
        SELECT graph_name, table_ref, table_ref_namespace_part_upper, table_ref_name_part_upper
          FROM graphitron_argument_reference_step WHERE table_ref IS NOT NULL
        UNION
        SELECT graph_name, table_ref, table_ref_namespace_part_upper, table_ref_name_part_upper
          FROM graphitron_reference_for_step WHERE table_ref IS NOT NULL
        UNION
        SELECT graph_name, table_ref, table_ref_namespace_part_upper, table_ref_name_part_upper
          FROM graphitron_argument_reference_for_step WHERE table_ref IS NOT NULL
        UNION
        SELECT graph_name, table_ref, table_ref_namespace_part_upper, table_ref_name_part_upper
          FROM graphitron_mutation WHERE table_ref IS NOT NULL
        UNION
        SELECT graph_name, routine_ref, routine_ref_namespace_part_upper, routine_ref_name_part_upper
          FROM graphitron_routine
        """;

    @Test
    @DisplayName("the argMapping supertype holds what the nine-arm union returned")
    void argMappingPairAgreesWithTheUnionItReplaced(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var union = store.dsl().fetch(ARG_MAPPING_UNION);
            assertThat(union)
                .as("the fixture has to reach the sites, or both sides agree on nothing")
                .isNotEmpty();
            var captured = store.dsl().fetch("""
                SELECT graph_name, site, type_name, field_name, argument_name, ordinal,
                       step_position, position, param_name, argument_path
                  FROM graphitron_arg_mapping_pair
                 WHERE site <> 'ARGUMENT_REFERENCE_FOR_STEP'
                """);
            assertThat(rowSet(captured))
                .as("what capture wrote against what readers used to reconstruct")
                .isEqualTo(rowSet(union));
        }
    }

    /**
     * Directional, and the direction is the finding rather than a weakness in the test. The union
     * reached eight of the nine sites, the argument {@code @referenceFor} step having no arm; that
     * site's rows are excluded above so the two sides are comparable, and asserted here to be empty
     * so the exclusion cannot hide rows. A fixture that ever reaches that site fails this and the
     * exclusion above comes out together with it.
     */
    @Test
    @DisplayName("the site the union never reached is the site nothing writes yet")
    void theUnreachedSiteStaysEmpty(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(store.dsl().fetch("""
                SELECT use_site FROM graphitron_arg_mapping_pair
                 WHERE site = 'ARGUMENT_REFERENCE_FOR_STEP'
                """))
                .as("the coordinate today's validator rejects, so capture reaches no row of it")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("the spelled-reference supertype holds what the seven-arm union returned")
    void spelledReferenceAgreesWithTheUnionItReplaced(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var union = store.dsl().fetch(SPELLING_UNION);
            assertThat(union)
                .as("the fixture has to author spellings, or both sides agree on nothing")
                .isNotEmpty();
            var captured = store.dsl().fetch("""
                SELECT graph_name, spelling, namespace_part_upper, name_part_upper
                  FROM graphitron_spelled_reference
                """);
            assertThat(rowSet(captured))
                .as("what capture wrote against what readers used to reconstruct")
                .isEqualTo(rowSet(union));
        }
    }

    /**
     * The dedup is the relation's own key doing its job, and it is worth a case of its own because
     * the union deduplicated with {@code UNION} where capture deduplicates with
     * {@code FactSink#claim}: the two mechanisms could disagree without either side being empty,
     * and a spelling authored at several sites is the ordinary case rather than an edge one.
     */
    @Test
    @DisplayName("a spelling authored at several sites is one row")
    void spellingsAreOneRowHoweverManySitesWroteThem(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(store.dsl().fetch("""
                SELECT spelling, COUNT(*) AS rows FROM graphitron_spelled_reference
                 GROUP BY graph_name, spelling HAVING COUNT(*) > 1
                """))
                .as("the primary key says a second write of a spelling is the same fact")
                .isEmpty();
            assertThat(store.dsl().fetch("""
                SELECT spelling FROM graphitron_spelled_reference WHERE spelling = 'language'
                """))
                .as("the fixture authors 'language' at a @table and at a @reference path element")
                .hasSize(1);
        }
    }
}
