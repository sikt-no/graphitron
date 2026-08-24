package no.sikt.graphitron.render;

import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-arm coverage of the condition glue renderer's reach dispatch: a total function over record
 * literals, needing no schema, catalog, or fixture plumbing. What is under test is that the reach
 * {@code EXISTS} dispatches every {@link no.sikt.graphitron.rewrite.model.On} arm through
 * {@link PathFragments} rather than assuming column pairs, in both places a reach path is read,
 * the hop-0 correlation and the walk-back bridging joins. SQL behaviour is the execution tier's,
 * and the whole rendered statement is pinned as SQL by the sakila baseline.
 */
@UnitTier
class ConditionGlueRendererTest {

    private static final ColumnRef FILM_ID = TestFixtures.col("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef ACTOR_ID = TestFixtures.col("actor_id", "ACTOR_ID", "java.lang.Integer");

    private static final TableRef FILM = TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID));
    private static final TableRef FILM_ACTOR = TestFixtures.tableRef(
        "film_actor", "FILM_ACTOR", "FilmActor", List.of(ACTOR_ID, FILM_ID));
    private static final TableRef ACTOR = TestFixtures.tableRef("actor", "ACTOR", "Actor", List.of(ACTOR_ID));

    private static JoinConditionRef stubCondition(String methodName) {
        return new JoinConditionRef(TestFixtures.staticOnlyMethodRef(
            "no.sikt.graphitron.rewrite.TestConditionStub", methodName,
            ClassName.get("org.jooq", "Condition")));
    }

    /** film -> film_actor on the developer's predicate. */
    private static JoinStep.Hop predicateHop() {
        return TestFixtures.conditionJoin(
            stubCondition("intermediate").method(), FILM_ACTOR, "films_0");
    }

    /** film -> film_actor on the catalog FK, optionally carrying a per-hop filter. */
    private static JoinStep.Hop fkHop(JoinConditionRef filter) {
        return TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_actor_film_id_fkey"),
            FILM, List.of(FILM_ID),
            FILM_ACTOR, List.of(FILM_ID),
            filter, "films_0");
    }

    /** film_actor -> actor on the catalog FK, the interior/terminal hop of a two-hop reach. */
    private static JoinStep.Hop actorFkHop() {
        return TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_actor_actor_id_fkey"),
            FILM_ACTOR, List.of(ACTOR_ID),
            ACTOR, List.of(ACTOR_ID),
            null, "films_1");
    }

    private static final CodeBlock INNER = CodeBlock.of("inner()");

    // ===== hopZeroCorrelation: the one genuinely new dispatch point =====

    @Test
    void hopZeroCorrelation_predicateArm_emitsTheTwoArgCallParentFirst() {
        // The developer's method receives (source, target) = (parent local, first-hop alias),
        // the same convention the per-hop filter() calls and the projection rail's arms emit.
        // Argument order is the whole assertion: reversing it hands the wrong alias to a
        // concretely-typed parameter and fails to compile at the consumer.
        var code = PathFragments.hopZeroCorrelation(predicateHop(), "table_fkt0_0", "table")
            .toString();
        assertThat(code).isEqualTo(
            "no.sikt.graphitron.rewrite.TestConditionStub.intermediate(table, table_fkt0_0)");
    }

    @Test
    void hopZeroCorrelation_columnPairsArm_emitsTheSlotEqualitiesUnchanged() {
        // The unchanged case, pinned beside the new one: an FK hop still correlates on its slots,
        // target side against parent side.
        var code = PathFragments.hopZeroCorrelation(fkHop(null), "table_fkt0_0", "table").toString();
        assertThat(code).isEqualTo("table_fkt0_0.FILM_ID.eq(table.FILM_ID)");
    }

    // ===== reachExists: the two dispatch points and the hop filters, in place =====

    @Test
    void reachExists_predicateHopZero_wrapsTheDeveloperCallAsTheCorrelation() {
        var reach = new ReachPath(List.of(predicateHop()));
        var code = ConditionGlueRenderer.reachExists(reach, List.of("table_fkt0_0"), INNER).toString();
        assertThat(code)
            .contains("org.jooq.impl.DSL.exists(")
            .contains(".from(table_fkt0_0)")
            .contains(".where(no.sikt.graphitron.rewrite.TestConditionStub.intermediate(table, "
                + "table_fkt0_0).and(inner()))");
    }

    @Test
    void reachExists_interiorHop_bridgesThroughPathFragmentsDispatch() {
        // Two hops, walked terminal-first: the FROM is the terminal alias and the previous node
        // joins in. The delegation is what is pinned, so the assertion is the fragment
        // PathFragments.emitBackwardBridging produces for this hop, verbatim.
        var terminal = actorFkHop();
        var reach = new ReachPath(List.of(predicateHop(), terminal));
        var aliases = List.of("table_fkt0_0", "table_fkt0_1");
        var code = ConditionGlueRenderer.reachExists(reach, aliases, INNER).toString();
        assertThat(code)
            .contains(".from(table_fkt0_1)")
            .contains(PathFragments.emitBackwardBridging(
                terminal, "table_fkt0_0", "table_fkt0_1", "condition-reach").toString())
            .contains("no.sikt.graphitron.rewrite.TestConditionStub.intermediate(table, table_fkt0_0)");
    }

    @Test
    void reachExists_hopZeroFilter_isEmittedInsideTheExists() {
        // A {key:, condition:} element folds its predicate onto the hop rather than becoming the
        // hop's ON. Omitting it emits a filter wider than the schema declares: rows the author's
        // predicate excludes come back. Source is the parent local at hop 0.
        var reach = new ReachPath(List.of(fkHop(stubCondition("splitFilterParentIncluded"))));
        var code = ConditionGlueRenderer.reachExists(reach, List.of("table_fkt0_0"), INNER).toString();
        assertThat(code).contains(
            ".where(table_fkt0_0.FILM_ID.eq(table.FILM_ID)"
            + ".and(no.sikt.graphitron.rewrite.TestConditionStub.splitFilterParentIncluded(table, table_fkt0_0))"
            + ".and(inner()))");
    }

    @Test
    void reachExists_noHopFilters_emitsTheCorrelationAndInnerAlone() {
        // The shape every shipped reach renders, pinned so the hop-filter conjunct above is
        // visibly conditional on a hop carrying one rather than always present.
        var reach = new ReachPath(List.of(fkHop(null)));
        var code = ConditionGlueRenderer.reachExists(reach, List.of("table_fkt0_0"), INNER).toString();
        assertThat(code).contains(".where(table_fkt0_0.FILM_ID.eq(table.FILM_ID).and(inner()))");
    }
}
