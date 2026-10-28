package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.ArgBinding;
import no.sikt.graphitron.command.ColumnTerm;
import no.sikt.graphitron.command.ConditionCommand;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.command.MatchKind;
import no.sikt.graphitron.command.Predicate;
import no.sikt.graphitron.command.ReachPath;
import no.sikt.graphitron.command.UnitMethodRef;
import no.sikt.graphitron.command.UnitRef;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.model.jooq.TableRef;
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
        var code = PathFragments.reachExists(reach, List.of("table_fkt0_0"), "table", INNER).toString();
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
        var code = PathFragments.reachExists(reach, aliases, "table", INNER).toString();
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
        var code = PathFragments.reachExists(reach, List.of("table_fkt0_0"), "table", INNER).toString();
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
        var code = PathFragments.reachExists(reach, List.of("table_fkt0_0"), "table", INNER).toString();
        assertThat(code).contains(".where(table_fkt0_0.FILM_ID.eq(table.FILM_ID).and(inner()))");
    }

    // ===== List-of-input row reads: the helper's table is the cells' table =====

    @Test
    void reachedListLevel_declaresItsRowHelperOverTheTerminalTable() {
        // A reached level's cells name actor's columns, which film_actor's class does not have, so
        // the helper must take actor's class; what the call passes is pinned by compilation.
        var firstName = TestFixtures.col("first_name", "FIRST_NAME", "java.lang.String");
        var lastName = TestFixtures.col("last_name", "LAST_NAME", "java.lang.String");
        var rows = new CallSiteExtraction.ElementRows.Whole(List.of(
            new CallSiteExtraction.Cell(List.of("firstName"), firstName, new CallSiteExtraction.Direct(), true),
            new CallSiteExtraction.Cell(List.of("lastName"), lastName, new CallSiteExtraction.Direct(), true)));
        var reached = new ArgBinding(new CallParam("names", rows, true, "org.jooq.Row2"), "names")
            .reachedAt(ACTOR);
        var term = new ColumnTerm(List.of(firstName, lastName), MatchKind.MEMBERSHIP, false, reached,
            new ReachPath(List.of(actorFkHop())));

        var helper = rowHelperOf(render(term));
        assertThat(helper.parameters().get(1).type()).isEqualTo(CatalogRefs.tableClass(ACTOR));
    }

    @Test
    void ownTableListLevel_declaresItsRowHelperOverTheGluesTable() {
        var rows = new CallSiteExtraction.ElementRows.Whole(List.of(
            new CallSiteExtraction.Cell(List.of("actorId"), ACTOR_ID, new CallSiteExtraction.Direct(), true),
            new CallSiteExtraction.Cell(List.of("filmId"), FILM_ID, new CallSiteExtraction.Direct(), true)));
        var own = new ArgBinding(new CallParam("pairs", rows, true, "org.jooq.Row2"), "pairs");
        var term = new ColumnTerm(List.of(ACTOR_ID, FILM_ID), MatchKind.MEMBERSHIP, false, own,
            ReachPath.none());

        var helper = rowHelperOf(render(term));
        assertThat(helper.parameters().get(1).type()).isEqualTo(CatalogRefs.tableClass(FILM_ACTOR));
    }

    // ===== Partial list-of-input reads: a match helper over the cells' table =====

    @Test
    void ownTablePartialLevel_declaresItsMatchHelperOverTheGluesTable() {
        // A partial level compares each element's carried cells itself, so it registers a match
        // helper answering the finished condition instead of a row helper. What the helper does
        // with an element is pinned by the execution tier.
        var rows = new CallSiteExtraction.ElementRows.Partial(List.of(
            new CallSiteExtraction.Cell(List.of("actorId"), ACTOR_ID, new CallSiteExtraction.Direct(), true),
            new CallSiteExtraction.Cell(List.of("filmId"), FILM_ID, new CallSiteExtraction.Direct(), false)));
        var own = new ArgBinding(new CallParam("pairs", rows, true, "org.jooq.Condition"), "pairs");
        var term = new ColumnTerm(List.of(ACTOR_ID, FILM_ID), MatchKind.PARTIAL_MEMBERSHIP, true, own,
            ReachPath.none());

        var conditions = render(term);
        var helper = matchHelperOf(conditions);
        assertThat(helper.name()).isEqualTo("pairsMatch");
        assertThat(helper.returnType()).isEqualTo(ClassName.get("org.jooq", "Condition"));
        assertThat(helper.parameters()).extracting(p -> p.type())
            .containsExactly(ClassName.get(Object.class), CatalogRefs.tableClass(FILM_ACTOR));
        assertThat(conditions.methodSpecs()).noneMatch(m -> m.name().endsWith("Rows"));
    }

    @Test
    void reachedPartialLevel_declaresItsMatchHelperOverTheTerminalTable() {
        // The helper compares against the terminal hop alias, so it must take actor's class; which
        // alias the call passes is pinned by compilation and execution.
        var firstName = TestFixtures.col("first_name", "FIRST_NAME", "java.lang.String");
        var lastName = TestFixtures.col("last_name", "LAST_NAME", "java.lang.String");
        var rows = new CallSiteExtraction.ElementRows.Partial(List.of(
            new CallSiteExtraction.Cell(List.of("firstName"), firstName, new CallSiteExtraction.Direct(), false),
            new CallSiteExtraction.Cell(List.of("lastName"), lastName, new CallSiteExtraction.Direct(), false)));
        var reached = new ArgBinding(new CallParam("names", rows, true, "org.jooq.Condition"), "names")
            .reachedAt(ACTOR);
        var term = new ColumnTerm(List.of(firstName, lastName), MatchKind.PARTIAL_MEMBERSHIP, false, reached,
            new ReachPath(List.of(actorFkHop())));

        var helper = matchHelperOf(render(term));
        assertThat(helper.parameters().get(1).type()).isEqualTo(CatalogRefs.tableClass(ACTOR));
    }

    private static TypeSpec render(ColumnTerm term) {
        var row = new ConditionCommand(FieldCoordinates.coordinates("Query", "filmActors"), FILM_ACTOR,
            List.of(new Predicate.Generated(List.of(term))),
            new UnitMethodRef(new UnitRef("fake.code.generated.conditions", "QueryConditions"),
                "filmActorsCondition"),
            List.of(), List.of());
        var classes = ConditionGlueRenderer.render(List.of(row), "fake.code.generated",
            KeyProjectionRelation.empty());
        assertThat(classes).hasSize(1);
        return classes.get(0);
    }

    private static MethodSpec matchHelperOf(TypeSpec conditions) {
        var helpers = conditions.methodSpecs().stream().filter(m -> m.name().endsWith("Match")).toList();
        assertThat(helpers).hasSize(1);
        return helpers.get(0);
    }

    private static MethodSpec rowHelperOf(TypeSpec conditions) {
        var helpers = conditions.methodSpecs().stream().filter(m -> m.name().endsWith("Rows")).toList();
        assertThat(helpers).hasSize(1);
        return helpers.get(0);
    }
}
