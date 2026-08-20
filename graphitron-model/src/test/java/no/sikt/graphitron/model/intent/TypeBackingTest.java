package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CONFLICT;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedBoundTable;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedTableWithoutRecordClass;
import static no.sikt.graphitron.model.test.SeededStore.seedTypeBackingClass;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_type_backing} returns, and {@code intent_type_backing_conflict} over it: which
 * class stands for a graph's type, from either population that can answer, and which types the two
 * populations together answer more than one way.
 *
 * <p>The two arms are a table binding read through the table's generated record and the backing
 * closure over producer returns and accessor hops. Neither is a special case of the other, so this
 * is where they meet rather than a base relation with a provenance tag, and both can carry the same
 * payload only because the catalog records a table's record class. A table whose generated model
 * exposes none reports {@code org.jooq.Record}, which is not a backing, so that arm drops it and the
 * type is as unbacked here as one no producer reaches.
 *
 * <p>Ambiguity is rows on both arms and nothing is preferred. Which population answered is carried
 * as provenance, never as a rank: a reader that wants one arm filters on it and owns having chosen,
 * and what it may not do is mistake the precedence for agreement. The contest view is where a reader
 * learns that it has to choose, counted over distinct classes so one class both arms name is one
 * answer, with the classes rendered in one canonical order so two readers grouping by the contested
 * set cannot split a group on row order.
 *
 * <p>Where the closure's own rows come from is not asked here. The seeds are
 * {@code no.sikt.graphitron.model.intent.TypeBackingSeedTest}'s subject, and the reachability over
 * them is a materialization a writer fills, pinned where that writer runs, in
 * {@code no.sikt.graphitron.rewrite.derive.TypeBackingClassTest}. This relation takes those rows as
 * given and states what coalescing them with a table binding makes of them.
 */
class TypeBackingTest {

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";

    // ===== The two populations =====

    /** Both arms answer, and each row says which of them did. */
    @Test
    void bothPopulationsAnswerAndTheRowSaysWhichDid() {
        withSeededStore(GRAPH, dsl -> {
            bindTable(dsl, GRAPH, "Tabled", "film");
            seedTypeBackingClass(dsl, GRAPH, "Handed", "app.PayloadDto");

            assertThat(backings(dsl)).containsExactlyInAnyOrder(
                "Tabled=" + recordOf(GRAPH, "film") + " BOUND_TABLE",
                "Handed=app.PayloadDto BACKING_CLOSURE");
        });
    }

    /**
     * A table whose generated model exposes no record class of its own backs nothing. The catalog
     * reports that as {@code org.jooq.Record} rather than as an absence, so an arm that passed it
     * through would hand every such type the same class. A second type bound to a table that does
     * carry a record is seeded beside it, which is what makes the silence about the record class.
     */
    @Test
    void aTableWithNoRecordClassBacksNothing() {
        withSeededStore(GRAPH, dsl -> {
            seedTableBinding(dsl, GRAPH, "Bare", "film");
            seedSource(dsl, catalogOf(GRAPH), "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, catalogOf(GRAPH));
            seedTableWithoutRecordClass(dsl, catalogOf(GRAPH), "public", "film");
            bindTable(dsl, GRAPH, "Tabled", "language");

            assertThat(backings(dsl))
                .containsExactly("Tabled=" + recordOf(GRAPH, "language") + " BOUND_TABLE");
        });
    }

    /**
     * A type the two populations answer differently is both rows. The walk this family shadows
     * resolves the pair by precedence, reading the table and never consulting the class; that is a
     * reading a consumer may still apply by filtering on the provenance, and a relation that folded
     * it in here would have recorded agreement where there is none.
     */
    @Test
    void aTypeThePopulationsAnswerDifferentlyIsBothRows() {
        withSeededStore(GRAPH, dsl -> {
            bindTable(dsl, GRAPH, "Film", "film");
            seedTypeBackingClass(dsl, GRAPH, "Film", "app.FilmDto");

            assertThat(backings(dsl)).containsExactlyInAnyOrder(
                "Film=" + recordOf(GRAPH, "film") + " BOUND_TABLE",
                "Film=app.FilmDto BACKING_CLOSURE");
            assertThat(conflicts(dsl))
                .containsExactly("Film=app.FilmDto, " + recordOf(GRAPH, "film") + " 2");
        });
    }

    /**
     * One class both populations name is two rows here and one answer below. The rows differ in
     * which population answered and in nothing else, provenance being what this relation carries;
     * the contest is counted over the classes, so a reader that needs one class has one.
     */
    @Test
    void oneClassBothPopulationsNameIsTwoRowsAndOneAnswer() {
        withSeededStore(GRAPH, dsl -> {
            bindTable(dsl, GRAPH, "Film", "film");
            seedTypeBackingClass(dsl, GRAPH, "Film", recordOf(GRAPH, "film"));

            assertThat(backings(dsl)).containsExactlyInAnyOrder(
                "Film=" + recordOf(GRAPH, "film") + " BOUND_TABLE",
                "Film=" + recordOf(GRAPH, "film") + " BACKING_CLOSURE");
            assertThat(conflicts(dsl)).isEmpty();
        });
    }

    // ===== The contest =====

    /** A type one class answers is not contested, the view naming the contested population only. */
    @Test
    void aTypeOneClassAnswersIsNotContested() {
        withSeededStore(GRAPH, dsl -> {
            seedTypeBackingClass(dsl, GRAPH, "Handed", "app.PayloadDto");
            bindTable(dsl, GRAPH, "Tabled", "film");

            assertThat(conflicts(dsl)).isEmpty();
        });
    }

    /**
     * A contest names its classes in one canonical order and counts each of them once. Four rows
     * answer this type and three classes do, the table's record being one the closure also reached,
     * so the render and the arity are both over the classes rather than over the rows. The order is
     * the classes' own and not the order anything seeded them in, nor the order the populations
     * answered in, which is what lets two readers group by the contested set without splitting a
     * group; the arity is what a rejection stands on.
     */
    @Test
    void aContestNamesItsClassesInOrderAndCountsEachOnce() {
        withSeededStore(GRAPH, dsl -> {
            seedTypeBackingClass(dsl, GRAPH, "Film", "zz.Dto");
            seedTypeBackingClass(dsl, GRAPH, "Film", "app.Alpha");
            seedTypeBackingClass(dsl, GRAPH, "Film", recordOf(GRAPH, "film"));
            bindTable(dsl, GRAPH, "Film", "film");

            assertThat(conflicts(dsl)).containsExactly(
                "Film=app.Alpha, " + recordOf(GRAPH, "film") + ", zz.Dto 3");
        });
    }

    // ===== The partition =====

    /**
     * A graph is backed and contested on its own rows only. Both graphs answer for a type of the
     * same name and answer differently, and neither is contested: a class another graph named is
     * not an answer this one has to choose against.
     */
    @Test
    void aGraphIsBackedAndContestedOnItsOwnRowsOnly() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, OTHER_GRAPH);
            seedTypeBackingClass(dsl, GRAPH, "Film", "app.Left");
            bindTable(dsl, GRAPH, "Tabled", "film");
            seedTypeBackingClass(dsl, OTHER_GRAPH, "Film", "app.Right");
            bindTable(dsl, OTHER_GRAPH, "Tabled", "film");

            assertThat(allBackings(dsl)).containsExactlyInAnyOrder(
                GRAPH + " Film=app.Left BACKING_CLOSURE",
                GRAPH + " Tabled=" + recordOf(GRAPH, "film") + " BOUND_TABLE",
                OTHER_GRAPH + " Film=app.Right BACKING_CLOSURE",
                OTHER_GRAPH + " Tabled=" + recordOf(OTHER_GRAPH, "film") + " BOUND_TABLE");
            assertThat(allConflicts(dsl)).isEmpty();
        });
    }

    // ===== Readings =====

    /** Every backing of this graph, as {@code Type=class VIA}. */
    private static List<String> backings(DSLContext dsl) {
        derive(dsl);
        return dsl.selectFrom(INTENT_TYPE_BACKING)
            .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(GRAPH))
            .fetch(r -> r.getTypeName() + "=" + r.getClassName() + " " + r.getDeclaredVia());
    }

    /** Every contested type of this graph, with the render and the arity the view adds. */
    private static List<String> conflicts(DSLContext dsl) {
        derive(dsl);
        return dsl.selectFrom(INTENT_TYPE_BACKING_CONFLICT)
            .where(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME.eq(GRAPH))
            .fetch(r -> r.getTypeName() + "=" + r.getClassNames() + " " + r.getCandidates());
    }

    /** The same two over the whole store, graph first, so the partition is read as a value. */
    private static List<String> allBackings(DSLContext dsl) {
        derive(dsl);
        return dsl.selectFrom(INTENT_TYPE_BACKING)
            .fetch(r -> r.getGraphName() + " " + r.getTypeName() + "=" + r.getClassName()
                + " " + r.getDeclaredVia());
    }

    private static List<String> allConflicts(DSLContext dsl) {
        derive(dsl);
        return dsl.selectFrom(INTENT_TYPE_BACKING_CONFLICT)
            .fetch(r -> r.getGraphName() + " " + r.getTypeName() + "=" + r.getClassNames()
                + " " + r.getCandidates());
    }

    // ===== Fixtures =====

    /**
     * A type bound to a table of the name given, under a catalog partition of this graph's own, so
     * two graphs binding the same name are two tables and not one shared row.
     */
    private static void bindTable(DSLContext dsl, String graphName, String typeName,
                                  String tableName) {
        seedBoundTable(dsl, graphName, typeName, tableName, catalogOf(graphName), "public",
            tableName);
    }

    private static String catalogOf(String graphName) {
        return "jooq." + graphName;
    }

    /** How the harness spells the generated record class of a table it seeded. */
    private static String recordOf(String graphName, String tableName) {
        return catalogOf(graphName) + ".tables.records." + tableName + "Record";
    }
}
