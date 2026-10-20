package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField.NullContract;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField.PresenceLeaf;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField.SubtreePresence;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link NestingField#subtreePresence()}, {@link NestingField#nullContract()}
 * and the parent-row demand derived from them: a nullable wrapper over column leaves collapses to
 * {@code null} when every presence leaf is absent, a non-null or list-wrapped one never does, and
 * a child fetched another way blocks the collapse for its own wrapper and every enclosing one.
 * A single reference whose first hop pairs columns is a leaf over the parent-side columns of that
 * hop. The list-reference and {@code @splitQuery} blockers are classified from SDL in
 * {@code NestingFieldPipelineTest}; here the blocking arm is exercised with an
 * {@code @externalField} leaf, a list reference and a predicate-joined reference.
 */
@UnitTier
class NestingFieldNullContractTest {

    private static final ColumnRef YEAR = TestFixtures.col("release_year", "RELEASE_YEAR", "java.lang.Integer");
    private static final ColumnRef LENGTH = TestFixtures.col("length", "LENGTH", "java.lang.Integer");
    private static final ColumnRef FILM_ID = TestFixtures.col("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef LANGUAGE_ID = TestFixtures.col("language_id", "LANGUAGE_ID", "java.lang.Integer");
    private static final ColumnRef ORIGINAL_LANGUAGE_ID =
        TestFixtures.col("original_language_id", "ORIGINAL_LANGUAGE_ID", "java.lang.Integer");
    private static final ColumnRef COUNTRY_ID = TestFixtures.col("country_id", "COUNTRY_ID", "java.lang.Integer");
    private static final ColumnRef NAME = TestFixtures.col("name", "NAME", "java.lang.String");

    private static ChildField column(String name, ColumnRef column) {
        return new ChildField.ColumnBackedField("Release", name, null, List.of(column),
            new CallSiteCompaction.Direct());
    }

    private static ChildField nodeId(String name, ColumnRef... columns) {
        var encode = new HelperRef.Encode(ClassName.get("test", "NodeIdEncoder"), "encodeFilm",
            List.of(columns));
        return new ChildField.ColumnBackedField("Release", name, null, List.of(columns),
            new CallSiteCompaction.NodeIdEncodeKeys(encode));
    }

    private static ChildField computed(String name) {
        return new ChildField.ComputedField("Release", name, null,
            new ReturnTypeRef.ScalarReturnType("String", new FieldWrapper.Single(true)), List.of(),
            TestFixtures.staticOnlyMethodRef("test.Helpers", name, ClassName.get(String.class)),
            AliasOwner.shared(), no.sikt.graphitron.rewrite.model.AliasScope.spliced());
    }

    private static JoinStep.Hop fkHop(List<ColumnRef> source, TableRef target, List<ColumnRef> targetSide,
                                      String alias) {
        return TestFixtures.fkJoin(TestFixtures.foreignKeyRef("fk_" + alias), TestFixtures.filmTable(),
            source, target, targetSide, null, alias);
    }

    private static ChildField.TableField reference(String name, FieldWrapper wrapper, List<JoinStep> path) {
        return new ChildField.TableField("Release", name, null,
            new ReturnTypeRef.TableBoundReturnType("Language", TestFixtures.languageTable(), wrapper),
            path, List.of(), new OrderBySpec.None(), null, LookupResolution.None.INSTANCE,
            TestFixtures.pcFor(path, TestFixtures.filmTable()), AliasOwner.shared(), no.sikt.graphitron.rewrite.model.AliasScope.spliced());
    }

    private static ChildField.TableField singleReference(String name, JoinStep.Hop... path) {
        return reference(name, new FieldWrapper.Single(true), List.of(path));
    }

    private static NestingField nesting(String name, FieldWrapper wrapper, ChildField... children) {
        return new NestingField("Film", name, null,
            new ReturnTypeRef.TableBoundReturnType(name + "Type", TestFixtures.filmTable(), wrapper),
            List.of(children));
    }

    private static NestingField nullable(String name, ChildField... children) {
        return nesting(name, new FieldWrapper.Single(true), children);
    }

    private static NestingField nonNull(String name, ChildField... children) {
        return nesting(name, new FieldWrapper.Single(false), children);
    }

    @Test
    void columnsOnly_collapsesWithOneLeafPerColumn() {
        var nf = nullable("release", column("year", YEAR), column("minutes", LENGTH));
        assertThat(nf.nullContract()).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf(List.of(YEAR)), new PresenceLeaf(List.of(LENGTH)))));
        assertThat(nf.parentRowColumns()).containsExactly(YEAR, LENGTH);
    }

    @Test
    void nodeIdLeaf_isOneLeafOverItsKeyColumns() {
        // The encoder answers null on any null key value, so the leaf is present only when every
        // key column is: one leaf over both columns, not two leaves.
        var nf = nullable("release", nodeId("id", FILM_ID, LANGUAGE_ID), column("year", YEAR));
        assertThat(nf.nullContract()).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf(List.of(FILM_ID, LANGUAGE_ID)), new PresenceLeaf(List.of(YEAR)))));
        assertThat(nf.parentRowColumns()).containsExactly(FILM_ID, LANGUAGE_ID, YEAR);
    }

    @Test
    void innerNestingFields_contributeTheirLeavesWhateverTheirNullability() {
        var nf = nullable("release",
            column("year", YEAR),
            nonNull("required", column("minutes", LENGTH)),
            nullable("optional", column("id", FILM_ID)));
        assertThat(nf.nullContract()).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf(List.of(YEAR)), new PresenceLeaf(List.of(LENGTH)),
            new PresenceLeaf(List.of(FILM_ID)))));
    }

    @Test
    void sharedColumn_isDemandedOnce() {
        var nf = nullable("release", column("year", YEAR), nonNull("again", column("year", YEAR)));
        assertThat(nf.parentRowColumns()).containsExactly(YEAR);
    }

    @Test
    void nonNullOuter_isAlwaysAnObjectAndDemandsNothing() {
        var nf = nonNull("release", column("year", YEAR));
        assertThat(nf.nullContract())
            .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.NonNull()));
        assertThat(nf.parentRowColumns()).isEmpty();
        // Its own subtree still answers its leaves, for an enclosing wrapper to count.
        assertThat(nf.subtreePresence())
            .isEqualTo(new SubtreePresence.Leaves(List.of(new PresenceLeaf(List.of(YEAR)))));
    }

    @Test
    void listWrapped_answersNotSingleWithoutACastFailure() {
        var nf = nesting("release", new FieldWrapper.List(true, true), column("year", YEAR));
        assertThat(nf.nullContract())
            .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.NotSingle()));
        assertThat(nf.parentRowColumns()).isEmpty();
    }

    @Test
    void otherChild_blocksTheWrapperNamingThatChild() {
        var blocker = computed("label");
        var nf = nullable("release", column("year", YEAR), blocker);
        assertThat(nf.subtreePresence()).isEqualTo(new SubtreePresence.Blocked(blocker));
        assertThat(nf.nullContract())
            .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.BlockedBy(blocker)));
        assertThat(nf.parentRowColumns()).isEmpty();
    }

    @Test
    void blockedInnerNesting_blocksEveryEnclosingWrapper() {
        var blocker = computed("label");
        var inner = nonNull("inner", column("minutes", LENGTH), blocker);
        var middle = nullable("middle", inner);
        var outer = nullable("release", column("year", YEAR), middle);
        assertThat(middle.nullContract())
            .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.BlockedBy(blocker)));
        assertThat(outer.nullContract())
            .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.BlockedBy(blocker)));
    }

    // ===== Reference leaves =====

    @Test
    void singleFkReference_isOneLeafOverItsJoinColumns() {
        var nf = nullable("release", column("year", YEAR),
            singleReference("originalLanguage",
                fkHop(List.of(ORIGINAL_LANGUAGE_ID), TestFixtures.languageTable(), List.of(LANGUAGE_ID), "ol_0")));
        assertThat(nf.nullContract()).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf(List.of(YEAR)), new PresenceLeaf(List.of(ORIGINAL_LANGUAGE_ID)))));
        assertThat(nf.parentRowColumns()).containsExactly(YEAR, ORIGINAL_LANGUAGE_ID);
    }

    @Test
    void compositeFkReference_isOneLeafOverAllItsJoinColumns() {
        // Equality on any null column matches no row, so the leaf is present only when all are.
        var nf = nullable("release",
            singleReference("pair", fkHop(List.of(FILM_ID, LANGUAGE_ID), TestFixtures.languageTable(),
                List.of(LANGUAGE_ID, FILM_ID), "pair_0")));
        assertThat(nf.nullContract()).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf(List.of(FILM_ID, LANGUAGE_ID)))));
    }

    @Test
    void listReference_blocksTheWrapper() {
        var blocker = reference("languages", new FieldWrapper.List(true, true), List.of(
            fkHop(List.of(LANGUAGE_ID), TestFixtures.languageTable(), List.of(LANGUAGE_ID), "ls_0")));
        var nf = nullable("release", column("year", YEAR), blocker);
        assertThat(nf.nullContract())
            .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.BlockedBy(blocker)));
        assertThat(nf.parentRowColumns()).isEmpty();
    }

    @Test
    void predicateJoinedReference_blocksTheWrapper() {
        // No parent-row column decides an authored predicate's match.
        var condition = TestFixtures.staticOnlyMethodRef("test.Conditions", "join",
            ClassName.get("org.jooq", "Condition"));
        var blocker = singleReference("language",
            TestFixtures.conditionJoin(condition, TestFixtures.languageTable(), "lang_0"));
        var nf = nullable("release", column("year", YEAR), blocker);
        assertThat(nf.nullContract())
            .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.BlockedBy(blocker)));
    }

    @Test
    void multiHopScalarReference_isALeafOverTheFirstHopsSourceColumnsOnly() {
        var path = List.<JoinStep>of(
            fkHop(List.of(LANGUAGE_ID), TestFixtures.languageTable(), List.of(LANGUAGE_ID), "c_0"),
            TestFixtures.fkJoin(TestFixtures.foreignKeyRef("fk_c_1"), TestFixtures.languageTable(),
                List.of(COUNTRY_ID), TestFixtures.joinTarget("country"), List.of(COUNTRY_ID), null, "c_1"));
        var scalar = new ChildField.ColumnBackedReferenceField("Release", "countryName", null,
            List.of(NAME), path, new CallSiteCompaction.Direct(),
            TestFixtures.pcFor(path, TestFixtures.filmTable()), AliasOwner.shared(), no.sikt.graphitron.rewrite.model.AliasScope.spliced());
        var nf = nullable("release", scalar);
        assertThat(nf.nullContract()).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf(List.of(LANGUAGE_ID)))));
    }

    @Test
    void referenceLeafInAnInnerNestingField_reachesTheOuterContract() {
        var inner = nonNull("origin", singleReference("originalLanguage",
            fkHop(List.of(ORIGINAL_LANGUAGE_ID), TestFixtures.languageTable(), List.of(LANGUAGE_ID), "ol_0")));
        var nf = nullable("release", column("year", YEAR), inner);
        assertThat(nf.nullContract()).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf(List.of(YEAR)), new PresenceLeaf(List.of(ORIGINAL_LANGUAGE_ID)))));
        assertThat(nf.parentRowColumns()).containsExactly(YEAR, ORIGINAL_LANGUAGE_ID);
    }
}
