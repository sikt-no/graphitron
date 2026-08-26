package no.sikt.graphitron.render;

import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.SourceKey;

import java.util.List;

/**
 * The reentry companion's body fragments: the follow-up SELECT run against keys the entry point
 * captured, rendered from the row's {@link LaunchSource.Reentry} arm. Two entry points capture
 * keys and call this companion, and the rendered body is the same for both: a mutation's write
 * captures them through {@code RETURNING}, and a root {@code @service} fetcher lifts them off the
 * records the developer's method returned. The companion resolves its correlation (the carried
 * {@link ParentCorrelation.OnLiftedSlots} fact) through one seam at two cardinalities: the list
 * arm renders the shared VALUES-join primitive (a {@code VALUES(idx, key...)} derived table
 * built through {@link ValuesJoinRowBuilder}, joined to the target over the correlation,
 * ordered by {@code idx} so the payload aligns one-to-one and in order with the keys the caller
 * handed over); the single arm renders the legible degenerate, plain key equality,
 * with no VALUES table and no ORDER BY.
 *
 * <p>Deliberately no empty-input gate: each caller owns it. The write emitter has the no-match
 * guard (a single-row write with no match short-circuits before the call; a list write's
 * empty-input contract short-circuits before the transaction), and the root {@code @service}
 * fetcher gates a null or empty service return before the call. So the companion is only ever
 * called with captured keys, and adding a gate here would duplicate a contract another emission
 * already owns.
 */
public final class ReentryRowsFragments {

    private ReentryRowsFragments() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName LIST = ClassName.get("java.util", "List");

    /** Generated-local names for the list arm's VALUES-join primitive. */
    private static final String KEY_ROWS = "keyRows";
    private static final String KEYS_INPUT = "keysInput";

    /**
     * Generation-time context string for {@link ValuesJoinRowBuilder}'s arity-cap diagnostics.
     * {@code GraphitronSchemaValidator} rejects an over-arity reentry key at validate time
     * (mirroring this cap, at both callers), so the row builder's own throw is a backstop for
     * model objects constructed outside the pipeline.
     */
    private static final String ROW_CONTEXT = "@table-return reentry key";

    /**
     * The companion's {@code keys} parameter type, derived from the row's one correlation fact
     * (the same fact each caller derives its key list from, so the assignment compatibility
     * across the generated call boundary is structural): the typed {@code RecordN} key row,
     * lifted to {@code Result} for the list shape.
     */
    public static TypeName keysType(LauncherCommand row) {
        var correlation = ((LaunchSource.Reentry) row.source()).correlation();
        TypeName keyRowType = SourceKey.keyElementType(
            new SourceKey.Wrap.Record(), correlation.columns());
        return row.result() instanceof ResultShape.RecordList
            ? ParameterizedTypeName.get(RESULT, keyRowType)
            : keyRowType;
    }

    /**
     * The companion's public payload type, a stated decision rather than a derivation quirk:
     * {@code List<Record>} for the list shape (the mapped {@code fetch(r -> r)} terminal's own
     * type, pinned by the SQL baselines and the compile spec) and {@code Record} for the single
     * shape. The other shapes are unrepresentable on a reentry row (the command constructor
     * rejects them).
     */
    public static TypeName valueTypeOf(LauncherCommand row) {
        return switch (row.result()) {
            case ResultShape.RecordList ignored -> ParameterizedTypeName.get(LIST, RECORD);
            case ResultShape.SingleRecord ignored -> RECORD;
            case ResultShape.Connection ignored -> throw new IllegalStateException(
                "a reentry companion never paginates; the command constructor rejects the pair");
            case ResultShape.LoaderDelegated ignored -> throw new IllegalStateException(
                "the LoaderDelegated result belongs to the service arms; the command"
                + " constructor rejects the pair");
        };
    }

    /**
     * The projected arm's body: the dsl declaration (the shell's fragment, the same
     * dsl-declaration carve-out the batched families consume), the table local, and the
     * key-restricted re-select through the projection unit's {@code $project}.
     */
    public static CodeBlock projectedBody(LauncherCommand row, LaunchSource.ProjectedReentry source,
            CodeBlock dslDeclaration) {
        var table = source.table();
        String tableLocal = TableLocal.name(table);
        var correlation = source.correlation();
        var select = ProjectionCall.fromEnvSelection(
            ClassName.get(source.projection().packageName(), source.projection().simpleName()),
            tableLocal);
        var b = CodeBlock.builder()
            .add(dslDeclaration)
            .add(TableLocal.declare(table));
        if (row.result() instanceof ResultShape.RecordList) {
            b.add(valuesJoinDecls(correlation))
                .add("return dsl.select($L)\n", select)
                .add("    .from($L)\n", tableLocal)
                .add("    .join($L).on($L)\n", KEYS_INPUT, valuesJoinOn(correlation))
                .add("    .orderBy($L)\n", idxField())
                .add("    .fetch(r -> r);\n");
        } else {
            b.add("return dsl.select($L)\n", select)
                .add("    .from($L)\n", tableLocal)
                .add("    .where(").add(keyEquality(correlation)).add(")\n")
                .add("    .fetchOne(r -> r);\n");
        }
        return b.build();
    }

    /**
     * The discriminated arm's body: the same dsl declaration and key restriction, with the
     * borrowed {@link LaunchSource.DiscriminatedTable} payload's whole assembly as the
     * re-projection. The condition composition order is pin-arbitrated: the key restriction is
     * seeded first (the single arm's equality; the list arm rides the {@code keysInput} join
     * instead, seeding the neutral condition) and the assembly ANDs the discriminator
     * {@code IN} on top.
     */
    public static CodeBlock discriminatedBody(LauncherCommand row,
            LaunchSource.DiscriminatedReentry source, CodeBlock dslDeclaration) {
        var disc = source.discriminated();
        var table = disc.table();
        String tableLocal = TableLocal.name(table);
        var correlation = source.correlation();
        var b = CodeBlock.builder()
            .add(dslDeclaration)
            .add(TableLocal.declare(table));
        if (row.result() instanceof ResultShape.RecordList) {
            b.add(valuesJoinDecls(correlation))
                .addStatement("$T condition = $T.noCondition()", CONDITION, DSL)
                .add(DiscriminatedTableFragments.assembly(disc, List.of(), tableLocal))
                .addStatement("return step.join($L).on($L).where(condition).orderBy($L).fetch()",
                    KEYS_INPUT, valuesJoinOn(correlation), idxField());
        } else {
            b.addStatement("$T condition = $L", CONDITION, keyEquality(correlation))
                .add(DiscriminatedTableFragments.assembly(disc, List.of(), tableLocal))
                .addStatement("return step.where(condition).fetchOne()");
        }
        return b.build();
    }

    /**
     * The list arm's correlation resolution: declares the typed {@code keyRows} array (one
     * {@code Row<N+1>(idx, key...)} per captured key row) and the {@code keysInput}
     * derived table ({@code DSL.values(keyRows).as("keysInput", "idx", ...)}) the follow-up
     * SELECT joins. Requires the {@code keys} local ({@code Result<RecordN<...>>}) in scope.
     * Row typing, cell binds (through each column's registered Converter), and alias args all
     * come from {@link ValuesJoinRowBuilder}, the same core the batched rows methods render.
     */
    private static CodeBlock valuesJoinDecls(ParentCorrelation.OnLiftedSlots correlation) {
        var cols = correlation.columns();
        var owner = correlation.targetTable();
        CodeBlock tableExpr = CodeBlock.of("$T.$L", owner.constantsClass(), owner.javaFieldName());
        var keyRowType = SourceKey.keyElementType(new SourceKey.Wrap.Record(), cols);
        var b = CodeBlock.builder();
        ValuesJoinRowBuilder.emitRowArrayDecl(b, cols, c -> c, ROW_CONTEXT,
            KEY_ROWS, "keys.size()");
        b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        b.addStatement("$T k = keys.get(i)", keyRowType);
        b.addStatement("$L[i] = $T.row($L)", KEY_ROWS, DSL,
            ValuesJoinRowBuilder.cellsCode(cols, c -> c,
                CodeBlock.of("$T.val(i, $T.class)", DSL, Integer.class), tableExpr,
                (col, ci) -> CodeBlock.of("k.get($L.$L)", tableExpr, col.javaName())));
        b.endControlFlow();
        b.addStatement("$T $L = $T.values($L).as($L)",
            ValuesJoinRowBuilder.inputTableType(cols, c -> c, ROW_CONTEXT),
            KEYS_INPUT, DSL, KEY_ROWS,
            ValuesJoinRowBuilder.aliasArgs(cols, c -> c, KEYS_INPUT));
        return b.build();
    }

    /**
     * The list arm's join predicate over the carried correlation:
     * {@code <target>.<COL>.eq(keysInput.field("<sqlName>", <target>.<COL>.getDataType()))} per
     * column, chained with {@code .and(...)}. Field lookup by SQL name plus the owner column's
     * {@code DataType} keeps converter-backed columns' type metadata faithful (the same
     * resolution the batched rows methods use for their {@code parentInput} predicate).
     */
    private static CodeBlock valuesJoinOn(ParentCorrelation.OnLiftedSlots correlation) {
        var cols = correlation.columns();
        var owner = correlation.targetTable();
        var on = CodeBlock.builder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) on.add(".and(");
            var col = cols.get(i);
            on.add("$T.$L.$L.eq($L.field($S, $T.$L.$L.getDataType()))",
                owner.constantsClass(), owner.javaFieldName(), col.javaName(),
                KEYS_INPUT, col.sqlName(),
                owner.constantsClass(), owner.javaFieldName(), col.javaName());
            if (i > 0) on.add(")");
        }
        return on.build();
    }

    /** The list arm's ordering field: {@code keysInput.field("idx", Integer.class)}. */
    private static CodeBlock idxField() {
        return CodeBlock.of("$L.field($S, $T.class)", KEYS_INPUT, "idx", Integer.class);
    }

    /**
     * The single arm's correlation resolution: plain key equality against the single
     * {@code RecordN} {@code keys} local ({@code col.eq(keys.value1())} for a one-column key,
     * the {@code DSL.row(...).eq(DSL.row(keys.get(...), ...))} row-value form for a composite
     * key). The legible degenerate of the VALUES-join primitive at row-count 1; no VALUES table
     * and no ORDER BY.
     */
    private static CodeBlock keyEquality(ParentCorrelation.OnLiftedSlots correlation) {
        var owner = correlation.targetTable();
        var colExprs = correlation.columns().stream()
            .map(col -> CodeBlock.of("$T.$L.$L",
                owner.constantsClass(), owner.javaFieldName(), col.javaName()))
            .toList();
        var b = CodeBlock.builder();
        if (colExprs.size() == 1) {
            b.add("$L.eq(keys.value1())", colExprs.get(0));
        } else {
            b.add("$T.row(", DSL);
            for (int i = 0; i < colExprs.size(); i++) {
                if (i > 0) b.add(", ");
                b.add("$L", colExprs.get(i));
            }
            b.add(").eq($T.row(", DSL);
            for (int i = 0; i < colExprs.size(); i++) {
                if (i > 0) b.add(", ");
                b.add("keys.get($L)", colExprs.get(i));
            }
            b.add("))");
        }
        return b.build();
    }
}
