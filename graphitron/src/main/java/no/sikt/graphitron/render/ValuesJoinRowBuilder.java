package no.sikt.graphitron.render;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Shared row-construction core for the emitters that produce
 * {@code VALUES (idx, c1, …) JOIN <table> … ORDER BY idx} SELECTs:
 * {@link LookupRows} (root and inline-child
 * lookup paths), {@code SelectMethodBody} (federated {@code _entities} and
 * {@code Query.node} / {@code Query.nodes} dispatch), and the parent-input
 * {@code VALUES} cells of the DataLoader rows methods
 * ({@link no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter} and
 * {@link no.sikt.graphitron.rewrite.generators.MultiTablePolymorphicEmitter}'s batched arms).
 * All routes go through {@link #cellsCode}, so every VALUES cell in the generator binds as
 * {@code DSL.val(value, col.getDataType())} through the column's registered Converter.
 *
 * <p>What that renders is the binding's decision, not this builder's. For the key and lookup
 * columns these routes actually serve it is a plain JDBC bind with no SQL {@code CAST}, which is
 * the property the emitted VALUES table's readability rests on. It is not a general property of
 * {@code DSL.val(value, dataType)}: a Postgres-enum column's binding renders
 * {@code cast(? as "<schema>"."<enum_type>")}, deliberately, because that is the operand the
 * database accepts (see {@link DiscriminatedTableFragments#discriminatorValue}). Should an enum
 * column ever become reachable as a VALUES cell, the cast is correct there too; only the "plain
 * bind" reading of the emitted SQL would need revisiting.
 *
 * <p>Each method takes the caller's slot list as {@code List<S>} plus a
 * {@code Function<S, ColumnRef>} projection, so callers keep their own slot representation
 * without a parallel-list bridge. The per-row fill loop, the {@code idx} cell expression, extra
 * projections, the join call itself, and the {@code .where(…)} / {@code .orderBy(…)} chain stay
 * caller-local; the join's predicate does not, since every caller reads the derived table's
 * labels back through {@link #onCondition}.
 */
public final class ValuesJoinRowBuilder {

    /**
     * jOOQ exposes typed {@code Row1..Row22} / {@code Record1..Record22}. Higher arities fall
     * back to the raw {@code RowN} / {@code Record} forms which lose typed cell access; the
     * {@code idx} cell occupies one slot, so the practical column count limit is 21.
     */
    static final int MAX_ARITY = 22;

    private ValuesJoinRowBuilder() {}

    /** Returns {@code Row<N+1>} for a row of {@code slotCount + 1} cells (idx + per-slot). */
    public static ClassName rowClass(int slotCount) {
        return ClassName.get("org.jooq", "Row" + (slotCount + 1));
    }

    /** Returns {@code Record<N+1>} for the matching row arity. */
    public static ClassName recordClass(int slotCount) {
        return ClassName.get("org.jooq", "Record" + (slotCount + 1));
    }

    /**
     * Returns the {@code Row<N+1>} / {@code Record<N+1>} type-args: {@code Integer} for the
     * {@code idx} cell, then one per slot resolved via {@code column.apply(slot).columnClass()}.
     * Throws {@link IllegalStateException} when arity exceeds {@link #MAX_ARITY} or when
     * {@code slots} is empty; the message includes {@code directiveContext} (e.g.
     * {@code "@lookupKey"}, {@code "@key"}) so authored-schema errors trace back to the
     * originating directive.
     */
    public static <S> TypeName[] rowTypeArgs(List<S> slots, Function<S, ColumnRef> column, String directiveContext) {
        if (slots.isEmpty()) {
            throw new IllegalStateException(
                directiveContext + " has no key columns; the row builder requires at least one slot");
        }
        int arity = slots.size() + 1;
        if (arity > MAX_ARITY) {
            throw new IllegalStateException(
                directiveContext + " arity " + slots.size() + " + idx exceeds jOOQ's typed "
                + "Row/Record limit of " + MAX_ARITY + " (compound keys with >" + (MAX_ARITY - 1)
                + " fields are not supported)");
        }
        TypeName[] typeArgs = new TypeName[arity];
        typeArgs[0] = ClassName.get(Integer.class);
        for (int i = 0; i < slots.size(); i++) {
            typeArgs[i + 1] = CatalogRefs.columnType(column.apply(slots.get(i)));
        }
        return typeArgs;
    }

    /**
     * Emits the typed-row array declaration (with the {@code @SuppressWarnings} annotation that
     * generic array creation requires). Equivalent to:
     * <pre>{@code
     * @SuppressWarnings({"unchecked", "rawtypes"})
     * Row<N+1><Integer, c1, …>[] <rowsLocal> = (Row<N+1><Integer, c1, …>[]) new Row<N+1>[<sizeExpr>];
     * }</pre>
     * Caller writes {@code sizeExpr} as raw javapoet text (e.g. {@code "n"}, {@code "bindings.size()"}).
     */
    public static <S> void emitRowArrayDecl(CodeBlock.Builder b, List<S> slots, Function<S, ColumnRef> column,
                                            String directiveContext, String rowsLocal, String sizeExpr) {
        TypeName[] typeArgs = rowTypeArgs(slots, column, directiveContext);
        ClassName raw = rowClass(slots.size());
        TypeName parameterised = ParameterizedTypeName.get(raw, typeArgs);
        b.add("@$T({$S, $S})\n", SuppressWarnings.class, "unchecked", "rawtypes");
        b.addStatement("$T[] $L = ($T[]) new $T[$L]",
            parameterised, rowsLocal, parameterised, raw, sizeExpr);
    }

    /**
     * Returns the comma-separated cells for one {@code DSL.row(…)} expression: the caller's
     * {@code idxCellExpr} followed by one {@code DSL.val(<value>, <tableLocal>.<COL>.getDataType())}
     * per slot. {@code valueExpr} is invoked once per slot with the slot and its zero-based index;
     * the returned {@link CodeBlock} is the raw value expression (jOOQ converts via the column's
     * registered Converter at bind time).
     *
     * <p>Wrap the result in {@code DSL.row(…)} at the call site (typically as
     * {@code <rowsLocal>[<idxLocal>] = DSL.row($L);}).
     */
    public static <S> CodeBlock cellsCode(List<S> slots, Function<S, ColumnRef> column,
                                          CodeBlock idxCellExpr, String tableLocal,
                                          BiFunction<S, Integer, CodeBlock> valueExpr) {
        return cellsCode(slots, column, idxCellExpr, CodeBlock.of("$L", tableLocal), valueExpr);
    }

    /**
     * Variant of {@link #cellsCode(List, Function, CodeBlock, String, BiFunction)} whose table
     * reference is an arbitrary {@link CodeBlock} expression rather than a Java-local name,
     * typically a constants-class reference like {@code Tables.FILM} for callers that have no
     * aliased table local in scope. Cell shape is identical.
     */
    public static <S> CodeBlock cellsCode(List<S> slots, Function<S, ColumnRef> column,
                                          CodeBlock idxCellExpr, CodeBlock tableExpr,
                                          BiFunction<S, Integer, CodeBlock> valueExpr) {
        var cells = CodeBlock.builder().add("$L", idxCellExpr);
        ClassName dsl = ClassName.get("org.jooq.impl", "DSL");
        for (int i = 0; i < slots.size(); i++) {
            S slot = slots.get(i);
            cells.add(", $T.val($L, $L.$L.getDataType())",
                dsl, valueExpr.apply(slot, i), tableExpr, column.apply(slot).javaName());
        }
        return cells.build();
    }

    /**
     * Returns the alias-args list for {@code DSL.values(rows).as(alias, "idx", "<sqlName1>", …)}.
     * Column labels are SQL names (case-sensitive in PostgreSQL with quoted identifiers); using
     * Java field names here would render mismatched {@link #onCondition} predicates downstream.
     */
    public static <S> CodeBlock aliasArgs(List<S> slots, Function<S, ColumnRef> column, String alias) {
        var b = CodeBlock.builder().add("$S, $S", alias, "idx");
        for (var slot : slots) {
            b.add(", $S", column.apply(slot).sqlName());
        }
        return b.build();
    }

    /**
     * Returns the join predicate between a table and the {@code VALUES} derived table whose
     * labels {@link #aliasArgs} wrote over the same slots:
     * {@code <tableLocal>.<COL>.eq(<inputLocal>.field("<sqlName>", <tableLocal>.<COL>.getDataType()))}
     * per slot, chained with {@code .and(...)} in slot order. Use as
     * {@code .join(<inputLocal>).on(<onCondition>)}. Each equality is minted by
     * {@link ColumnComparison#equalityAgainstField}; the operand is typed by the receiving column
     * itself, so it never coerces.
     *
     * <p><b>{@code ON}, never {@code USING}.</b> A {@code USING} column must be a plain column
     * name, so a key column whose jOOQ field renders as an expression (a client-side computed
     * column) is invalid SQL there. Oracle also forbids qualifying a {@code USING} column
     * anywhere in the statement, and the type's {@code $project} qualifies every column it
     * selects. And a join path through a junction table can carry a second column with the key's
     * name, which makes an unqualified {@code USING} column ambiguous. An explicit predicate keeps
     * the column on the table side, where it renders like any other.
     *
     * <p>The input column is read back by the SQL name {@link #aliasArgs} labelled it with, not
     * by position, so the writer and the reader of each label sit in this class and nothing
     * depends on the slot order matching the cell order. It is typed by
     * {@code getDataType()}, the {@code DataType} {@link #cellsCode} bound the cell at, so a
     * converter-backed column's type stays faithful on both sides.
     */
    public static <S> CodeBlock onCondition(List<S> slots, Function<S, ColumnRef> column,
                                            String tableLocal, String inputLocal) {
        return onCondition(slots, column, CodeBlock.of("$L", tableLocal), inputLocal);
    }

    /**
     * Variant of {@link #onCondition(List, Function, String, String)} whose table reference is an
     * arbitrary {@link CodeBlock} expression, the counterpart of
     * {@link #cellsCode(List, Function, CodeBlock, CodeBlock, BiFunction)}. Predicate shape is
     * identical.
     */
    public static <S> CodeBlock onCondition(List<S> slots, Function<S, ColumnRef> column,
                                            CodeBlock tableExpr, String inputLocal) {
        var on = CodeBlock.builder();
        for (int i = 0; i < slots.size(); i++) {
            ColumnRef col = column.apply(slots.get(i));
            var operand = CodeBlock.of("$L.field($S, $L.$L.getDataType())",
                inputLocal, col.sqlName(), tableExpr, col.javaName());
            var eq = ColumnComparison.equalityAgainstField(tableExpr, col, col, operand);
            on.add(i == 0 ? "$L" : ".and($L)", eq);
        }
        return on.build();
    }

    /** Convenience: array type {@code Row<N+1>[]} for a {@code returns} declaration on a helper method. */
    public static <S> TypeName rowArrayType(List<S> slots, Function<S, ColumnRef> column, String directiveContext) {
        TypeName[] typeArgs = rowTypeArgs(slots, column, directiveContext);
        return ArrayTypeName.of(ParameterizedTypeName.get(rowClass(slots.size()), typeArgs));
    }

    /** Convenience: parameterised {@code Table<Record<N+1><Integer, c1, …>>} for the values-derived-table local. */
    public static <S> TypeName inputTableType(List<S> slots, Function<S, ColumnRef> column, String directiveContext) {
        TypeName[] typeArgs = rowTypeArgs(slots, column, directiveContext);
        ClassName table = ClassName.get("org.jooq", "Table");
        return ParameterizedTypeName.get(table, ParameterizedTypeName.get(recordClass(slots.size()), typeArgs));
    }
}
