package no.sikt.graphitron.render;

import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.RoutineWriteCommand;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.SourceKey;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The routine-write fetcher renderer: interprets one {@link RoutineWriteCommand} into the
 * {@code public static <Ret> <field>(DataFetchingEnvironment env)} entry point that executes a
 * {@code @routine} write. Total over the command's two arms, takes no schema and no field leaf.
 *
 * <p><b>The two-statements rule</b>, which both arms obey and neither restates: the write
 * transaction contains the routine call and a projection of the routine's own result columns, and
 * nothing else, ever. Anything past that capture is a post-commit follow-up query running at read
 * time under the caller's identity, which is what the arms differ on:
 *
 * <ul>
 *   <li>{@link RoutineWriteCommand.ChainReread} owns its follow-up. After the commit it re-reads
 *       from hop 0's table, keyed by the values captured off the routine's result rows, joining the
 *       chain's remaining hops forward exactly as a read chain does, and projects the terminus type
 *       through its projection unit.</li>
 *   <li>{@link RoutineWriteCommand.CarrierKeys} has no follow-up here: the captured key record
 *       <em>is</em> the fetcher's value, and the payload's data field owns the re-read. The keys are
 *       projected under the target table's own key fields, so the data field's correlation reads
 *       them back by field identity.</li>
 * </ul>
 *
 * <p>The routine never appears in the follow-up query's {@code FROM}: re-invoking it would
 * re-execute the write. An SQL error raised by the routine rolls the transaction back at the
 * {@code transactionResult} boundary and surfaces exactly as a DML error does, while an error in
 * the follow-up propagates as a field error and cannot undo the committed write, the same caveat
 * the DML fetchers carry.
 *
 * <p>The connection the write runs against comes off the run's tenancy axis
 * ({@link TenantRouting}) rather than from the shell: the plan folds each coordinate's classified
 * binding into the acquisition its entry point emits, and
 * {@link TenantAcquisitionFragments} renders it. What the shell still hands over is its own
 * {@code graphitronContext(env)} seam, a per-class helper collector rather than a decision, so a
 * request-context read and the helper it names land on one class together.
 *
 * <p>The direct arm's shape, at list arity:
 * <pre>{@code
 * public static DataFetcherResult<Result<Record>> rentFilm(DataFetchingEnvironment env) {
 *     try {
 *         RentFilm source = Routines.rentFilm(env.<Integer>getArgument("inventoryId"), ...);
 *         Rental rentFilm_0 = Tables.RENTAL.as("rentFilm_0");
 *         DSLContext dsl = graphitronContext(env).getDslContext(env);
 *         Result<Record1<Integer>> keys = dsl.transactionResult(tx -> DSL.using(tx)
 *             .select(source.RENTAL_ID)
 *             .from(source)
 *             .fetch());
 *         Result<Record> payload = dsl
 *             .select(Rental.$project(env.getSelectionSet().getFieldsGroupedByResultKey(), rentFilm_0, env))
 *             .from(rentFilm_0)
 *             .where(rentFilm_0.RENTAL_ID.in(keys.getValues(source.RENTAL_ID)))
 *             .fetch();
 *         ...
 *     } catch (Exception e) { ... }
 * }
 * }</pre>
 */
public final class RoutineWriteFetcherRenderer {

    private RoutineWriteFetcherRenderer() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");

    /** Renders one routine-write entry point from its row and the run's tenancy axis. */
    public static MethodSpec render(RoutineWriteCommand row, TenantRouting tenancy,
            ArgPathHelperRegistry argHelpers, ProjectedKeyHost keyHost,
            RequestContextRead contextRead) {
        var keys = keyHost.at(row.coordinate());
        var dsl = TenantAcquisitionFragments.declare(tenancy, row.coordinate(), contextRead);
        return switch (row) {
            case RoutineWriteCommand.ChainReread r ->
                renderChainReread(r, dsl.statement(), dsl.localContextTail(), argHelpers, keys);
            case RoutineWriteCommand.CarrierKeys r ->
                renderCarrierKeys(r, dsl.statement(), dsl.localContextTail(), argHelpers, keys);
        };
    }

    private static MethodSpec renderChainReread(RoutineWriteCommand.ChainReread row,
            CodeBlock dslDeclaration, CodeBlock localContextTail, ArgPathHelperRegistry argHelpers,
            ProjectedKeyReads keys) {
        boolean isList = row.arity() == Arity.LIST;
        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, RECORD) : RECORD;
        var builder = entryPoint(row, valueType);

        var call = RoutineCallEmitter.emitCall(row.call(), new PreviousNodeRef.None(),
            new ArgumentValueSource.Env(), argHelpers, keys);
        // Any node-id decode a projected IN parameter needs, declared ahead of the try. Outside it
        // deliberately: the catch arm below routes what it catches through the field's error
        // channel, and a malformed node id is a client error about an argument rather than a
        // database error about a write.
        builder.addCode(keys.declarations());
        builder.beginControlFlow("try");
        builder.addStatement("$T source = $L", row.call().resultTable().tableClass(), call);
        for (JoinStep step : row.hops()) {
            var hop = (JoinStep.Hop) step;
            builder.addStatement("$T $L = $L.as($S)", hop.targetTable().tableClass(), hop.alias(),
                PathFragments.emitTableExpression(step, new PreviousNodeRef.None(),
                    new ArgumentValueSource.Env(), argHelpers),
                hop.alias());
        }
        builder.addCode(dslDeclaration);

        // The write. The routine executes inside the per-field transaction; the SELECT captures
        // hop 0's source-side key columns off the routine's result rows, and the commit happens
        // when the lambda returns.
        var capturedColumns = sourceSides(row.capturedSlots());
        TypeName keyRowType = SourceKey.keyElementType(new SourceKey.Wrap.Record(), capturedColumns);
        builder.addCode(captureTransaction(
            isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType,
            capturedColumns, null, isList));
        builder.addCode(nullKeysGuard(valueType, isList));

        // The post-commit re-read: anchored on hop 0's table keyed by the captured values, with
        // the remaining hops joining forward exactly as the read chain's fetcher emits them.
        String anchorLocal = row.anchorAlias();
        var sel = CodeBlock.builder()
            .add("$T payload = dsl\n", valueType)
            .indent()
            .add(".select($L)\n", ProjectionCall.fromEnvSelection(
                className(row.terminusProjection()), row.terminalAlias()))
            .add(".from($L)\n", anchorLocal);
        var filters = new ArrayList<CodeBlock>();
        var hops = row.hops();
        for (int i = 1; i < hops.size(); i++) {
            var hop = (JoinStep.Hop) hops.get(i);
            String prev = ((JoinStep.Hop) hops.get(i - 1)).alias();
            switch (hop.on()) {
                case On.ColumnPairs cp -> sel.add("$L\n", JoinFragments.emitForwardJoin(cp, prev, hop.alias()));
                case On.Predicate pred -> sel.add(".join($L).on($L)\n", hop.alias(),
                    PathFragments.emitTwoArgMethodCall(pred.condition(), prev, hop.alias()));
                // Unrepresentable: the chain admits exactly one routine node, its start, so no
                // hop position carries a lateral join.
                case On.Lateral ignored -> throw new IllegalStateException(
                    "a lateral routine hop cannot appear in a root routine chain's hops");
            }
            if (hop.filter() != null) {
                filters.add(PathFragments.emitTwoArgMethodCall(hop.filter(), prev, hop.alias()));
            }
        }
        var anchorCols = row.capturedSlots().stream()
            .map(s -> CodeBlock.of("$L.$L", anchorLocal, s.targetSide().javaName()))
            .toList();
        var capturedCols = row.capturedSlots().stream()
            .map(s -> CodeBlock.of("source.$L", s.sourceSide().javaName()))
            .toList();
        var where = filters.stream().reduce(keysInCondition(anchorCols, capturedCols, isList),
            (a, b) -> CodeBlock.of("$L.and($L)", a, b));
        sel.add(".where($L)\n", where);
        sel.add(isList ? ".fetch();\n" : ".fetchOne();\n").unindent();
        builder.addCode(sel.build());

        return closeEntryPoint(builder, row, valueType, "payload", localContextTail, null);
    }

    private static MethodSpec renderCarrierKeys(RoutineWriteCommand.CarrierKeys row,
            CodeBlock dslDeclaration, CodeBlock localContextTail, ArgPathHelperRegistry argHelpers,
            ProjectedKeyReads keys) {
        boolean isList = row.arity() == Arity.LIST;
        var targetKeyColumns = row.capturedPairs().stream().map(JoinSlot::targetSide).toList();
        TypeName keyRowType = SourceKey.keyElementType(new SourceKey.Wrap.Record(), targetKeyColumns);
        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType;
        var builder = entryPoint(row, valueType);

        var call = RoutineCallEmitter.emitCall(row.call(), new PreviousNodeRef.None(),
            new ArgumentValueSource.Env(), argHelpers, keys);
        // Outside the try, for the reason the sibling arm states.
        builder.addCode(keys.declarations());
        builder.beginControlFlow("try");
        builder.addStatement("$T source = $L", row.call().resultTable().tableClass(), call);
        builder.addCode(dslDeclaration);

        // The whole emit: the routine call and a projection of its own result columns, re-typed
        // under the target table's key fields so the payload data field reads them back by
        // field identity rather than by jOOQ's name-lookup fallback.
        var coerceTo = CodeBlock.builder();
        for (int i = 0; i < targetKeyColumns.size(); i++) {
            if (i > 0) coerceTo.add(", ");
            coerceTo.add("$T.$L.$L", row.targetTable().constantsClass(),
                row.targetTable().javaFieldName(), targetKeyColumns.get(i).javaName());
        }
        builder.addCode(captureTransaction(valueType, sourceSides(row.capturedPairs()),
            coerceTo.build(), isList));
        builder.addCode(nullKeysGuard(valueType, isList));

        var sentinel = isList
            ? RecordSentinel.bulk(row.targetTable(), targetKeyColumns)
            : RecordSentinel.single(row.targetTable(), targetKeyColumns);
        return closeEntryPoint(builder, row, valueType, "keys", localContextTail, sentinel);
    }

    /** The entry point's signature: the field's own name, one {@code env} parameter. */
    private static MethodSpec.Builder entryPoint(RoutineWriteCommand row, TypeName valueType) {
        return MethodSpec.methodBuilder(row.unit().methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(FetcherResult.syncResultType(valueType))
            .addParameter(ENV, "env");
    }

    /** The success return plus the {@code catch} arm, closing the {@code try} both arms opened. */
    private static MethodSpec closeEntryPoint(MethodSpec.Builder builder, RoutineWriteCommand row,
            TypeName valueType, String payloadLocal, CodeBlock localContextTail, CodeBlock sentinel) {
        builder.addCode(FetcherResult.success(valueType, payloadLocal, localContextTail));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(ErrorDispatchFragments.catchArm(row.errors(), sentinel));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * {@code <keysType> keys = dsl.transactionResult(tx -> DSL.using(tx).select(...).from(source)
     * [.coerce(...)].fetch[One]());} — the write half both arms emit. {@code coerceTo} is the
     * carrier arm's re-typing and null on the direct arm, whose captured columns are read back off
     * the routine's own result table.
     */
    private static CodeBlock captureTransaction(TypeName keysType, List<ColumnRef> capturedColumns,
            CodeBlock coerceTo, boolean isList) {
        var b = CodeBlock.builder()
            .add("$T keys = dsl.transactionResult(tx -> $T.using(tx)\n", keysType, DSL).indent()
            .add(".select(");
        for (int i = 0; i < capturedColumns.size(); i++) {
            if (i > 0) b.add(", ");
            b.add("source.$L", capturedColumns.get(i).javaName());
        }
        b.add(")\n").add(".from(source)\n");
        if (coerceTo != null) {
            b.add(".coerce($L)\n", coerceTo);
        }
        return b.add(isList ? ".fetch());\n" : ".fetchOne());\n").unindent().build();
    }

    /**
     * The single shape's empty-capture guard: the routine legitimately returned no row, so nothing
     * was keyed and the whole payload renders null, the same contract the DML single-row shape
     * carries. The many shape needs none, an empty {@code Result} being a value.
     */
    private static CodeBlock nullKeysGuard(TypeName valueType, boolean isList) {
        return isList
            ? CodeBlock.of("")
            : CodeBlock.builder().add("if (keys == null) ").add(FetcherResult.nullData(valueType)).build();
    }

    private static List<ColumnRef> sourceSides(List<JoinSlot.FkSlot> slots) {
        return slots.stream().map(JoinSlot::sourceSide).toList();
    }

    /**
     * The re-read's key predicate: hop 0's target-side columns restricted to the captured
     * source-side values, read back off the {@code keys} local the write half declared. Forks on
     * arity (a {@code Result} of keys reads through {@code getValues} / a row-value {@code IN}, a
     * single record through {@code eq}) and on key width (a single column compares directly, a
     * composite through {@code DSL.row(...)}).
     *
     * <p>Private, and meant to stay the direct arm's alone: a DML reentry companion resolves its
     * correlation through its launcher row's source arm ({@link ReentryRowsFragments}), and a
     * routine-write coordinate joins that seam if its member set ever mints the reentry re-select.
     * Do not grow keys-IN callers.
     */
    private static CodeBlock keysInCondition(List<CodeBlock> conditionCols, List<CodeBlock> keyCols,
            boolean isList) {
        var b = CodeBlock.builder();
        if (isList) {
            if (conditionCols.size() == 1) {
                b.add("$L.in(keys.getValues($L))", conditionCols.get(0), keyCols.get(0));
            } else {
                b.add("$T.row(", DSL);
                for (int i = 0; i < conditionCols.size(); i++) {
                    if (i > 0) b.add(", ");
                    b.add("$L", conditionCols.get(i));
                }
                b.add(").in(keys.stream().map(r -> $T.row(", DSL);
                for (int i = 0; i < keyCols.size(); i++) {
                    if (i > 0) b.add(", ");
                    b.add("r.get($L)", keyCols.get(i));
                }
                b.add(")).toList())");
            }
        } else {
            if (conditionCols.size() == 1) {
                b.add("$L.eq(keys.value1())", conditionCols.get(0));
            } else {
                b.add("$T.row(", DSL);
                for (int i = 0; i < conditionCols.size(); i++) {
                    if (i > 0) b.add(", ");
                    b.add("$L", conditionCols.get(i));
                }
                b.add(").eq($T.row(", DSL);
                for (int i = 0; i < keyCols.size(); i++) {
                    if (i > 0) b.add(", ");
                    b.add("keys.get($L)", keyCols.get(i));
                }
                b.add("))");
            }
        }
        return b.build();
    }

    private static ClassName className(UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
