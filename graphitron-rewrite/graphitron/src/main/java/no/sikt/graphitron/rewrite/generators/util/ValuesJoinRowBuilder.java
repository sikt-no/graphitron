package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Shared row-construction core for the two emitters that produce
 * {@code VALUES (idx, c1, …) JOIN <table> USING (…) ORDER BY idx} SELECTs:
 * {@link no.sikt.graphitron.rewrite.generators.LookupValuesJoinEmitter} (root and inline-child
 * lookup paths) and {@link SelectMethodBody} (federated {@code _entities} and
 * {@code Query.node} / {@code Query.nodes} dispatch).
 *
 * <p>Holds the parts that are identical across the two call sites:
 * <ul>
 *   <li>The typed {@code Row<N+1><Integer, c1, …>} type-arg array, with the arity-22 cap.</li>
 *   <li>The {@code @SuppressWarnings({"unchecked", "rawtypes"})}-annotated typed-row array
 *       declaration. Generic array creation forces the cast; both call sites use it.</li>
 *   <li>Per-cell {@code DSL.val(value, table.COL.getDataType())} construction so jOOQ binds
 *       through the column's registered Converter and renders a plain JDBC bind, no SQL
 *       {@code CAST}.</li>
 *   <li>The {@code DSL.values(rows).as(alias, "idx", "<sqlName1>", …)} alias-args list.</li>
 *   <li>The {@code USING(table.C1, table.C2, …)} column-list. R55 standardised both call sites
 *       on {@code .using(…)}: the dispatcher's FROM side is the entity's own jOOQ table only
 *       (no FK chain), so quoted-name collisions are not a risk; for the lookup site, the same
 *       safety holds at the root path. The inline-child lookup path uses {@code .on(…)} per its
 *       own emitter (not consumed via this helper).</li>
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
 *       {@code DSL.inline("<TypeName>").as("__typename")}) and the {@code .where(condition)} /
 *       {@code .orderBy(idxCol)} chain.</li>
 * </ul>
 */
public final class ValuesJoinRowBuilder {

    /**
     * jOOQ exposes typed {@code Row1..Row22} / {@code Record1..Record22}. Higher arities fall
     * back to the raw {@code RowN} / {@code Record} forms which lose typed cell access; the
     * {@code idx} cell occupies one slot, so the practical column count limit is 21.
     */
    public static final int MAX_ARITY = 22;

    /** A target column on the joined table. The helper deliberately does not carry per-call-site context. */
    public record Slot(ColumnRef targetColumn) {}

    private ValuesJoinRowBuilder() {}

    /** Returns {@code Row<N+1>} for a row of {@code slots.size() + 1} cells (idx + per-slot). */
    public static ClassName rowClass(int slotCount) {
        return ClassName.get("org.jooq", "Row" + (slotCount + 1));
    }

    /** Returns {@code Record<N+1>} for the matching row arity. */
    public static ClassName recordClass(int slotCount) {
        return ClassName.get("org.jooq", "Record" + (slotCount + 1));
    }

    /**
     * Returns the {@code Row<N+1>} / {@code Record<N+1>} type-args: {@code Integer} for the
     * {@code idx} cell, then one per slot resolved via {@link ColumnRef#columnClass()}. Throws
     * {@link IllegalStateException} when arity exceeds {@link #MAX_ARITY}.
     */
    public static TypeName[] rowTypeArgs(List<Slot> slots) {
        int arity = slots.size() + 1;
        if (arity > MAX_ARITY) {
            throw new IllegalStateException(
                "Row arity " + arity + " (idx + " + slots.size() + " columns) exceeds jOOQ's typed "
                + "Row/Record limit of " + MAX_ARITY + "; compound keys with >" + (MAX_ARITY - 1)
                + " fields are not supported");
        }
        TypeName[] typeArgs = new TypeName[arity];
        typeArgs[0] = ClassName.get(Integer.class);
        for (int i = 0; i < slots.size(); i++) {
            typeArgs[i + 1] = ClassName.bestGuess(slots.get(i).targetColumn().columnClass());
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
    public static void emitRowArrayDecl(CodeBlock.Builder b, List<Slot> slots,
                                         String rowsLocal, String sizeExpr) {
        TypeName[] typeArgs = rowTypeArgs(slots);
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
    public static CodeBlock cellsCode(List<Slot> slots, CodeBlock idxCellExpr,
                                       String tableLocal,
                                       BiFunction<Slot, Integer, CodeBlock> valueExpr) {
        var cells = CodeBlock.builder().add("$L", idxCellExpr);
        ClassName dsl = ClassName.get("org.jooq.impl", "DSL");
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            cells.add(", $T.val($L, $L.$L.getDataType())",
                dsl, valueExpr.apply(slot, i), tableLocal, slot.targetColumn().javaName());
        }
        return cells.build();
    }

    /**
     * Returns the alias-args list for {@code DSL.values(rows).as(alias, "idx", "<sqlName1>", …)}.
     * Column labels are SQL names (case-sensitive in PostgreSQL with quoted identifiers); using
     * Java field names here would render mismatched USING/ON predicates downstream.
     */
    public static CodeBlock aliasArgs(List<Slot> slots, String alias) {
        var b = CodeBlock.builder().add("$S, $S", alias, "idx");
        for (var slot : slots) {
            b.add(", $S", slot.targetColumn().sqlName());
        }
        return b.build();
    }

    /**
     * Returns the USING-args list: {@code <tableLocal>.<COL1>, <tableLocal>.<COL2>, …}. Use as
     * {@code .join(input).using(<aliasArgs>)} on the SELECT chain.
     */
    public static CodeBlock usingArgs(List<Slot> slots, String tableLocal) {
        var b = CodeBlock.builder();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("$L.$L", tableLocal, slots.get(i).targetColumn().javaName());
        }
        return b.build();
    }

    /** Convenience: array type {@code Row<N+1>[]} for a {@code returns} declaration on a helper method. */
    public static TypeName rowArrayType(List<Slot> slots) {
        TypeName[] typeArgs = rowTypeArgs(slots);
        return ArrayTypeName.of(ParameterizedTypeName.get(rowClass(slots.size()), typeArgs));
    }

    /** Convenience: parameterised {@code Table<Record<N+1><Integer, c1, …>>} for the values-derived-table local. */
    public static TypeName inputTableType(List<Slot> slots) {
        TypeName[] typeArgs = rowTypeArgs(slots);
        ClassName table = ClassName.get("org.jooq", "Table");
        return ParameterizedTypeName.get(table, ParameterizedTypeName.get(recordClass(slots.size()), typeArgs));
    }
}
