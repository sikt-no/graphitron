package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.CapturedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_COLUMN_MAPPING_PAIR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_ENTRY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three site anchors the walk used to be the only producer of, derived from the entry stratum
 * and held to what the walk writes.
 *
 * <p>Two readings of one corpus in one store, which is what makes this a comparison rather than a
 * smoke test. The walk writes these relations first with its own instant; the derivation writes
 * them second with another and then sweeps every row of the graph carrying an instant that is not
 * its own. So a row the walk wrote and the derivation did not reproduce is gone by the time a case
 * looks, and a row the derivation keyed differently is two rows rather than one. Both failures are
 * visible as a count, and neither needs the walk's rows kept aside to compare against.
 *
 * <p>The corpus is chosen for the three things the derivations decide rather than for coverage. A
 * type carries {@code @node} with a key-column list, so the list's order is stated by something
 * other than the order rows happen to come back in. A second carries a bare {@code @node}, which is
 * the arm that has an application and no decoded payload. And a field carries two {@code @routine}
 * applications, because an ordinal is the one column here that cannot be read off a single row: it
 * is a position among siblings, and a rule that got it from the wrong place would still produce one
 * row per application and only disagree about which is which.
 */
class GraphitronSiteAnchorAgreementTest {

    @TempDir
    Path tmp;

    private static final String SDL = """
        type Query {
          films: [Film!]!
          lookup: Language @routine(name: "public.pick_first", columnMapping: "pId: film_id, pYear: release_year") @routine(name: "public.pick_second")
        }

        type Film @table(name: "film") @node(typeId: "film", keyColumns: ["film_id", "title"]) {
          id: ID!
          title: String
        }

        type Language @table(name: "language") @node {
          name: String
        }
        """;

    @Test
    @DisplayName("a node's type id and its key columns survive the derivation's sweep")
    void theNodeAnchorsAgreeWithTheWalk() {
        try (var store = CapturedStore.of(tmp, SDL)) {
            var dsl = store.dsl();
            assertThat(typeIds(dsl))
                .as("both applications are rows, and the bare one carries no id rather than no row")
                .containsExactly(tuple("Film", "film"), tuple("Language", null));

            var t = GRAPHITRON_NODE_KEYCOLUMN_ENTRY;
            assertThat(dsl.select(t.POSITION, t.COLUMN_REF).from(t)
                    .where(t.TYPE_NAME.eq("Film")).orderBy(t.POSITION).fetch()
                    .map(row -> row.value1() + ":" + row.value2()))
                .as("the list is numbered from zero in the order the author wrote it")
                .containsExactly("0:film_id", "1:title");
        }
    }

    /**
     * The ordinal, which is the column a derivation can get wrong while still producing the right
     * number of rows. Two applications on one field, so the numbering has to order them; the
     * routine each ordinal names is what says it ordered them by where they were written.
     */
    @Test
    @DisplayName("repeated routine applications are numbered in written order")
    void theRoutineOrdinalsAgreeWithTheWalk() {
        try (var store = CapturedStore.of(tmp, SDL)) {
            var t = GRAPHITRON_ROUTINE_ENTRY;
            assertThat(store.dsl().select(t.ORDINAL, t.ROUTINE_REF).from(t)
                    .where(t.TYPE_NAME.eq("Query"), t.FIELD_NAME.eq("lookup"))
                    .orderBy(t.ORDINAL).fetch()
                    .map(row -> row.value1() + ":" + row.value2()))
                .as("numbered from zero, the first written application taking zero")
                .containsExactly("0:public.pick_first", "1:public.pick_second");
        }
    }

    /** The node applications as type and stated id, in a stable order. */
    private static List<org.assertj.core.groups.Tuple> typeIds(DSLContext dsl) {
        var t = GRAPHITRON_NODE_ENTRY;
        return dsl.select(t.TYPE_NAME, t.TYPE_ID).from(t).orderBy(t.TYPE_NAME).fetch()
            .map(row -> tuple(row.value1(), row.value2()));
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    /**
     * The mapping pairs, in both strata, because the number that ties them together is the one a
     * derivation could get wrong while producing the right rows.
     *
     * <p>A pair is written inside a string rather than as a node, so it has no position of its own
     * and the grammar's index is what identifies it. The entry states that index at the position the
     * application was written at; the anchor carries it across to the coordinate the application
     * resolves to, rather than ranking the pairs a second time. Asserting both is what says the two
     * agree about which pair is which, and the two-pair string is what makes the claim have content:
     * with one pair every index is zero and any rule at all would pass.
     */
    @Test
    @DisplayName("a columnMapping's pairs keep the grammar's index into the resolved relation")
    void theMappingPairsAgreeAcrossTheTwoStrata() {
        try (var store = CapturedStore.of(tmp, SDL)) {
            var dsl = store.dsl();

            var e = GRAPHITRON_AST_ROUTINE_COLUMN_MAPPING_PAIR_ENTRY;
            assertThat(dsl.select(e.POSITION, e.PARAM_NAME, e.COLUMN_REF).from(e)
                    .orderBy(e.POSITION).fetch()
                    .map(row -> row.value1() + ":" + row.value2() + "=" + row.value3()))
                .as("the entry holds each pair at the index the grammar read it at, and the"
                    + " application with no mapping contributes none")
                .containsExactly("0:pId=film_id", "1:pYear=release_year");

            var t = GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR_ENTRY;
            assertThat(dsl.select(t.TYPE_NAME, t.FIELD_NAME, t.ORDINAL, t.POSITION,
                        t.PARAM_NAME, t.COLUMN_REF).from(t)
                    .orderBy(t.POSITION).fetch()
                    .map(row -> row.value1() + "." + row.value2() + "#" + row.value3()
                        + " " + row.value4() + ":" + row.value5() + "=" + row.value6()))
                .as("and the anchor carries both the index and the ordinal of the application the"
                    + " pairs were written on, which is the first of the field's two routines")
                .containsExactly("Query.lookup#0 0:pId=film_id", "Query.lookup#0 1:pYear=release_year");
        }
    }
}
