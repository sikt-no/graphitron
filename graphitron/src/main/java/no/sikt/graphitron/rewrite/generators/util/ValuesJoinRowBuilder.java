package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Shared row-construction core for the two emitters that produce
 * {@code VALUES (idx, c1, …) JOIN <table> … ORDER BY idx} SELECTs:
 * {@link no.sikt.graphitron.rewrite.generators.LookupValuesJoinEmitter} (root and inline-child
 * lookup paths) and {@link SelectMethodBody} (federated {@code _entities} and
 * {@code Query.node} / {@code Query.nodes} dispatch).
 *
 * <p>Each method takes the caller's slot list as {@code List<S>} plus a
 * {@code Function<S, ColumnRef>} projection. The lookup site keeps its rich {@code Slot} record
 * (argName + RootSource + decode bindings) and passes {@code Slot::targetColumn}; the dispatcher
 * uses {@code List<ColumnRef>} directly with {@code c -> c}. No parallel-list bridge is required.
 *
 * <p>What's shared:
 * <ul>
 *   <li>The typed {@code Row<N+1><Integer, c1, …>} type-arg array, with the arity-22 cap and a
 *       per-call-site directive context in the cap-violation message.</li>
 *   <li>The {@code @SuppressWarnings({"unchecked", "rawtypes"})}-annotated typed-row array
 *       declaration. Generic array creation forces the cast; both call sites use it.</li>
 *   <li>Per-cell {@code DSL.val(value, table.COL.getDataType())} construction so jOOQ binds
 *       through the column's registered Converter and renders a plain JDBC bind, no SQL
 *       {@code CAST}.</li>
 *   <li>The {@code DSL.values(rows).as(alias, "idx", "<sqlName1>", …)} alias-args list.</li>
 *   <li>A {@code USING(table.C1, …)} column-list helper, consumed by the lookup root path. The
 *       dispatcher path uses an explicit {@code ON} predicate (see {@link SelectMethodBody}'s
 *       Javadoc); the helper does not enforce a join syntax on its callers.</li>
 * </ul>
 *
 * <p>What stays caller-local:
 * <ul>
 *   <li>The for-loop body that fills {@code rows[i]}: the lookup site does composite-key
 *       extraction and per-row {@code DecodedRecord} NodeId decode; the dispatcher reads
 *       {@code idx = binding[0]} and {@code cols = binding[1]}. Callers keep that machinery and
 *       delegate only the typed cell tuple to {@link #cellsCode}.</li>
 *   <li>The {@code idx} cell expression: lookup uses {@code DSL.inline(i)} (compile-time int);
 *       dispatcher uses {@code DSL.val(idx, Integer.class)} (binding-derived). Both render to a
 *       typed {@code Field<Integer>}; callers pass their own {@code idxCellExpr}.</li>
 *   <li>Any extra projections beyond the join (e.g. the dispatcher's
 *       {@code DSL.inline("<TypeName>").as("__typename")}), the join syntax, and the
 *       {@code .where(condition)} / {@code .orderBy(idxCol)} chain.</li>
 * </ul>
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
            typeArgs[i + 1] = ClassName.bestGuess(column.apply(slots.get(i)).columnClass());
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
        var cells = CodeBlock.builder().add("$L", idxCellExpr);
        ClassName dsl = ClassName.get("org.jooq.impl", "DSL");
        for (int i = 0; i < slots.size(); i++) {
            S slot = slots.get(i);
            cells.add(", $T.val($L, $L.$L.getDataType())",
                dsl, valueExpr.apply(slot, i), tableLocal, column.apply(slot).javaName());
        }
        return cells.build();
    }

    /**
     * Returns the alias-args list for {@code DSL.values(rows).as(alias, "idx", "<sqlName1>", …)}.
     * Column labels are SQL names (case-sensitive in PostgreSQL with quoted identifiers); using
     * Java field names here would render mismatched USING/ON predicates downstream.
     */
    public static <S> CodeBlock aliasArgs(List<S> slots, Function<S, ColumnRef> column, String alias) {
        var b = CodeBlock.builder().add("$S, $S", alias, "idx");
        for (var slot : slots) {
            b.add(", $S", column.apply(slot).sqlName());
        }
        return b.build();
    }

    /**
     * Returns the USING-args list: {@code <tableLocal>.<COL1>, <tableLocal>.<COL2>, …}. Use as
     * {@code .join(input).using(<usingArgs>)} on the SELECT chain. The dispatcher path uses an
     * explicit {@code ON} predicate instead and does not consume this method.
     */
    public static <S> CodeBlock usingArgs(List<S> slots, Function<S, ColumnRef> column, String tableLocal) {
        var b = CodeBlock.builder();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("$L.$L", tableLocal, column.apply(slots.get(i)).javaName());
        }
        return b.build();
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
