package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.Presence.NullContract;
import no.sikt.graphitron.rewrite.model.Presence.PresenceLeaf;
import no.sikt.graphitron.rewrite.model.Presence.SubtreePresence;
import no.sikt.graphitron.rewrite.model.Presence.TypeFields;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the {@link Presence} rule: the leaf kind each field kind yields, the null
 * contract a wrapper's leaves give it, and the parent-row demand derived from it. A nullable
 * wrapper over leaves collapses to {@code null} when every leaf is absent, a non-null or
 * list-wrapped one never does, and a child no leaf decides blocks the collapse for its own wrapper
 * and every enclosing one. Columns are {@link PresenceLeaf.Columns} leaves; a single reference,
 * inline or {@code @splitQuery}, keyed or predicate-joined, and an argument-free list are
 * {@link PresenceLeaf.Probe} leaves over the field's own join path and filters.
 */
@UnitTier
class PresenceTest {

    private static final ColumnRef YEAR = TestFixtures.col("release_year", "RELEASE_YEAR", "java.lang.Integer");
    private static final ColumnRef LENGTH = TestFixtures.col("length", "LENGTH", "java.lang.Integer");
    private static final ColumnRef FILM_ID = TestFixtures.col("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef LANGUAGE_ID = TestFixtures.col("language_id", "LANGUAGE_ID", "java.lang.Integer");
    private static final ColumnRef ORIGINAL_LANGUAGE_ID =
        TestFixtures.col("original_language_id", "ORIGINAL_LANGUAGE_ID", "java.lang.Integer");
    private static final ColumnRef COUNTRY_ID = TestFixtures.col("country_id", "COUNTRY_ID", "java.lang.Integer");
    private static final ColumnRef NAME = TestFixtures.col("name", "NAME", "java.lang.String");

    /** {@code Language}: a type of columns only, so an object reference to it probes them. */
    private static final TypeFields TYPES = typeName -> switch (typeName) {
        case "Language" -> List.of(new ChildField.ColumnBackedField("Language", "name", null,
            List.of(NAME), new CallSiteCompaction.Direct()));
        case "LanguageWithFilms" -> List.of(
            new ChildField.ColumnBackedField("LanguageWithFilms", "name", null, List.of(NAME),
                new CallSiteCompaction.Direct()),
            computed("films"));
        default -> List.of();
    };

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
            AliasOwner.shared(), AliasScope.spliced());
    }

    private static JoinStep.Hop fkHop(List<ColumnRef> source, TableRef target, List<ColumnRef> targetSide,
                                      String alias) {
        return TestFixtures.fkJoin(TestFixtures.foreignKeyRef("fk_" + alias), TestFixtures.filmTable(),
            source, target, targetSide, null, alias);
    }

    private static JoinStep.Hop languageHop() {
        return fkHop(List.of(ORIGINAL_LANGUAGE_ID), TestFixtures.languageTable(), List.of(LANGUAGE_ID), "ol_0");
    }

    private static ChildField.TableField reference(String name, String returnType, FieldWrapper wrapper,
            List<JoinStep> path, List<WhereFilter> filters, PaginationSpec pagination) {
        return new ChildField.TableField("Release", name, null,
            new ReturnTypeRef.TableBoundReturnType(returnType, TestFixtures.languageTable(), wrapper),
            path, filters, new OrderBySpec.None(), pagination, LookupResolution.None.INSTANCE,
            TestFixtures.pcFor(path, TestFixtures.filmTable()), AliasOwner.shared(), AliasScope.spliced());
    }

    private static ChildField.TableField singleReference(String name, JoinStep.Hop... path) {
        return reference(name, "Language", new FieldWrapper.Single(true), List.of(path), List.of(), null);
    }

    private static ChildField.TableField listReference(String name, JoinStep.Hop... path) {
        return reference(name, "Language", new FieldWrapper.List(true, true), List.of(path), List.of(), null);
    }

    private static ChildField.BatchedTableField splitReference(String name, FieldWrapper wrapper) {
        var returnType = new ReturnTypeRef.TableBoundReturnType("Language", TestFixtures.languageTable(), wrapper);
        var path = List.<JoinStep>of(languageHop());
        return new ChildField.BatchedTableField("Release", name, null, returnType,
            path, List.of(), new OrderBySpec.None(), null, SourceShape.Table,
            TestFixtures.splitSourceKey(List.of(ORIGINAL_LANGUAGE_ID)),
            TestFixtures.fkColumnsLift(),
            TestFixtures.loaderRegistration(returnType, false, false),
            LookupResolution.None.INSTANCE,
            TestFixtures.pcFor(path, TestFixtures.filmTable()));
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

    private static PresenceLeaf.Columns columns(ColumnRef... columns) {
        return new PresenceLeaf.Columns(List.of(columns));
    }

    private static NullContract contract(NestingField nf) {
        return nf.nullContract(TYPES);
    }

    private static NullContract blockedBy(ChildField blocker) {
        return new NullContract.AlwaysObject(new NullContract.Reason.BlockedBy(blocker));
    }

    // ===== Column leaves =====

    @Test
    void columnsOnly_collapsesWithOneLeafPerColumn() {
        var nf = nullable("release", column("year", YEAR), column("minutes", LENGTH));
        assertThat(contract(nf)).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            columns(YEAR), columns(LENGTH))));
        assertThat(nf.parentRowColumns()).containsExactly(YEAR, LENGTH);
    }

    @Test
    void nodeIdLeaf_isOneLeafOverItsKeyColumns() {
        // The encoder answers null on any null key value, so the leaf is present only when every
        // key column is: one leaf over both columns, not two leaves.
        var nf = nullable("release", nodeId("id", FILM_ID, LANGUAGE_ID), column("year", YEAR));
        assertThat(contract(nf)).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            columns(FILM_ID, LANGUAGE_ID), columns(YEAR))));
        assertThat(nf.parentRowColumns()).containsExactly(FILM_ID, LANGUAGE_ID, YEAR);
    }

    @Test
    void innerNestingFields_contributeTheirLeavesWhateverTheirNullability() {
        var nf = nullable("release",
            column("year", YEAR),
            nonNull("required", column("minutes", LENGTH)),
            nullable("optional", column("id", FILM_ID)));
        assertThat(contract(nf)).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            columns(YEAR), columns(LENGTH), columns(FILM_ID))));
    }

    @Test
    void sharedColumn_isDemandedOnce() {
        var nf = nullable("release", column("year", YEAR), nonNull("again", column("year", YEAR)));
        assertThat(nf.parentRowColumns()).containsExactly(YEAR);
    }

    @Test
    void nonNullOuter_isAlwaysAnObjectAndDemandsNothing() {
        var nf = nonNull("release", column("year", YEAR));
        assertThat(contract(nf)).isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.NonNull()));
        assertThat(nf.parentRowColumns()).isEmpty();
        // Its own subtree still answers its leaves, for an enclosing wrapper to count.
        assertThat(SubtreePresence.over(nf.nestedFields(), TYPES))
            .isEqualTo(new SubtreePresence.Leaves(List.of(columns(YEAR))));
    }

    @Test
    void listWrapped_answersNotSingleWithoutACastFailure() {
        var nf = nesting("release", new FieldWrapper.List(true, true), column("year", YEAR));
        assertThat(contract(nf)).isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.NotSingle()));
        assertThat(nf.parentRowColumns()).isEmpty();
    }

    @Test
    void otherChild_blocksTheWrapperNamingThatChild() {
        var blocker = computed("label");
        var nf = nullable("release", column("year", YEAR), blocker);
        assertThat(SubtreePresence.over(nf.nestedFields(), TYPES)).isEqualTo(new SubtreePresence.Blocked(blocker));
        assertThat(contract(nf)).isEqualTo(blockedBy(blocker));
        assertThat(nf.parentRowColumns()).isEmpty();
    }

    @Test
    void blockedInnerNesting_blocksEveryEnclosingWrapper() {
        var blocker = computed("label");
        var inner = nonNull("inner", column("minutes", LENGTH), blocker);
        var middle = nullable("middle", inner);
        var outer = nullable("release", column("year", YEAR), middle);
        assertThat(contract(middle)).isEqualTo(blockedBy(blocker));
        assertThat(contract(outer)).isEqualTo(blockedBy(blocker));
    }

    // ===== Probe leaves =====

    @Test
    void singleObjectReference_probesItsTargetTypesColumnLeaves() {
        // Its join column being set does not prove a row exists, so the reference counts by value:
        // a referenced row holding a non-null column of the target type.
        var reference = singleReference("originalLanguage", languageHop());
        var nf = nullable("release", column("year", YEAR), reference);
        assertThat(contract(nf)).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            columns(YEAR),
            new PresenceLeaf.Probe(reference, reference.joinPath(), List.of(), List.of(NAME)))));
        // The probe is not a parent-row column: the demand is the column leaves alone.
        assertThat(nf.parentRowColumns()).containsExactly(YEAR);
    }

    @Test
    void objectReferenceWhoseTargetHoldsMoreThanColumns_countsItsRowsExistence() {
        var reference = reference("language", "LanguageWithFilms", new FieldWrapper.Single(true),
            List.of(languageHop()), List.of(), null);
        var nf = nullable("release", reference);
        assertThat(contract(nf).probes()).singleElement()
            .satisfies(p -> assertThat(p.targetColumns()).isEmpty());
    }

    @Test
    void multiHopScalarReference_probesItsTerminalColumnOverTheWholePath() {
        var path = List.<JoinStep>of(
            fkHop(List.of(LANGUAGE_ID), TestFixtures.languageTable(), List.of(LANGUAGE_ID), "c_0"),
            TestFixtures.fkJoin(TestFixtures.foreignKeyRef("fk_c_1"), TestFixtures.languageTable(),
                List.of(COUNTRY_ID), TestFixtures.joinTarget("country"), List.of(COUNTRY_ID), null, "c_1"));
        var scalar = new ChildField.ColumnBackedReferenceField("Release", "countryName", null,
            List.of(NAME), path, new CallSiteCompaction.Direct(),
            TestFixtures.pcFor(path, TestFixtures.filmTable()), AliasOwner.shared(), AliasScope.spliced());
        var nf = nullable("release", scalar);
        assertThat(contract(nf)).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf.Probe(scalar, path, List.of(), List.of(NAME)))));
        assertThat(nf.parentRowColumns()).isEmpty();
    }

    @Test
    void predicateJoinedReference_probes() {
        var condition = TestFixtures.staticOnlyMethodRef("test.Conditions", "join",
            ClassName.get("org.jooq", "Condition"));
        var reference = singleReference("language",
            TestFixtures.conditionJoin(condition, TestFixtures.languageTable(), "lang_0"));
        var nf = nullable("release", column("year", YEAR), reference);
        assertThat(contract(nf).probes()).singleElement()
            .satisfies(p -> assertThat(p.field()).isSameAs(reference));
    }

    @Test
    void splitQueryReference_probesExactlyAsItsInlineForm() {
        var split = splitReference("originalLanguage", new FieldWrapper.Single(true));
        var nf = nullable("release", split);
        assertThat(contract(nf)).isEqualTo(new NullContract.CollapsesWhenAbsent(List.of(
            new PresenceLeaf.Probe(split, split.joinPath(), List.of(), List.of(NAME)))));
    }

    @Test
    void splitQueryChildOnAnotherConnection_blocks() {
        // Routed to another tenant or a global source, the split field may read tables the
        // parent's database does not hold, so no probe of it can run in the parent's statement.
        var split = splitReference("originalLanguage", new FieldWrapper.Single(true));
        var otherConnection = new TypeFields() {
            @Override public List<? extends GraphitronField> of(String typeName) {
                return TYPES.of(typeName);
            }

            @Override public boolean sharesParentConnection(ChildField.BatchedTableField field) {
                return false;
            }
        };
        var nf = nullable("release", column("year", YEAR), split);
        assertThat(nf.nullContract(otherConnection)).isEqualTo(blockedBy(split));
        // An inline reference needs no connection of its own and still probes.
        var inline = nullable("release", singleReference("originalLanguage", languageHop()));
        assertThat(inline.nullContract(otherConnection).probes()).hasSize(1);
    }

    @Test
    void argumentFreeList_probesForAnyRow() {
        var list = listReference("languages", languageHop());
        var splitList = splitReference("splitLanguages", new FieldWrapper.List(true, true));
        var nf = nullable("release", column("year", YEAR), list, splitList);
        assertThat(contract(nf).probes())
            .extracting(PresenceLeaf.Probe::field, PresenceLeaf.Probe::targetColumns)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(list, List.of()),
                org.assertj.core.groups.Tuple.tuple(splitList, List.of()));
    }

    @Test
    void referenceInAnInnerNestingField_reachesTheOuterContract() {
        var reference = singleReference("originalLanguage", languageHop());
        var nf = nullable("release", column("year", YEAR), nonNull("origin", reference));
        assertThat(contract(nf).probes()).singleElement()
            .satisfies(p -> assertThat(p.field()).isSameAs(reference));
        assertThat(nf.parentRowColumns()).containsExactly(YEAR);
    }

    @Test
    void probeTargetColumns_needATypeLookup_butNothingElseDoes() {
        var nf = nullable("release", column("year", YEAR), singleReference("originalLanguage", languageHop()));
        var withoutTypes = nf.nullContract(TypeFields.none());
        assertThat(withoutTypes.probes()).singleElement()
            .satisfies(p -> assertThat(p.targetColumns()).isEmpty());
        assertThat(withoutTypes.presenceColumns()).isEqualTo(contract(nf).presenceColumns());
    }

    // ===== Blockers =====

    @Test
    void listTakingArguments_blocks() {
        var pagination = new PaginationSpec(new PaginationSpec.PaginationArg("Int", false), null, null, null);
        var paged = reference("languages", "Language", new FieldWrapper.List(true, true),
            List.of(languageHop()), List.of(), pagination);
        assertThat(contract(nullable("release", column("year", YEAR), paged))).isEqualTo(blockedBy(paged));
    }

    @Test
    void connection_blocks() {
        var connection = reference("languages", "Language", new FieldWrapper.Connection(true, 10),
            List.of(languageHop()), List.of(), null);
        assertThat(contract(nullable("release", connection))).isEqualTo(blockedBy(connection));
    }

    @Test
    void filtersReadingTheFieldsOwnArguments_block() {
        // An unselected field has no arguments to serve the probe's filter.
        var arg = new CallParam("name", new CallSiteExtraction.Direct(), false, NAME.columnClass());
        var filter = new GeneratedConditionFilter(TestFixtures.languageTable(), List.of(arg),
            List.of(new BodyParam.Eq("name", NAME, "java.lang.String", false, new CallSiteExtraction.Direct())));
        var filtered = reference("language", "Language", new FieldWrapper.Single(true),
            List.of(languageHop()), List.of(filter), null);
        assertThat(contract(nullable("release", filtered))).isEqualTo(blockedBy(filtered));
    }

    @Test
    void conditionBindingNoArguments_probesWithItsFilter() {
        var condition = new ConditionFilter("test.Conditions", "activeOnly", List.of());
        var filtered = reference("language", "Language", new FieldWrapper.Single(true),
            List.of(languageHop()), List.of(condition), null);
        assertThat(contract(nullable("release", filtered)).probes()).singleElement()
            .satisfies(p -> assertThat(p.filters()).containsExactly(condition));
    }

    @Test
    void referenceThroughARoutine_blocks() {
        var routineHop = new JoinStep.Hop(
            new TableExpr.RoutineCall(new RoutineRef(ClassName.get("test", "Routines"), "languagesOf", List.of()),
                TestFixtures.languageTable()),
            new On.Lateral(), TestFixtures.filmTable(), null, "r_0");
        var path = List.<JoinStep>of(routineHop);
        var throughRoutine = new ChildField.TableField("Release", "language", null,
            new ReturnTypeRef.TableBoundReturnType("Language", TestFixtures.languageTable(), new FieldWrapper.Single(true)),
            path, List.of(), new OrderBySpec.None(), null, LookupResolution.None.INSTANCE,
            new ParentCorrelation.OnLateralArgs(routineHop), AliasOwner.shared(), AliasScope.spliced());
        assertThat(contract(nullable("release", column("year", YEAR), throughRoutine)))
            .isEqualTo(blockedBy(throughRoutine));
        assertThatThrownBy(() -> new PresenceLeaf.Probe(throughRoutine, path, List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uncorrelatedReadOfTheOwnTable_blocks() {
        var standalone = reference("anyLanguage", "Language", new FieldWrapper.Single(true),
            List.of(), List.of(), null);
        assertThat(contract(nullable("release", standalone))).isEqualTo(blockedBy(standalone));
    }

    @Test
    void recordSourcedSplitField_blocks() {
        var returnType = new ReturnTypeRef.TableBoundReturnType("Language", TestFixtures.languageTable(),
            new FieldWrapper.Single(true));
        var path = List.<JoinStep>of(languageHop());
        var recordSourced = new ChildField.BatchedTableField("Release", "language", null, returnType,
            path, List.of(), new OrderBySpec.None(), null, SourceShape.Record,
            TestFixtures.recordParentRowSourceKey(List.of(ORIGINAL_LANGUAGE_ID)),
            TestFixtures.fkColumnsLift(),
            TestFixtures.loaderRegistration(returnType, false, false),
            LookupResolution.None.INSTANCE,
            TestFixtures.pcFor(path, TestFixtures.filmTable()));
        assertThat(contract(nullable("release", recordSourced))).isEqualTo(blockedBy(recordSourced));
    }
}
