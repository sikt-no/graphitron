package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.Arity;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ForeignKeyRef;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WalkerResult;
import no.sikt.graphitron.rewrite.walker.ServiceMethodCallWalker;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared factory methods for model objects used across test classes.
 * Reduces the 8-12 arg constructor calls that are duplicated across generator and validation tests.
 * When record components change, only these factories need updating.
 */
public final class TestFixtures {

    private TestFixtures() {}

    // ===== MethodRef factories =====

    /**
     * Builds a {@link MethodRef.StaticOnly} for the {@code @tableMethod} / {@code @externalField}
     * / condition-join populations. Mirrors the {@code tableRef(...)} factory pattern: tests
     * that don't care about declared exceptions or call shape stay on this concise overload.
     */
    public static MethodRef.StaticOnly staticOnlyMethodRef(String className, String methodName, TypeName returnType) {
        return new MethodRef.StaticOnly(className, methodName, returnType, List.of(), List.of());
    }

    public static MethodRef.StaticOnly staticOnlyMethodRef(String className, String methodName, TypeName returnType,
                                                            List<MethodRef.Param> params) {
        return new MethodRef.StaticOnly(className, methodName, returnType, params, List.of());
    }

    public static MethodRef.StaticOnly staticOnlyMethodRef(String className, String methodName, TypeName returnType,
                                                            List<MethodRef.Param> params, List<String> declaredExceptions) {
        return new MethodRef.StaticOnly(className, methodName, returnType, params, declaredExceptions);
    }

    /**
     * Builds a {@link MethodRef.Service} with the given {@link MethodRef.CallShape} explicit.
     * Tests that exercise the static/instance fork directly (e.g. {@code MethodRefCallShapeTest})
     * route through this overload.
     */
    public static MethodRef.Service serviceMethodRef(String className, String methodName, TypeName returnType,
                                                      List<MethodRef.Param> params, MethodRef.CallShape callShape) {
        return new MethodRef.Service(className, methodName, returnType, params, List.of(), callShape);
    }

    public static MethodRef.Service serviceMethodRef(String className, String methodName, TypeName returnType,
                                                      List<MethodRef.Param> params, List<String> declaredExceptions,
                                                      MethodRef.CallShape callShape) {
        return new MethodRef.Service(className, methodName, returnType, params, declaredExceptions, callShape);
    }

    /**
     * Convenience overload mirroring the former default-static {@code MethodRef.Basic} compat
     * constructor for {@code @service} fixtures: builds a {@link MethodRef.Service} with a
     * {@link MethodRef.CallShape.Static} arm whose {@code needsDslLocal} is derived from the
     * params (any param whose source is {@link ParamSource.DslContext} flips it on, matching
     * what {@code ServiceCatalog.reflectServiceMethod} does at classify time).
     */
    public static MethodRef.Service staticServiceMethodRef(String className, String methodName, TypeName returnType,
                                                            List<MethodRef.Param> params) {
        boolean needsDslLocal = params.stream().anyMatch(p -> p.source() instanceof ParamSource.DslContext);
        return new MethodRef.Service(className, methodName, returnType, params, List.of(),
            new MethodRef.CallShape.Static(needsDslLocal));
    }

    public static MethodRef.Service staticServiceMethodRef(String className, String methodName, TypeName returnType,
                                                            List<MethodRef.Param> params, List<String> declaredExceptions) {
        boolean needsDslLocal = params.stream().anyMatch(p -> p.source() instanceof ParamSource.DslContext);
        return new MethodRef.Service(className, methodName, returnType, params, declaredExceptions,
            new MethodRef.CallShape.Static(needsDslLocal));
    }

    /**
     * Convenience overload for instance-{@code @service} test fixtures: a {@link MethodRef.Service}
     * with a {@link MethodRef.CallShape.InstanceWithDslHolder} arm. Used by tests that exercise
     * the {@code new ServiceClass(dsl).method(...)} emit shape.
     */
    public static MethodRef.Service instanceServiceMethodRef(String className, String methodName, TypeName returnType,
                                                              List<MethodRef.Param> params) {
        return new MethodRef.Service(className, methodName, returnType, params, List.of(),
            new MethodRef.CallShape.InstanceWithDslHolder());
    }

    /**
 * Stub a {@link ServiceMethodCall} from a {@link MethodRef.Service} via the
     * production walker. Used by tests building the four root sync {@code @service} permits,
     * which now carry both legacy {@code method} and the new {@code serviceMethodCall} slots
     * during the additive cutover. When the walker rejects (multi-DSL invariant violation, etc.),
     * tests should construct the carrier manually instead.
     */
    public static ServiceMethodCall stubServiceCall(MethodRef.Service method) {
        var result = new ServiceMethodCallWalker().walk(null, method);
        if (result instanceof WalkerResult.Ok<ServiceMethodCall> ok) {
            return ok.carrier();
        }
        throw new IllegalStateException(
            "Test fixture's MethodRef.Service did not translate to a ServiceMethodCall — "
            + "construct the carrier manually for cases that exercise walker rejections.");
    }

    /**
     * Builds a {@link MethodRef.Param.Sourced} carrying the full per-axis triple. The convenience
     * overloads below cover the common shorthand cases (Wrap.Row + POSITIONAL_LIST; typed
     * TableRecord with POSITIONAL_LIST or MAPPED_SET).
     */
    public static MethodRef.Param.Sourced sourced(String name, SourceKey.Wrap wrap,
                                                   List<ColumnRef> columns,
                                                   LoaderRegistration.Container container) {
        return new MethodRef.Param.Sourced(name, wrap, columns, container);
    }

    /**
     * Shorthand for the common case: {@code List<RowN<...>>}-shaped SOURCES, projecting to
     * {@link SourceKey.Wrap.Row} + {@link LoaderRegistration.Container#POSITIONAL_LIST}.
     */
    public static MethodRef.Param.Sourced sourcedRow(String name, List<ColumnRef> columns) {
        return new MethodRef.Param.Sourced(name, new SourceKey.Wrap.Row(),
            columns, LoaderRegistration.Container.POSITIONAL_LIST);
    }

    /**
     * Shorthand for the {@code Set<RowN<...>>}-shaped SOURCES: {@link SourceKey.Wrap.Row} +
     * {@link LoaderRegistration.Container#MAPPED_SET}.
     */
    public static MethodRef.Param.Sourced sourcedRowMapped(String name, List<ColumnRef> columns) {
        return new MethodRef.Param.Sourced(name, new SourceKey.Wrap.Row(),
            columns, LoaderRegistration.Container.MAPPED_SET);
    }

    /**
     * Shorthand for the {@code List<RecordN<...>>}-shaped SOURCES: {@link SourceKey.Wrap.Record} +
     * {@link LoaderRegistration.Container#POSITIONAL_LIST}.
     */
    public static MethodRef.Param.Sourced sourcedRecord(String name, List<ColumnRef> columns) {
        return new MethodRef.Param.Sourced(name, new SourceKey.Wrap.Record(),
            columns, LoaderRegistration.Container.POSITIONAL_LIST);
    }

    /**
     * Shorthand for the {@code Set<RecordN<...>>}-shaped SOURCES: {@link SourceKey.Wrap.Record} +
     * {@link LoaderRegistration.Container#MAPPED_SET}.
     */
    public static MethodRef.Param.Sourced sourcedRecordMapped(String name, List<ColumnRef> columns) {
        return new MethodRef.Param.Sourced(name, new SourceKey.Wrap.Record(),
            columns, LoaderRegistration.Container.MAPPED_SET);
    }

    /**
     * Shorthand for the {@code List<X extends TableRecord>}-shaped SOURCES: typed
     * {@link SourceKey.Wrap.TableRecord} carrying {@code recordClass} + POSITIONAL_LIST.
     */
    public static MethodRef.Param.Sourced sourcedTableRecord(String name,
                                                              List<ColumnRef> columns,
                                                              Class<? extends org.jooq.TableRecord<?>> recordClass) {
        return new MethodRef.Param.Sourced(name,
            new SourceKey.Wrap.TableRecord(ClassName.get(recordClass)),
            columns, LoaderRegistration.Container.POSITIONAL_LIST);
    }

    /**
     * Shorthand for the {@code Set<X extends TableRecord>}-shaped SOURCES: typed
     * {@link SourceKey.Wrap.TableRecord} carrying {@code recordClass} + MAPPED_SET.
     */
    public static MethodRef.Param.Sourced sourcedTableRecordMapped(String name,
                                                                    List<ColumnRef> columns,
                                                                    Class<? extends org.jooq.TableRecord<?>> recordClass) {
        return new MethodRef.Param.Sourced(name,
            new SourceKey.Wrap.TableRecord(ClassName.get(recordClass)),
            columns, LoaderRegistration.Container.MAPPED_SET);
    }

    // ===== SourceKey / LoaderRegistration test fixtures =====
    //
    // The projections below replace the deleted SourceKeyResolver / LoaderRegistrationResolver
    // helpers, which were inlined into the field-classifier producers. Test fixtures use
    // these to compose the same per-axis shapes the producers build.

    /**
     * Split-query parent-side {@link SourceKey}: {@link SourceKey.Wrap.Row} over the FK columns.
     * Mirrors the FK-derived projection in {@code FieldBuilder.deriveSplitQuerySource}. (Arity
     * left the key: the split fetcher's cardinality is the field's wrapper position.)
     */
    public static SourceKey splitSourceKey(List<ColumnRef> fkColumns) {
        return new SourceKey(fkColumns, new SourceKey.Wrap.Row());
    }

    /**
     * Catalog-FK record-parent {@link SourceKey} + {@link KeyLift.FkColumns} pairing helpers.
     * Mirrors the FK arm of {@code FieldBuilder.deriveFkRecordParentSource}: the residue key's
     * wrap is the lift arm's derivation.
     */
    public static SourceKey recordParentRowSourceKey(List<ColumnRef> fkColumns) {
        return new SourceKey(fkColumns, fkColumnsLift().wrap());
    }

    /** The catalog-FK / polymorphic-Row lift arm (per-column reads off the parent). */
    public static KeyLift fkColumnsLift() {
        return new KeyLift.FkColumns();
    }

    /**
     * Polymorphic-Row {@link SourceKey} for an {@link ChildField.InterfaceField} /
     * {@link ChildField.UnionField} on a table-backed parent: the parent IS the
     * source; pairs with {@link #fkColumnsLift()} as the leaf's {@code parentKeyLift}.
     */
    public static SourceKey polymorphicRowParentSourceKey(List<ColumnRef> pkColumns) {
        return new SourceKey(pkColumns, fkColumnsLift().wrap());
    }

    /**
     * Polymorphic-Accessor lift for {@link ChildField.InterfaceField} /
     * {@link ChildField.UnionField} on a record-backed parent with a typed hub
     * accessor (Single ⇒ {@link Arity#ONE}, Many ⇒ {@link Arity#MANY}). Pair with
     * {@link #polymorphicAccessorParentSourceKey}; the hub table itself is carried as the
     * leaf's {@code parentKeyOwnerTable} (no hop, no path).
     */
    public static KeyLift.Accessor polymorphicAccessorParentLift(
            no.sikt.graphitron.rewrite.model.AccessorRef accessor,
            boolean isMany) {
        return new KeyLift.Accessor(accessor, isMany ? Arity.MANY : Arity.ONE);
    }

    /**
     * Polymorphic-Accessor {@link SourceKey}: columns=hubTable.PK, wrap from the accessor lift
     * arm's derivation ({@code RecordN}).
     */
    public static SourceKey polymorphicAccessorParentSourceKey(
            no.sikt.graphitron.rewrite.model.TableRef hubTable,
            no.sikt.graphitron.rewrite.model.AccessorRef accessor,
            boolean isMany) {
        return new SourceKey(hubTable.primaryKeyColumns(),
            polymorphicAccessorParentLift(accessor, isMany).wrap());
    }

    /**
     * Service-backed {@link SourceKey}: the {@code (columns, wrap)} pair read off the
     * {@code @service} method's {@code Sources} signature fact. Mirrors
     * {@code FieldBuilder.buildServiceSourceKey}. (The service reader arms died into the
     * {@link MethodRef} signature; no lift, since service fields never reach the record-parent key
     * extraction.)
     */
    public static SourceKey serviceSourceKey(SourceKey.Wrap wrap,
                                              List<ColumnRef> parentKeyColumns) {
        return new SourceKey(parentKeyColumns, wrap);
    }

    /**
     * {@link LoaderRegistration} for a DataLoader-backed field: container is mapped or positional
     * per {@code mapped}; dispatch is {@code LOAD_MANY} per {@code loadMany} (only the
     * accessor-many arm sets this to true today); {@code valueIsList} follows
     * {@code rt.wrapper().isList()} but flips to false for the loadMany arm (loadMany emits one
     * record per element-PK).
     */
    public static LoaderRegistration loaderRegistration(ReturnTypeRef rt, boolean mapped, boolean loadMany) {
        boolean valueIsList = rt.wrapper().isList() && !loadMany;
        return new LoaderRegistration(
            valueIsList,
            mapped ? LoaderRegistration.Container.MAPPED_SET : LoaderRegistration.Container.POSITIONAL_LIST,
            loadMany ? LoaderRegistration.Dispatch.LOAD_MANY : LoaderRegistration.Dispatch.LOAD_ONE);
    }

    public static MethodRef.Service instanceServiceMethodRef(String className, String methodName, TypeName returnType,
                                                              List<MethodRef.Param> params, List<String> declaredExceptions) {
        return new MethodRef.Service(className, methodName, returnType, params, declaredExceptions,
            new MethodRef.CallShape.InstanceWithDslHolder());
    }


    // ===== TableRef =====

    /**
     * Stable synthetic root for test-only TableRef typed-class fields. Matches
     * {@code TestConfiguration.DEFAULT_JOOQ_PACKAGE} so synthetic helpers built here line up
     * with the FQNs tests already assert against (e.g. {@code <root>.tables.Film},
     * {@code <root>.tables.records.FilmRecord}, {@code <root>.Tables}).
     */
    private static final String TEST_JOOQ_ROOT = "no.sikt.graphitron.rewrite.test.jooq";

    /**
     * Builds a {@link TableRef} with the typed-class fields synthesised from {@code TEST_JOOQ_ROOT}
     * + {@code simpleClassName} (e.g. {@code Film}). Use this for any test that needs a TableRef
     * rooted in a single notional schema; cross-schema scenarios should use the real
     * multischemafixture catalog via {@link JooqCatalog} instead.
     */
    public static TableRef tableRef(String sqlName, String javaFieldName, String simpleClassName,
                                    List<ColumnRef> pkColumns) {
        return new TableRef(
            sqlName,
            javaFieldName,
            ClassName.get(TEST_JOOQ_ROOT + ".tables", simpleClassName),
            ClassName.get(TEST_JOOQ_ROOT + ".tables.records", simpleClassName + "Record"),
            ClassName.get(TEST_JOOQ_ROOT, "Tables"),
            pkColumns,
            // allColumns: fixtures that build a TableRef by hand don't enumerate the whole row; the
            // reserved-alias full-row emit / TableRecord key reconstruction that read this
            // are exercised through the real-catalog pipeline tests, not these hand-built refs.
            List.of());
    }

    public static TableRef filmTable() {
        return tableRef("film", "FILM", "Film", List.of());
    }

    public static TableRef filmTable(List<ColumnRef> pkColumns) {
        return tableRef("film", "FILM", "Film", pkColumns);
    }

    public static TableRef filmTableWithPk() {
        return filmTable(List.of(filmIdCol()));
    }

    public static TableRef languageTable() {
        return tableRef("language", "LANGUAGE", "Language", List.of());
    }

    public static TableRef languageTableWithPk() {
        return tableRef("language", "LANGUAGE", "Language", List.of(languageIdCol()));
    }

    public static TableRef actorTable() {
        return tableRef("actor", "ACTOR", "Actor", List.of());
    }

    public static TableRef categoryTable() {
        return tableRef("category", "CATEGORY", "Category", List.of());
    }

    /**
     * Stub TableRef for join targets where the typed-class fields are not under test. Synthesises
     * the typed fields from the SQL name (e.g. sqlName "language" → simpleClass "Language"); tests
     * that assert specific FQNs should use {@link #tableRef} with explicit values instead.
     */
    public static TableRef joinTarget(String sqlName) {
        String simpleClass = simpleNameFromSqlName(sqlName);
        return tableRef(sqlName, sqlName.toUpperCase(), simpleClass, List.of());
    }

    private static String simpleNameFromSqlName(String sqlName) {
        // snake_case → PascalCase, e.g. "child_ref" → "ChildRef"
        var parts = sqlName.split("_");
        var sb = new StringBuilder();
        for (var p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    // ===== ForeignKeyRef =====

    /**
     * Builds a synthetic {@link ForeignKeyRef} rooted in {@link #TEST_JOOQ_ROOT}. The constant
     * name is derived by upper-casing the SQL constraint name (matching jOOQ's stock
     * {@code Keys.<TABLE>__<FK>} convention closely enough for fixture purposes); tests that need
     * exact constant names should pass them explicitly via the three-arg overload.
     *
     * <p>The FK-hop provenance ({@link no.sikt.graphitron.rewrite.model.On.ColumnPairs#fk()}) is a non-null
     * {@link ForeignKeyRef}. Fixtures that previously passed {@code null} should route through
     * this factory so the synthetic FK reference is type-correct end to end.
     */
    public static ForeignKeyRef foreignKeyRef(String sqlName) {
        return foreignKeyRef(sqlName, sqlName.toUpperCase(),
            ClassName.get(TEST_JOOQ_ROOT, "Keys"));
    }

    public static ForeignKeyRef foreignKeyRef(String sqlName, String constantName, ClassName keysClass) {
        return new ForeignKeyRef(sqlName, keysClass, constantName);
    }

    // ===== ColumnRef =====

    public static ColumnRef filmIdCol() {
        return new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    }

    public static ColumnRef languageIdCol() {
        return new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer");
    }

    public static ColumnRef titleCol() {
        return new ColumnRef("title", "TITLE", "java.lang.String");
    }

    public static ColumnRef col(String sqlName, String javaName, String javaType) {
        return new ColumnRef(sqlName, javaName, javaType);
    }

    // ===== ReturnTypeRef =====

    public static ReturnTypeRef.TableBoundReturnType tableBoundFilm(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", filmTable(), wrapper);
    }

    public static ReturnTypeRef.TableBoundReturnType tableBound(String typeName, TableRef table, FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType(typeName, table, wrapper);
    }

    // ===== FieldWrapper shortcuts =====

    public static FieldWrapper.Single single() {
        return new FieldWrapper.Single(true);
    }

    public static FieldWrapper.List listWrapper() {
        return new FieldWrapper.List(true, true);
    }

    public static FieldWrapper.List nonNullList() {
        return new FieldWrapper.List(false, false);
    }

    // ===== ChildField shortcuts =====

    public static ChildField.ColumnField columnField(String parentType, String name, String columnName,
                                                      String javaName, String columnClass) {
        return new ChildField.ColumnField(parentType, name, null, columnName,
            new ColumnRef(columnName, javaName, columnClass),
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct());
    }

    /**
     * Builds an arity-1 NodeId-encoded {@link ChildField.ColumnField} with a synthetic
     * {@code encode<TypeName>} {@link no.sikt.graphitron.rewrite.model.HelperRef.Encode} carrier.
     * Tests that need the full classifier shape should run through {@code GraphitronSchemaBuilder}.
     */
    public static ChildField.ColumnField nodeIdField(String parentType, String name, String nodeTypeName,
                                                     ColumnRef keyColumn) {
        var encoderClass = no.sikt.graphitron.javapoet.ClassName.get("test.util", "NodeIdEncoder");
        var enc = new no.sikt.graphitron.rewrite.model.HelperRef.Encode(
            encoderClass, "encode" + nodeTypeName, java.util.List.of(keyColumn));
        return new ChildField.ColumnField(parentType, name, null, keyColumn.sqlName(),
            keyColumn,
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys(enc));
    }

    /**
     * Builds an arity-{@code keyColumns.size()} NodeId-encoded {@link ChildField.CompositeColumnField}
     * with a synthetic {@code encode<TypeName>} carrier.
     */
    public static ChildField.CompositeColumnField compositeNodeIdField(String parentType, String name,
                                                                       String nodeTypeName,
                                                                       List<ColumnRef> keyColumns) {
        var encoderClass = no.sikt.graphitron.javapoet.ClassName.get("test.util", "NodeIdEncoder");
        var enc = new no.sikt.graphitron.rewrite.model.HelperRef.Encode(
            encoderClass, "encode" + nodeTypeName, keyColumns);
        return new ChildField.CompositeColumnField(parentType, name, null, keyColumns,
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys(enc));
    }

    // ===== JoinStep test fixtures =====

    /**
     * Test-only constructor mirroring the earlier {@code FkJoin(fk, originTable, sourceColumns,
     * targetTable, targetColumns, whereFilter, alias)} shape (the redundant
     * {@code fkName} component was dropped; the SQL constraint name is carried by {@code fk.sqlName()}),
     * zipping the two column lists into source-side/target-side slot pairs. Test fixtures
     * historically wrote source columns first (parent-holds-FK convention), so
     * {@code sourceColumns[i]} maps to {@code slot.sourceSide()} and {@code targetColumns[i]} to
     * {@code slot.targetSide()} — exactly the FK-on-source case the spec migration table covers,
     * kept mechanical for tests that don't care about the orientation specifically.
     */
    public static JoinStep.Hop fkJoin(ForeignKeyRef fk, TableRef originTable,
                                       List<ColumnRef> sourceColumns,
                                       TableRef targetTable, List<ColumnRef> targetColumns,
                                       JoinConditionRef whereFilter, String alias) {
        if (sourceColumns.size() != targetColumns.size()) {
            throw new IllegalArgumentException(
                "fkJoin fixture: sourceColumns/targetColumns arity mismatch ("
                + sourceColumns.size() + " vs " + targetColumns.size() + ")");
        }
        List<JoinSlot.FkSlot> slots = new ArrayList<>(sourceColumns.size());
        for (int i = 0; i < sourceColumns.size(); i++) {
            slots.add(new JoinSlot.FkSlot(sourceColumns.get(i), targetColumns.get(i)));
        }
        return new JoinStep.Hop(new TableExpr.Catalog(targetTable),
            new On.ColumnPairs(new On.Keying.ForeignKey(fk), slots), originTable, whereFilter, alias);
    }

    /**
 * Builds the resolved single-hop FK correlation carrier
     * ({@link no.sikt.graphitron.rewrite.model.ParticipantCorrelation.KeyTupleWhere}) a multi-table
     * polymorphic interface/union child field holds per participant, from parent-side /
     * participant-side column lists ({@code parentColumns[i]} → {@code slot.sourceSide()},
     * {@code participantColumns[i]} → {@code slot.targetSide()}). Same orientation convention as
     * {@link #fkJoin}. The {@link On.Keying} is a {@code NameMatchedKey} placeholder: the key-tuple
     * WHERE emit reads only the slots, never the keying provenance.
     */
    public static no.sikt.graphitron.rewrite.model.ParticipantCorrelation participantFkPath(
            List<ColumnRef> parentColumns, List<ColumnRef> participantColumns) {
        if (parentColumns.size() != participantColumns.size()) {
            throw new IllegalArgumentException(
                "participantFkPath fixture: parent/participant column arity mismatch ("
                + parentColumns.size() + " vs " + participantColumns.size() + ")");
        }
        List<JoinSlot.FkSlot> slots = new ArrayList<>(parentColumns.size());
        for (int i = 0; i < parentColumns.size(); i++) {
            slots.add(new JoinSlot.FkSlot(parentColumns.get(i), participantColumns.get(i)));
        }
        return new no.sikt.graphitron.rewrite.model.ParticipantCorrelation.KeyTupleWhere(
            new On.ColumnPairs(new On.Keying.NameMatchedKey("fixture_key"), slots));
    }

    /** True when the step is a {@link JoinStep.Hop} joining on FK-derived column pairs. */
    public static boolean isFkHop(JoinStep step) {
        return step instanceof JoinStep.Hop h && h.on() instanceof On.ColumnPairs;
    }

    /** True when the step is a {@link JoinStep.Hop} joining on a condition method. */
    public static boolean isConditionHop(JoinStep step) {
        return step instanceof JoinStep.Hop h && h.on() instanceof On.Predicate;
    }

    /**
     * Narrows a step to an FK-derived {@link JoinStep.Hop}, failing the test loudly when the
     * step is not one. Assertion-side counterpart of the old {@code (JoinStep.FkJoin)} cast.
     */
    public static JoinStep.Hop fkHop(JoinStep step) {
        if (!isFkHop(step)) {
            throw new AssertionError("expected an FK-derived Hop (On.ColumnPairs); got " + step);
        }
        return (JoinStep.Hop) step;
    }

    /** The FK-derived column pairs of a step; fails loudly when the step is not an FK hop. */
    public static On.ColumnPairs fkPairs(JoinStep step) {
        return (On.ColumnPairs) fkHop(step).on();
    }

    /**
     * The {@link ForeignKeyRef} behind a {@link On.ColumnPairs}' keying; fails the test loudly
     * when the pairs derive from the name-matched key instead of a catalog FK. Assertion-side
     * narrowing for the {@link On.Keying} seal.
     */
    public static ForeignKeyRef fkRef(On.ColumnPairs pairs) {
        if (!(pairs.keying() instanceof On.Keying.ForeignKey k)) {
            throw new AssertionError("expected FK-derived keying (On.Keying.ForeignKey); got "
                + pairs.keying());
        }
        return k.fk();
    }

    /**
     * Narrows a step to a condition-join {@link JoinStep.Hop}, failing the test loudly
     * otherwise. Assertion-side counterpart of the old {@code (JoinStep.ConditionJoin)} cast.
     */
    public static JoinStep.Hop conditionHop(JoinStep step) {
        if (!isConditionHop(step)) {
            throw new AssertionError("expected a condition-join Hop (On.Predicate); got " + step);
        }
        return (JoinStep.Hop) step;
    }

    /** The join-condition method of a condition-join step. */
    public static JoinConditionRef hopCondition(JoinStep step) {
        return ((On.Predicate) conditionHop(step).on()).condition();
    }

    /**
     * Builds a condition-join {@link JoinStep.Hop} (an {@link On.Predicate} join with no origin
     * table and no per-hop filter), mirroring what {@code BuildContext.parsePathElement}
     * produces for a {@code {condition:}}-only path element.
     */
    public static JoinStep.Hop conditionJoin(MethodRef condition, TableRef targetTable, String alias) {
        return new JoinStep.Hop(new TableExpr.Catalog(targetTable),
            new On.Predicate(new JoinConditionRef(condition)), null, null, alias);
    }

    /**
     * The hop-less pre-keyed correlation (the former test-only {@code liftedHop(...)}
     * fixture): source and target sides are the same column tuple.
     */
    public static ParentCorrelation.OnLiftedSlots liftedSlots(TableRef targetTable,
                                                               List<ColumnRef> columns) {
        return new ParentCorrelation.OnLiftedSlots(targetTable, columns);
    }

    /**
     * Synthesises a {@link ParentCorrelation} mirroring
     * {@code BuildContext.buildParentCorrelation}: the parent-anchor arm
     * {@link ParentCorrelation.OnParentJoin} when the first hop joins on a condition method
 * <em>or</em> carries a hop-0 {@code filter()}, {@link ParentCorrelation.OnFkSlots}
     * for a filter-less FK head, and {@code null} when the joinPath is empty
     * (standalone-lookup shape; the pre-keyed lifted shape constructs
     * {@link ParentCorrelation.OnLiftedSlots} directly). Test fixtures use this to satisfy
     * the ChildField compact-constructor invariant without threading the resolver state through
     * every test case.
     */
    public static ParentCorrelation pcFor(List<JoinStep> joinPath, TableRef parentTable) {
        if (joinPath.isEmpty()) {
            return null;
        }
        JoinStep.Hop hop = (JoinStep.Hop) joinPath.get(0);
        if (hop.on() instanceof On.Predicate || hop.filter() != null) {
            return new ParentCorrelation.OnParentJoin(hop, parentTable);
        }
        return new ParentCorrelation.OnFkSlots(hop);
    }
}
