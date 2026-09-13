package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.EntryFamilyFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a path element can be put back together from the facts the entry stratum states about it.
 *
 * <p>The entry stratum states one {@code @reference} path element as up to three separate facts in
 * three relations, keyed by the position the element was written at: the table it names, the key it
 * names, and the condition it names. The walk states the same element as one row of
 * {@code graphitron_field_reference_step_entry} carrying all three as nullable columns. So the
 * anchor that replaces the walk has to recombine, and this is the case that says it can, before any
 * of it moves.
 *
 * <p><b>Why a recombination needs a spine.</b> No relation holds the element. Each of the three
 * holds only the elements that wrote its own fact, so the set of positions an application has is the
 * union of three sets and not the contents of any one of them. Every other anchor derived off this
 * stratum joins one entry relation to one coordinate; this is the first read where what a split
 * bought at the write has to be paid back, which is the whole reason the split is worth measuring
 * here rather than arguing about.
 *
 * <p><b>Two arms, not one relation with a site column.</b> {@code @reference} is admitted at three
 * SDL sites and the two strata cut them differently. The walk keys by graphql field, so an output
 * field and an input object's field are both rows of one relation and an argument is a row of
 * another. The entry stratum keys by node kind, so an input object's field is an input value
 * alongside the argument and apart from the output field. The relation this reconstructs is the
 * walk's, so its population is the two sites that are fields, and they arrive from two different
 * entry families. Each arm is a whole statement of the rule over its own relations rather than one
 * statement with the site as a predicate, which is what keeps a row from having to say which arm
 * could have produced it.
 *
 * <p><b>The position is ranked again rather than copied.</b> The entry numbers an element where the
 * author wrote it in the list. The walk numbers the elements it could decode, densely, having
 * incremented its counter only after writing a row. The two agree on every application whose
 * elements all decode and disagree on any that has a gap, so copying the entry's number would be
 * copying a different fact under the same name.
 */
class ReferenceStepRecombinationTest {

    @TempDir
    static Path directory;

    private static CapturedStore captured;

    private static CapturedStore store() {
        if (captured == null) {
            captured = EntryFamilyFixture.capture(directory);
        }
        return captured;
    }

    @AfterAll
    static void closeStore() {
        if (captured != null) {
            captured.close();
            captured = null;
        }
    }

    /**
     * One arm of the recombination, over the three step relations of one entry family.
     *
     * <p>Given the relation prefix the family spells its three step relations with, and the join
     * that reaches a coordinate from an application's position, this is the same rule twice.
     */
    private static String arm(String claim, String prefix) {
        return """
            SELECT c.type_name, c.field_name, c.ordinal,
                   ROW_NUMBER() OVER (PARTITION BY c.type_name, c.field_name, c.ordinal
                                      ORDER BY p.position) - 1 AS position,
                   t.table_ref, t.table_ref_namespace_part, t.table_ref_name_part,
                   k.key_ref, k.key_ref_namespace_part, k.key_ref_name_part,
                   d.class_name, d.method, d.argmapping
              FROM %1$s c
              JOIN (SELECT source_name, source_line, source_column, position
                      FROM %2$s_table_step_entry WHERE graph_name = ?
                    UNION
                    SELECT source_name, source_line, source_column, position
                      FROM %2$s_key_step_entry WHERE graph_name = ?
                    UNION
                    SELECT source_name, source_line, source_column, position
                      FROM %2$s_condition_step_entry WHERE graph_name = ?) p
                ON p.source_name = c.site_name AND p.source_line = c.site_line
               AND p.source_column = c.site_column
              LEFT JOIN %2$s_table_step_entry t
                ON t.graph_name = ? AND t.source_name = p.source_name
               AND t.source_line = p.source_line AND t.source_column = p.source_column
               AND t.position = p.position
              LEFT JOIN %2$s_key_step_entry k
                ON k.graph_name = ? AND k.source_name = p.source_name
               AND k.source_line = p.source_line AND k.source_column = p.source_column
               AND k.position = p.position
              LEFT JOIN %2$s_condition_step_entry d
                ON d.graph_name = ? AND d.source_name = p.source_name
               AND d.source_line = p.source_line AND d.source_column = p.source_column
               AND d.position = p.position
            """.formatted(claim, prefix);
    }

    /**
     * The application ranked to an ordinal, for one of the two site families. A repeated
     * {@code @reference} on a field composes an ordered chain rather than being a conflict, so every
     * rank is kept and the rank is the ordinal; the corpus merge order decides between applications
     * written in different documents, as it does for every coordinate-keyed anchor.
     */
    private static final String CLAIMED_FIELD = """
        (SELECT f.type_name AS type_name, f.name AS field_name,
                a.source_name AS site_name, a.source_line AS site_line,
                a.source_column AS site_column,
                ROW_NUMBER() OVER (PARTITION BY f.type_name, f.name
                                   ORDER BY m.merge_ordinal, a.source_line,
                                            a.source_column) - 1 AS ordinal
           FROM graphql_ast_field_directive_entry a
           JOIN graphql_ast_field_definition_entry f
             ON f.graph_name = a.graph_name AND f.source_name = a.source_name
            AND f.source_line = a.parent_line AND f.source_column = a.parent_column
           JOIN graphql_type_declaration m
             ON m.graph_name = a.graph_name AND m.type_name = f.type_name
            AND m.source_name = f.source_name
            AND m.source_line = f.parent_line AND m.source_column = f.parent_column
          WHERE a.graph_name = ? AND a.name = 'reference')
        """;

    /**
     * The same for an input object's field, which is an input value in this stratum and a field in
     * the walk's. Its coordinate comes off the input-field entry rather than the field-definition
     * one, and its declaration is the input object it was written inside.
     */
    private static final String CLAIMED_INPUT_FIELD = """
        (SELECT i.type_name AS type_name, i.name AS field_name,
                a.source_name AS site_name, a.source_line AS site_line,
                a.source_column AS site_column,
                ROW_NUMBER() OVER (PARTITION BY i.type_name, i.name
                                   ORDER BY m.merge_ordinal, a.source_line,
                                            a.source_column) - 1 AS ordinal
           FROM graphql_ast_input_value_directive_entry a
           JOIN graphql_ast_input_field_entry i
             ON i.graph_name = a.graph_name AND i.source_name = a.source_name
            AND i.source_line = a.parent_line AND i.source_column = a.parent_column
           JOIN graphql_type_declaration m
             ON m.graph_name = a.graph_name AND m.type_name = i.type_name
            AND m.source_name = i.source_name
            AND m.source_line = i.parent_line AND m.source_column = i.parent_column
          WHERE a.graph_name = ? AND a.name = 'reference')
        """;

    private static final String WALKED = """
        SELECT type_name, field_name, ordinal, position,
               table_ref, table_ref_namespace_part, table_ref_name_part,
               key_ref, key_ref_namespace_part, key_ref_name_part,
               class_name, method, argmapping
          FROM graphitron_field_reference_step_entry
         WHERE graph_name = ?
        """;

    /** The whole rule: the same arm over each of the two site families, and nothing over both. */
    private static final String RECOMBINED =
        arm(CLAIMED_FIELD, "graphitron_ast_field_reference")
            + " UNION ALL "
            + arm(CLAIMED_INPUT_FIELD, "graphitron_ast_input_value_reference");

    private static List<String> rows(String sql, int binds) {
        return rows(store(), sql, binds);
    }

    private static List<String> rows(CapturedStore from, String sql, int binds) {
        Object[] values = new Object[binds];
        java.util.Arrays.fill(values, CapturedStore.GRAPH);
        return from.dsl().fetch(sql, values).stream()
            .map(record -> java.util.Arrays.toString(record.intoArray()))
            .sorted()
            .toList();
    }

    /**
     * The two shapes the shared fixture does not carry, in a corpus of their own.
     *
     * <p>A repeated {@code @reference} composes, so a field can have two applications and the
     * ordinal is what separates them; nothing in the shared fixture applies one twice. And a path
     * element that is not an object is where the two strata's numbering parts company: the entry
     * numbers elements where the author wrote them, incrementing past one it does not transcribe,
     * while the walk increments only after writing a row. Every position in the shared fixture
     * decodes, so the two numberings coincide there and the ranking is never exercised by it.
     *
     * <p>The gap is written on an input object's field, and it has to be. {@code DirectiveLegality}
     * judges the type and field sites, so an application carrying an element of the wrong shape is
     * refused there whole and opens no gap to rank over. It does not yet judge the input-value
     * site, and an input object's field is an input value in the entry stratum while being a field
     * to the walk, which is the one coordinate where the two strata still number the same
     * application differently. If that site is judged too, this case stops being reachable and the
     * ranking it justifies can go with it; until then both are live and the walk is the arm that
     * has not adopted the rule.
     */
    @Test
    @DisplayName("the ordinal separates repeated applications and the position closes over a gap")
    void theRankingSurvivesARepeatedApplicationAndAnUndecodableElement(@TempDir Path tmp) {
        try (var edge = CapturedStore.of(tmp, """
                type Query { films(filter: FilmFilter): [Film!] }
                type Film {
                  id: ID!
                  actors: [Actor!]
                    @reference(path: [{table: "film_actor"}])
                    @reference(path: [{table: "actor", key: "film_actor_actor_id_fk"}])
                }
                input FilmFilter {
                  gapped: ID @reference(path: [{table: "a"}, "oops", {table: "b"}])
                }
                type Actor { id: ID! }
                """)) {
            assertThat(rows(edge, RECOMBINED, 14))
                .as("a repeated application is two ordinals, and an element the walk could not "
                    + "decode is a position the entry stratum has and the walk does not, so a "
                    + "recombination that copied the entry's number would disagree here. The gap "
                    + "sits on an input object's field because that is the one site the legality "
                    + "judge does not reach yet")
                .containsExactlyElementsOf(rows(edge, WALKED, 1));
        }
    }

    @Test
    @DisplayName("a path element recombines out of the three facts the entry stratum states about it")
    void theRecombinationEqualsWhatTheWalkWrote() {
        var derived = rows(RECOMBINED, 14);
        var walked = rows(WALKED, 1);

        assertThat(walked)
            .as("the fixture has to carry the shapes this compares, or the comparison is vacuous")
            .isNotEmpty();
        assertThat(derived)
            .as("the entry stratum states a path element as up to three facts in three relations, "
                + "and this is the read that puts one back together. A row here the walk does not "
                + "have is a recombination that invented an element; a row the walk has and this "
                + "does not is a fact the split dropped")
            .containsExactlyElementsOf(walked);
    }
}
