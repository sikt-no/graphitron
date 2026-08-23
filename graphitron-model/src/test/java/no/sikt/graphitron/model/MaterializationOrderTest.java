package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.MaterializeDependencies;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.META_MATERIALIZE;
import static no.sikt.graphitron.model.Tables.META_MATERIALIZE_DEPENDENCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Pins the derived refresh order on synthetic registrations, because the production DDL's own
 * dependent derivations do not span the shapes the design claims: one registered target reading
 * another's is now the shipped state rather than a hypothetical, but it is a single edge, and a
 * cycle, a walk through an unregistered intermediate view and a row-free relation are all states no
 * shipped registration produces. A scratch store can
 * {@code CREATE} ordinary tables and views and register them, which is enough to drive
 * {@link MaterializeDependencies#populate} and {@link Materializations#refreshOrder} through every
 * shape the design claims: the parse that finds an edge, the walk through an unregistered
 * intermediate view, the refresh that respects the order, the cycle that fails naming itself, and
 * the row-free relation that orders exactly as the census does.
 *
 * <p>The fixture names are chosen so the dependent sorts ahead of its prerequisite
 * ({@code scratch_d_live} before {@code scratch_p_live}): an unordered refresh would fill the
 * dependent from the prerequisite's stale rows, so the ordering assertions here fail on the exact
 * defect the order exists to prevent rather than passing by alphabetical accident.
 */
class MaterializationOrderTest {

    @Test
    @DisplayName("population finds the edge a source view's direct read states")
    void populationFindsTheDirectEdge() {
        withStore(dsl -> {
            prerequisite(dsl);
            dependent(dsl);
            MaterializeDependencies.populate(dsl);
            assertThat(dependencyRows(dsl))
                .as("the one edge the fixture's direct read states")
                .containsExactly(tuple("scratch_d_live", "scratch_p_live"));
        });
    }

    @Test
    @DisplayName("population walks through an unregistered intermediate view")
    void populationWalksThroughAnUnregisteredIntermediateView() {
        withStore(dsl -> {
            prerequisite(dsl);
            dsl.execute("CREATE VIEW scratch_mid AS SELECT v FROM scratch_p");
            dsl.execute("CREATE VIEW scratch_t_live AS SELECT v FROM scratch_mid");
            dsl.execute("CREATE TABLE scratch_t (v INT)");
            register(dsl, "scratch_t_live", "scratch_t");
            MaterializeDependencies.populate(dsl);
            assertThat(dependencyRows(dsl))
                .as("the transitive read through scratch_mid, which is a view no registration names")
                .containsExactly(tuple("scratch_t_live", "scratch_p_live"));
        });
    }

    @Test
    @DisplayName("population writes no row for a view reading base tables alone")
    void populationWritesNoRowForABaseTableReader() {
        withStore(dsl -> {
            prerequisite(dsl);
            MaterializeDependencies.populate(dsl);
            assertThat(dependencyRows(dsl))
                .as("scratch_p_live reads only scratch_src, a base table, so no edge exists")
                .isEmpty();
        });
    }

    @Test
    @DisplayName("population rewrites identical rows when run twice on one store")
    void populationRewritesIdenticallyOnASecondRun() {
        withStore(dsl -> {
            prerequisite(dsl);
            dependent(dsl);
            MaterializeDependencies.populate(dsl);
            var first = dependencyRows(dsl);
            MaterializeDependencies.populate(dsl);
            assertThat(dependencyRows(dsl))
                .as("a second population over an unchanged store")
                .isEqualTo(first)
                .isNotEmpty();
        });
    }

    /**
     * The order observed through rows rather than through a sequence assertion: the prerequisite's
     * target holds a stale row, its source holds the current ones, and only a refresh that settles
     * the prerequisite first can leave the dependent holding the current rows. The dependent's name
     * sorting ahead of the prerequisite's is what makes the unordered refresh fail this, copying
     * the stale row instead.
     */
    @Test
    @DisplayName("a dependent target refreshes after its prerequisite, in the graph-scoped refresh")
    void aDependentRefreshesAfterItsPrerequisite() {
        withStore(dsl -> {
            prerequisite(dsl);
            dependent(dsl);
            dsl.execute("INSERT INTO scratch_src VALUES (1), (2), (3)");
            dsl.execute("INSERT INTO scratch_p VALUES (99)");
            MaterializeDependencies.populate(dsl);
            Materializations.refresh(dsl, "any-graph");
            assertThat(targetRows(dsl, "scratch_d"))
                .as("the dependent's rows derive from the prerequisite's fresh rows, not its stale one")
                .containsExactly(1, 2, 3);
        });
    }

    @Test
    @DisplayName("a dependent target refreshes after its prerequisite, in refreshAll")
    void aDependentRefreshesAfterItsPrerequisiteInRefreshAll() {
        withStore(dsl -> {
            prerequisite(dsl);
            dependent(dsl);
            dsl.execute("INSERT INTO scratch_src VALUES (4), (5)");
            dsl.execute("INSERT INTO scratch_p VALUES (99)");
            MaterializeDependencies.populate(dsl);
            Materializations.refreshAll(dsl);
            assertThat(targetRows(dsl, "scratch_d"))
                .as("refreshAll settles the prerequisite before the dependent reads it")
                .containsExactly(4, 5);
        });
    }

    /**
     * Two registrations reading each other's targets is legal DDL with no view recursion anywhere,
     * which is why the refusal is semantic rather than syntactic: no refresh order makes both
     * targets equal their views on a settled store, each needing the other current first.
     */
    @Test
    @DisplayName("a registered cycle fails, with the cycle named")
    void aRegisteredCycleFailsNamingTheCycle() {
        withStore(dsl -> {
            dsl.execute("CREATE TABLE scratch_x (v INT)");
            dsl.execute("CREATE TABLE scratch_y (v INT)");
            dsl.execute("CREATE VIEW scratch_x_live AS SELECT v FROM scratch_y");
            dsl.execute("CREATE VIEW scratch_y_live AS SELECT v FROM scratch_x");
            register(dsl, "scratch_x_live", "scratch_x");
            register(dsl, "scratch_y_live", "scratch_y");
            MaterializeDependencies.populate(dsl);
            assertThatThrownBy(() -> Materializations.refreshOrder(dsl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("scratch_x_live")
                .hasMessageContaining("scratch_y_live");
        });
    }

    @Test
    @DisplayName("a row-free dependency relation refreshes in exactly the census's alphabetical order")
    void aRowFreeRelationRefreshesInTheCensusOrder() {
        withStore(dsl -> {
            prerequisite(dsl);
            dsl.execute("CREATE VIEW scratch_a_live AS SELECT v FROM scratch_src");
            dsl.execute("CREATE TABLE scratch_a (v INT)");
            register(dsl, "scratch_a_live", "scratch_a");
            MaterializeDependencies.populate(dsl);
            assertThat(dependencyRows(dsl))
                .as("neither synthetic registration reads the other's target")
                .isEmpty();
            // The subject here is the row-free relation, which the production DDL's own dependent
            // registration means no longer arises on its own. Emptying it is what puts the case back
            // in front of the state it is about, rather than asserting the shipped edge's order.
            dsl.deleteFrom(META_MATERIALIZE_DEPENDENCY).execute();
            assertThat(Materializations.refreshOrder(dsl).registrations())
                .as("the empty relation is the identity case: today's order, byte for byte")
                .isEqualTo(Materializations.registrations(dsl));
        });
    }

    // ===== Fixture =====

    private static void withStore(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }

    /** The prerequisite: target {@code scratch_p}, whose view reads only a base table. */
    private static void prerequisite(DSLContext dsl) {
        dsl.execute("CREATE TABLE scratch_src (v INT)");
        dsl.execute("CREATE VIEW scratch_p_live AS SELECT v FROM scratch_src");
        dsl.execute("CREATE TABLE scratch_p (v INT)");
        register(dsl, "scratch_p_live", "scratch_p");
    }

    /** The dependent: target {@code scratch_d}, whose view reads the prerequisite's target. */
    private static void dependent(DSLContext dsl) {
        dsl.execute("CREATE VIEW scratch_d_live AS SELECT v FROM scratch_p");
        dsl.execute("CREATE TABLE scratch_d (v INT)");
        register(dsl, "scratch_d_live", "scratch_d");
    }

    /**
     * The same walk asked the other way round. {@link MaterializeDependencies#populate} needs only the
     * registrations a registered source view reaches; the reach a cost gate ranges over is every
     * view's, so this pins the answer for the fixture whose subtree is known by inspection here:
     * {@code scratch_d_live} reads the prerequisite's target through an unregistered intermediate view,
     * and the intermediate view reaches it directly.
     *
     * <p>A walk stops at a registered target, which is the property worth pinning rather than
     * inferring: {@code scratch_p_live}, sitting below both, reaches nothing, so a reader meeting a
     * materialized table is not charged for what fills it. And an unregistered view is answered in its
     * own right rather than only as a step of somebody's walk, which is what a cost gate needs of it.
     */
    @Test
    @DisplayName("the reach walk answers every view, stopping at a registered target")
    void theReachWalkAnswersEveryViewAndStopsAtRegisteredTargets() {
        withStore(dsl -> {
            prerequisite(dsl);
            dsl.execute("CREATE VIEW scratch_mid AS SELECT v FROM scratch_p");
            dsl.execute("CREATE VIEW scratch_t_live AS SELECT v FROM scratch_mid");
            dsl.execute("CREATE TABLE scratch_t (v INT)");
            register(dsl, "scratch_t_live", "scratch_t");

            var reached = MaterializeDependencies.registrationsReachedByView(dsl);

            assertThat(reached.get("scratch_t_live"))
                .as("the registered source view reaches the prerequisite through the intermediate")
                .containsExactly("scratch_p_live");
            assertThat(reached.get("scratch_mid"))
                .as("an unregistered view is answered in its own right, which is the reach a cost"
                    + " claim over that view ranges over")
                .containsExactly("scratch_p_live");
            assertThat(reached.get("scratch_p_live"))
                .as("the prerequisite reads a base table alone and so reaches no registration")
                .isEmpty();
            assertThat(reached.keySet())
                .as("every view in the store is a key, base tables are not")
                .contains("scratch_t_live", "scratch_mid", "scratch_p_live")
                .doesNotContain("scratch_src", "scratch_p", "scratch_t");
        });
    }

    private static void register(DSLContext dsl, String sourceViewName, String targetTableName) {
        dsl.insertInto(META_MATERIALIZE,
                META_MATERIALIZE.SOURCE_VIEW_NAME,
                META_MATERIALIZE.TARGET_TABLE_NAME,
                META_MATERIALIZE.REASON)
            .values(sourceViewName, targetTableName, "synthetic registration for this test")
            .execute();
    }

    /**
     * The synthetic registrations' edges alone. Scoped to the {@code scratch_} prefix because the
     * production DDL now registers a dependent derivation of its own, so the relation is no longer
     * empty on an untouched store and a case about what a fixture's reads state would otherwise be
     * asserting over the shipped edge as well.
     */
    private static List<org.assertj.core.groups.Tuple> dependencyRows(DSLContext dsl) {
        return dsl.select(META_MATERIALIZE_DEPENDENCY.SOURCE_VIEW_NAME,
                META_MATERIALIZE_DEPENDENCY.DEPENDS_ON)
            .from(META_MATERIALIZE_DEPENDENCY)
            .where(META_MATERIALIZE_DEPENDENCY.SOURCE_VIEW_NAME.like("scratch\\_%"))
            .orderBy(META_MATERIALIZE_DEPENDENCY.SOURCE_VIEW_NAME,
                META_MATERIALIZE_DEPENDENCY.DEPENDS_ON)
            .fetch(row -> tuple(row.value1(), row.value2()));
    }

    private static List<Integer> targetRows(DSLContext dsl, String targetTableName) {
        return dsl.fetch("SELECT v FROM " + targetTableName + " ORDER BY v")
            .getValues(0, Integer.class);
    }
}
