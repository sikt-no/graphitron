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
 * <p><b>The position is copied, because the ranking was closing a gap that no longer occurs and
 * could never close the one that does.</b> It was there because an element the decode could not
 * read spent its index, leaving the entry numbering ahead of the walk's; an element like that is
 * now refused with its whole application, so that gap is gone. Measured rather than assumed:
 * replacing the ranking with the authored position leaves every case here green, where before the
 * rule it turned the second one red.
 *
 * <p><b>One divergence is live and neither numbering reaches it.</b> A path element may be legal
 * and still write no step row, {@code ReferenceElement} declaring every field optional, so
 * {@code @reference(path: [{table: "a"}, {}, {table: "b"}])} assembles and the bare element names
 * no table, no key and no condition. The walk writes three rows at 0, 1 and 2; the three step
 * relations hold 0 and 2 between them and the element at 1 appears in none of them. A spine built
 * from those relations therefore loses a row, which a rank turns into a renumbering and a copy
 * turns into a hole, both wrong and neither recoverable from the relations being read. What closes
 * it is a coordinate rather than arithmetic: the element is already a row of
 * {@code graphql_ast_value_entry}, in authored order and unfiltered, and a decode keyed by that
 * row can name an element that decoded to nothing. Until these relations carry that key the
 * comparison holds only for paths whose every element states at least one fact, which is what the
 * corpora here are.
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
                   p.position AS position,
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

    /** The corpus assembled, which is the scope every comparison here is made in. */
    private static final String PROBLEMS = """
        SELECT stage, error_class FROM graphql_schema_problem WHERE graph_name = ?
        """;

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
     * <p>It used to carry a third shape, a path element the decode could not read, because the two
     * strata numbered around such an element differently. They cannot meet one now: an element the
     * definition does not admit withdraws its whole application at every site, so no admitted
     * application numbers around a hole. The walk still writes rows for one, being the thing this
     * replaces and having never adopted the rule, so the two strata do disagree on a corpus
     * carrying one. That corpus cannot assemble, and a differential that kept agreeing there would
     * be pinning the walk's behaviour on input no schema can carry, which is why the case below
     * asserts the corpus assembles before comparing anything.
     */
    @Test
    @DisplayName("the ordinal separates repeated applications, at both of the sites that are fields")
    void theOrdinalSeparatesRepeatedApplications(@TempDir Path tmp) {
        try (var edge = CapturedStore.of(tmp, """
                type Query { films(filter: FilmFilter): [Film!] }
                type Film {
                  id: ID!
                  actors: [Actor!]
                    @reference(path: [{table: "film_actor"}])
                    @reference(path: [{table: "actor", key: "film_actor_actor_id_fk"}])
                }
                input FilmFilter {
                  byAgency: ID
                    @reference(path: [{table: "agency"}])
                    @reference(path: [{table: "film"}, {table: "agency", key: "film_agency_fk"}])
                }
                type Actor { id: ID! }
                """)) {
            assertThat(rows(edge, PROBLEMS, 1))
                .as("the corpus assembles, which is the scope this comparison holds in: the walk "
                    + "transcribes an application the definition does not admit and the entry "
                    + "stratum does not, so the two agree exactly where a schema can be built")
                .isEmpty();
            assertThat(rows(edge, RECOMBINED, 14))
                .as("a repeated application is two ordinals, and it is two at an output field and "
                    + "at an input object's field alike, which is the pair of arms the union is "
                    + "for")
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
