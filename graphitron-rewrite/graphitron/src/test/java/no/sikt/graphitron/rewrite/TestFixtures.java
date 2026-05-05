package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ForeignKeyRef;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared factory methods for model objects used across test classes.
 * Reduces the 8-12 arg constructor calls that are duplicated across generator and validation tests.
 * When record components change, only these factories need updating.
 */
public final class TestFixtures {

    private TestFixtures() {}

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
            pkColumns);
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
     * <p>R81 lifted {@link no.sikt.graphitron.rewrite.model.JoinStep.FkJoin#fk()} to a non-null
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
     * Test-only constructor mirroring the pre-R82 {@code FkJoin(fk, originTable, sourceColumns,
     * targetTable, targetColumns, whereFilter, alias)} shape (post-R81 dropped the redundant
     * {@code fkName} component; the SQL constraint name is carried by {@code fk.sqlName()}),
     * zipping the two column lists into source-side/target-side slot pairs. Test fixtures
     * historically wrote source columns first (parent-holds-FK convention), so
     * {@code sourceColumns[i]} maps to {@code slot.sourceSide()} and {@code targetColumns[i]} to
     * {@code slot.targetSide()} — exactly the FK-on-source case the spec migration table covers,
     * kept mechanical for tests that don't care about the orientation specifically.
     */
    public static JoinStep.FkJoin fkJoin(ForeignKeyRef fk, TableRef originTable,
                                          List<ColumnRef> sourceColumns,
                                          TableRef targetTable, List<ColumnRef> targetColumns,
                                          MethodRef whereFilter, String alias) {
        if (sourceColumns.size() != targetColumns.size()) {
            throw new IllegalArgumentException(
                "fkJoin fixture: sourceColumns/targetColumns arity mismatch ("
                + sourceColumns.size() + " vs " + targetColumns.size() + ")");
        }
        List<JoinSlot.FkSlot> slots = new ArrayList<>(sourceColumns.size());
        for (int i = 0; i < sourceColumns.size(); i++) {
            slots.add(new JoinSlot.FkSlot(sourceColumns.get(i), targetColumns.get(i)));
        }
        return new JoinStep.FkJoin(fk, originTable, targetTable, slots, whereFilter, alias);
    }

    /**
     * Test-only constructor mirroring the pre-R82 {@code LiftedHop(targetTable, targetColumns,
     * alias)} shape, wrapping each {@link ColumnRef} as a {@link JoinSlot.LifterSlot} (the
     * single-column-per-slot permit that encodes "DataLoader key tuple IS target-column tuple"
     * structurally).
     */
    public static JoinStep.LiftedHop liftedHop(TableRef targetTable,
                                                List<ColumnRef> targetColumns, String alias) {
        List<JoinSlot.LifterSlot> slots = targetColumns.stream()
            .map(JoinSlot.LifterSlot::new)
            .toList();
        return new JoinStep.LiftedHop(targetTable, slots, alias);
    }
}
