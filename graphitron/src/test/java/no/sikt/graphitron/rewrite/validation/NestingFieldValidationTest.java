package no.sikt.graphitron.rewrite.validation;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.AliasOwner;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class NestingFieldValidationTest {

    private static final TableRef FILM_TABLE = TestFixtures.tableRef("film", "FILM", "Film", List.of());
    private static final TableRef ADVERTISEMENT_TABLE = TestFixtures.tableRef("advertisement", "ADVERTISEMENT", "Advertisement", List.of());

    private static NestingField nestingField(String parent, String name, List<ChildField> nested, TableRef parentTable) {
        return new NestingField(parent, name, null,
            new ReturnTypeRef.TableBoundReturnType("FilmDetails", parentTable, new FieldWrapper.Single(true)),
            nested);
    }

    private static ColumnBackedField titleOn(String parent, String column, String javaType) {
        return new ColumnBackedField(parent, "title", null,
            List.of(new ColumnRef(column, "TITLE", javaType)),
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct());
    }

    /**
     * A plain projected reference leaf: a scalar {@code @field} + {@code @reference} hop, the
     * {@link no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct} shape that emits a
     * correlated scalar subselect against the anchor's table.
     */
    private static ColumnBackedReferenceField languageNameOn(String parentTypeName) {
        var path = List.<JoinStep>of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), null, List.of(TestFixtures.languageIdCol()),
            TestFixtures.joinTarget("language"), List.of(TestFixtures.languageIdCol()), null, ""));
        return new ColumnBackedReferenceField(parentTypeName, "languageName", null,
            List.of(new ColumnRef("NAME", "", "")),
            path,
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct(),
            TestFixtures.pcFor(path, TestFixtures.filmTable()), AliasOwner.shared());
    }

    private static final ParameterizedTypeName BOOLEAN_FIELD =
        ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"), ClassName.get(Boolean.class));

    private static final MethodRef IS_ENGLISH = TestFixtures.staticServiceMethodRef(
        "com.example.FilmExtensions", "isEnglish",
        ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"), ClassName.get(Boolean.class)),
        List.of(new MethodRef.Param.Typed("table", "com.example.tables.Film", new ParamSource.Table())));

    /** An {@code @externalField} expression leaf: no join path, the supported form. */
    private static ComputedField isEnglishOn(String parentTypeName) {
        return new ComputedField(parentTypeName, "isEnglish", null,
            new ReturnTypeRef.ScalarReturnType("Boolean", new FieldWrapper.Single(true)),
            List.of(),
            IS_ENGLISH, AliasOwner.shared());
    }

    /**
     * An arity-2 NodeId-encoded reference carrier (the merged leaf's composite shape) on a
     * single FK hop, with the arity-independent parent correlation the construction site derives.
     */
    private static ColumnBackedReferenceField compositeLanguageRef(String parentTypeName) {
        var path = List.<JoinStep>of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), null, List.of(TestFixtures.languageIdCol()),
            TestFixtures.joinTarget("language"), List.of(TestFixtures.languageIdCol()), null, ""));
        return new ColumnBackedReferenceField(parentTypeName, "languageRef", null,
            List.of(new ColumnRef("ID_1", "", ""), new ColumnRef("ID_2", "", "")),
            path,
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys(
                new HelperRef.Encode(ClassName.bestGuess("com.example.NodeIds"), "encodeLanguage",
                    List.of(new ColumnRef("ID_1", "java.lang.Integer", ""), new ColumnRef("ID_2", "java.lang.Integer", "")))),
            TestFixtures.pcFor(path, TestFixtures.filmTable()), AliasOwner.shared());
    }

    enum Case implements ValidatorCase {

        EMPTY("nesting field with no nested fields — no error",
            new NestingField("Film", "nested", null,
                new ReturnTypeRef.TableBoundReturnType("Film",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of()),
            List.of()),

        LIST_CARDINALITY_REJECTED("list cardinality on a NestingField → error",
            new NestingField("Film", "tags", null,
                new ReturnTypeRef.TableBoundReturnType("Tag",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.List(false, false)),
                List.of()),
            List.of("Field 'Film.tags': list cardinality on a plain-object nesting field is not supported")),

        // The next two do not assert "no error" even though ColumnBackedReferenceField is now
        // admitted at nested depth: both fixtures carry a NodeIdEncodeKeys compaction, which
        // validateColumnBackedReferenceField rejects on its own account. That per-variant arm is
        // exactly what the blanket nested-depth gate used to shadow — before the nested walk ran
        // per-variant validation, admitting the leaf would have turned these two into silent
        // passes, and the fixture would then have reached ProjectionCommands.contributionFor and
        // crashed the generator. The replacement message is the canary for that half of the fix.
        DEFERRED_NESTED_COMPOSITE_REFERENCE("a composite NodeId reference at nested depth surfaces its own per-variant deferral, not the blanket nested-depth one",
            new NestingField("Film", "details", null,
                new ReturnTypeRef.TableBoundReturnType("FilmDetails",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(compositeLanguageRef("FilmDetails"))),
            List.of("Field 'FilmDetails.languageRef': ColumnBackedReferenceField NodeIdEncodeKeys "
                + "(rooted-at-parent NodeId reference) not yet implemented — requires JOIN-with-projection emission")),

        DEFERRED_NESTED_COMPOSITE_INSIDE_NESTED_NESTING("composite NodeId reference inside a NestingField inside a NestingField → recursive walk surfaces it",
            new NestingField("Film", "details", null,
                new ReturnTypeRef.TableBoundReturnType("FilmDetails",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(new NestingField("FilmDetails", "meta", null,
                    new ReturnTypeRef.TableBoundReturnType("FilmMeta",
                        TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                        new FieldWrapper.Single(true)),
                    List.of(compositeLanguageRef("FilmMeta"))))),
            List.of("Field 'FilmMeta.languageRef': ColumnBackedReferenceField NodeIdEncodeKeys "
                + "(rooted-at-parent NodeId reference) not yet implemented — requires JOIN-with-projection emission")),

        PROJECTED_REFERENCE_AT_NESTED_DEPTH("a Direct-compaction @field + @reference scalar under a nesting type — no error",
            new NestingField("Film", "details", null,
                new ReturnTypeRef.TableBoundReturnType("FilmDetails",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(languageNameOn("FilmDetails"))),
            List.of()),

        COMPUTED_FIELD_AT_NESTED_DEPTH("an @externalField expression leaf under a nesting type — no error",
            new NestingField("Film", "details", null,
                new ReturnTypeRef.TableBoundReturnType("FilmDetails",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(isEnglishOn("FilmDetails"))),
            List.of()),

        // The spike watched this exact shape build green and emit the helper call with the join
        // path silently dropped, back when the nested walk skipped per-variant validation.
        COMPUTED_FIELD_WITH_LIFT_CONDITION_AT_NESTED_DEPTH("an @externalField carrying a @reference path under a nesting type → the lift-form deferral fires at nested depth",
            new NestingField("Film", "details", null,
                new ReturnTypeRef.TableBoundReturnType("FilmDetails",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(new ComputedField("FilmDetails", "isEnglish", null,
                    new ReturnTypeRef.ScalarReturnType("Boolean", new FieldWrapper.Single(true)),
                    List.of(TestFixtures.conditionJoin(
                        TestFixtures.staticServiceMethodRef("com.example.Conditions", "liftCondition",
                            ClassName.get("org.jooq", "Condition"), List.of()),
                        TestFixtures.filmTable(),
                        "")),
                    IS_ENGLISH, AliasOwner.shared()))),
            List.of("Field 'FilmDetails.isEnglish': @externalField with a @reference path "
                + "(condition-join lift form) is not yet supported")),

        // The nesting field's own checks used to run only at the top level, so a list-shaped
        // nesting type nested inside another one was unchecked.
        LIST_CARDINALITY_REJECTED_AT_DEPTH_TWO("list cardinality on a NestingField inside a NestingField → error",
            new NestingField("Film", "details", null,
                new ReturnTypeRef.TableBoundReturnType("FilmDetails",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(new NestingField("FilmDetails", "tags", null,
                    new ReturnTypeRef.TableBoundReturnType("Tag",
                        TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                        new FieldWrapper.List(false, false)),
                    List.of()))),
            List.of("Field 'FilmDetails.tags': list cardinality on a plain-object nesting field is not supported"));

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void nestingFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }

    // ===== Multi-parent compatibility (schema-level) =====

    /**
     * Build a {@link GraphitronSchema} with two {@code @table} parents each declaring a
     * {@code details: FilmDetails} nesting field. Mirrors the production grouping: fields()
     * keyed by parent coordinates, types() with both parent TableTypes.
     */
    private static GraphitronSchema twoParentSchema(List<ChildField> filmNested, List<ChildField> advertisementNested) {
        var types = new java.util.LinkedHashMap<String, GraphitronType>();
        types.put("Film", new TableType("Film", null, FILM_TABLE));
        types.put("Advertisement", new TableType("Advertisement", null, ADVERTISEMENT_TABLE));
        var fields = new java.util.LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Film", "details"),
            nestingField("Film", "details", filmNested, FILM_TABLE));
        fields.put(FieldCoordinates.coordinates("Advertisement", "details"),
            nestingField("Advertisement", "details", advertisementNested, ADVERTISEMENT_TABLE));
        return new GraphitronSchema(types, fields);
    }

    @Test
    void multiParentCompat_matchingShapes_noError() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of(titleOn("FilmDetails", "title", "java.lang.String")));
        assertThat(validate(schema)).extracting(ValidationError::message).isEmpty();
    }

    @Test
    void multiParentCompat_missingFieldOnSecondParent_reported() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of());
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'title' exists on the first but not the second");
    }

    @Test
    void multiParentCompat_extraFieldOnSecondParent_reported() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of(
                titleOn("FilmDetails", "title", "java.lang.String"),
                new ColumnBackedField("FilmDetails", "extra", null,
                    List.of(new ColumnRef("extra", "EXTRA", "java.lang.String")),
                    new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct())));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'extra' exists on the second but not the first");
    }

    @Test
    void multiParentCompat_differentColumnName_reported() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of(titleOn("FilmDetails", "headline", "java.lang.String")));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'title' resolves to column 'title' on the first but 'headline' on the second");
    }

    @Test
    void multiParentCompat_differentJavaType_reported() {
        var schema = twoParentSchema(
            List.of(titleOn("FilmDetails", "title", "java.lang.String")),
            List.of(titleOn("FilmDetails", "title", "java.lang.Integer")));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'title' has Java type 'java.lang.String' on the first but 'java.lang.Integer' on the second");
    }

    @Test
    void multiParentCompat_nestedNestingWithDivergentInnerColumn_reported() {
        // Two parents share a nested type FilmDetails whose `meta` is itself a NestingField.
        // The inner FilmMeta has one field `label` that resolves to different columns on each
        // parent. The shape check must recurse into the inner NestingField and flag the mismatch;
        // without recursion, class equality (NestingField == NestingField) hides the divergence.
        var filmMeta = new NestingField("FilmDetails", "meta", null,
            new ReturnTypeRef.TableBoundReturnType("FilmMeta", FILM_TABLE, new FieldWrapper.Single(true)),
            List.of(new ColumnBackedField("FilmMeta", "label", null,
                List.of(new ColumnRef("title", "TITLE", "java.lang.String")),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct())));
        var adMeta = new NestingField("FilmDetails", "meta", null,
            new ReturnTypeRef.TableBoundReturnType("FilmMeta", ADVERTISEMENT_TABLE, new FieldWrapper.Single(true)),
            List.of(new ColumnBackedField("FilmMeta", "label", null,
                List.of(new ColumnRef("headline", "HEADLINE", "java.lang.String")),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct())));
        var schema = twoParentSchema(List.of(filmMeta), List.of(adMeta));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .contains(
                "Nested type 'FilmMeta' shared across 'Film' and 'Advertisement': field 'label' resolves to column 'title' on the first but 'headline' on the second");
    }

    // ===== The alias-projected arm: the two leaves admitted across parents =====

    /**
     * A Direct-compaction {@code @field} + {@code @reference} leaf entering {@code target} through
     * the named FK. The FK name and anchor differ per parent (each anchor infers its own entry into
     * the shared path), which is exactly what the arm tolerates; {@code target} and
     * {@code columnClass} are what it compares.
     */
    private static ColumnBackedReferenceField sharedRefLeaf(String fk, TableRef anchor, String target,
                                                            String columnClass) {
        var path = List.<JoinStep>of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef(fk), anchor,
            List.of(TestFixtures.languageIdCol()), TestFixtures.joinTarget(target),
            List.of(TestFixtures.languageIdCol()), null, ""));
        return new ColumnBackedReferenceField("FilmDetails", "langName", null,
            List.of(new ColumnRef("name", "NAME", columnClass)),
            path,
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct(),
            TestFixtures.pcFor(path, anchor), AliasOwner.shared());
    }

    /** An {@code @externalField} expression leaf, with the helper's return type as the variable. */
    private static ComputedField sharedComputedLeaf(ParameterizedTypeName helperReturn, String scalar) {
        return new ComputedField("FilmDetails", "flag", null,
            new ReturnTypeRef.ScalarReturnType(scalar, new FieldWrapper.Single(true)),
            List.of(),
            TestFixtures.staticServiceMethodRef("com.example.FilmExtensions", "flag", helperReturn,
                List.of(new MethodRef.Param.Typed("table", "org.jooq.Table", new ParamSource.Table()))),
            AliasOwner.shared());
    }

    @Test
    void multiParentCompat_sharedProjectedLeaves_admitted() {
        // Both leaves the alias-projected arm admits, on one shared nested type. Each anchor mints
        // its own projection unit and reads the value back by result-key alias off the source
        // record, so the per-anchor join entry (a different FK name on each side of the reference
        // leaf) is not a conflict. No error at all: the arm admits and no per-variant check fires.
        var schema = twoParentSchema(
            List.of(sharedRefLeaf("film_language_id_fkey", FILM_TABLE, "language", "java.lang.String"),
                sharedComputedLeaf(BOOLEAN_FIELD, "Boolean")),
            List.of(sharedRefLeaf("advertisement_language_id_fkey", ADVERTISEMENT_TABLE, "language", "java.lang.String"),
                sharedComputedLeaf(BOOLEAN_FIELD, "Boolean")));
        assertThat(validate(schema)).extracting(ValidationError::message).isEmpty();
    }

    @Test
    void multiParentCompat_divergentDomainReturnType_reported() {
        // The read-side analogue of the ColumnBackedField arm's columnClass case, hand-built on the
        // reference leaf: its claim carries the terminal column's own type, so two anchors reading
        // differently-typed terminal columns disagree here. (The computed leaf cannot produce this:
        // everything it stores bar its per-variant-deferred joinPath comes off the single SDL
        // declaration on the shared nested type, so its two sides agree by construction.)
        var schema = twoParentSchema(
            List.of(sharedRefLeaf("film_language_id_fkey", FILM_TABLE, "language", "java.lang.String")),
            List.of(sharedRefLeaf("advertisement_language_id_fkey", ADVERTISEMENT_TABLE, "language", "java.lang.Integer")));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'langName' "
                    + "projects Java type 'Plain(java.lang.String)' on the first but "
                    + "'Plain(java.lang.Integer)' on the second, and one generated fetchers class "
                    + "carries one typed read per coordinate");
    }

    @Test
    void multiParentCompat_divergentReferenceTerminalTable_reported() {
        // The case domainReturnType() alone cannot see, and the reason the arm compares one fact
        // more. A {key: "..."} first step resolves from either endpoint of the named FK, so two
        // anchors sitting on opposite ends traverse it in opposite directions and read two
        // different tables' same-named, same-typed column. Both sides agree on Plain(String) here;
        // only the terminus differs.
        var schema = twoParentSchema(
            List.of(sharedRefLeaf("customer_address_id_fkey", FILM_TABLE, "address", "java.lang.String")),
            List.of(sharedRefLeaf("customer_address_id_fkey", ADVERTISEMENT_TABLE, "customer", "java.lang.String")));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .containsExactly(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'langName' "
                    + "ends its @reference path on table 'address' on the first but 'customer' on "
                    + "the second, and one generated fetchers class carries one read per coordinate");
    }

    @Test
    void multiParentCompat_mixedResultKeyAliasedMembership_deferredOnFkOrientation() {
        // A membership split: ColumnBackedField (reads a typed column constant) under one anchor,
        // ColumnBackedReferenceField (alias-projected) under the other. Deferred rather than
        // structural, because the divergence is a property of the two anchors' FK topology and not
        // of anything the author wrote.
        //
        // The pair is hand-built. The one route that reaches it on a real schema is an @nodeId
        // reference whose FK-mirror collapse lands on different sides per anchor, and there the
        // ColumnBackedReferenceField side carries a NodeIdEncodeKeys compaction that
        // validateColumnBackedReferenceField rejects on its own account — so no reachable schema
        // produces this message alone. That is why the wording stays on the FK-orientation fact.
        var schema = twoParentSchema(
            List.of(new ColumnBackedField("FilmDetails", "langName", null,
                List.of(new ColumnRef("name", "NAME", "java.lang.String")),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct())),
            List.of(sharedRefLeaf("advertisement_language_id_fkey", ADVERTISEMENT_TABLE, "language", "java.lang.String")));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .contains(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'langName' "
                    + "classifies as ColumnBackedField on the first but ColumnBackedReferenceField on "
                    + "the second, because the two parents enter the same node target from opposite "
                    + "ends of their foreign keys: one resolves to the parent's own key columns and "
                    + "the other needs a join, and one generated fetchers class carries one read");
    }

    @Test
    void multiParentCompat_tableFieldDivergentFilters_reported() {
        // The arm-ordering guard. TableField is a ResultKeyAliasedField too, so an alias-projected
        // arm placed above its arm would swallow it and silently stop comparing filters(). One
        // generated condition method serves every reuse site, so diverging filters at two sites is
        // a real conflict; this test is what makes the ordering a build failure rather than a
        // review note.
        var schema = twoParentSchema(
            List.of(sharedTableLeaf(List.of())),
            List.of(sharedTableLeaf(List.of(
                new no.sikt.graphitron.rewrite.model.ConditionFilter(
                    "com.example.Conditions", "addressCondition", List.of())))));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .contains(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'address' "
                    + "classifies different condition filters at the two reuse sites, and one "
                    + "generated condition method serves every site");
    }

    /** An inline {@code TableField} leaf with the filter list as the variable. */
    private static ChildField.TableField sharedTableLeaf(List<no.sikt.graphitron.rewrite.model.WhereFilter> filters) {
        return new ChildField.TableField("FilmDetails", "address", null,
            new ReturnTypeRef.TableBoundReturnType("Address",
                TestFixtures.joinTarget("address"), new FieldWrapper.Single(true)),
            List.of(), filters, new OrderBySpec.None(), null,
            no.sikt.graphitron.rewrite.model.LookupResolution.None.INSTANCE,
            /* parentCorrelation */ null, AliasOwner.shared());
    }

    @Test
    void multiParentCompat_nonColumnLeaf_rejectedAcrossParents() {
        // The live pin on the catch-all deferral. Its resident is the Table-sourced arm of
        // BatchedTableField: every sharing parent mints the identical <NestedType>Fetchers#rows
        // reference (the address is keyed on the nested type), while the batch grain comes off each
        // anchor's own correlation columns, so the two derivations are correct and different with
        // one artifact to carry them.
        var schema = twoParentSchema(
            List.of(sharedBatchedLeaf("film_actor_film_id_fkey")),
            List.of(sharedBatchedLeaf("advertisement_actor_advertisement_id_fkey")));
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .contains(
                "Nested type 'FilmDetails' shared across 'Film' and 'Advertisement': field 'actors' "
                    + "classifies as BatchedTableField which is not yet supported across multiple parents");
    }

    /** A Table-sourced {@code @splitQuery} leaf, the shape the catch-all still defers. */
    private static ChildField.BatchedTableField sharedBatchedLeaf(String fk) {
        var returnType = new ReturnTypeRef.TableBoundReturnType("Actor",
            TestFixtures.joinTarget("actor"), new FieldWrapper.Single(true));
        var path = List.<JoinStep>of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef(fk), null,
            List.of(TestFixtures.filmIdCol()), TestFixtures.joinTarget("film_actor"),
            List.of(TestFixtures.filmIdCol()), null, ""));
        return new ChildField.BatchedTableField("FilmDetails", "actors", null, returnType,
            path, List.of(), new OrderBySpec.None(), null,
            no.sikt.graphitron.rewrite.model.SourceShape.Table,
            TestFixtures.splitSourceKey(List.of(TestFixtures.filmIdCol())),
            TestFixtures.fkColumnsLift(),
            TestFixtures.loaderRegistration(returnType, false, false),
            no.sikt.graphitron.rewrite.model.LookupResolution.None.INSTANCE,
            TestFixtures.pcFor(path, TestFixtures.filmTable()));
    }
}
