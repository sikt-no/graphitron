package no.sikt.graphitron.emit;

import no.sikt.graphitron.emit.RoutineWriteQuery.Column;
import no.sikt.graphitron.emit.RoutineWriteQuery.Hop;
import no.sikt.graphitron.emit.RoutineWriteQuery.Keying;
import no.sikt.graphitron.emit.RoutineWriteQuery.Pair;
import no.sikt.graphitron.emit.RoutineWriteQuery.Row;
import no.sikt.graphitron.emit.RoutineWriteQuery.Table;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.render.ArgPathHelperRegistry;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.render.ColumnComparison;
import no.sikt.graphitron.render.ErrorDispatchFragments;
import no.sikt.graphitron.render.FetcherResult;
import no.sikt.graphitron.render.ProjectedKeyReads;
import no.sikt.graphitron.render.ProjectionCall;
import no.sikt.graphitron.render.RoutineCallEmitter;
import no.sikt.graphitron.render.TenantHandDown;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Emits the datafetcher entry point of one {@code @routine}-writing mutation coordinate, from that
 * coordinate's row of {@link RoutineWriteQuery}:
 * {@code public static DataFetcherResult<Ret> <field>(DataFetchingEnvironment env)}.
 *
 * <p><b>The two-statements rule.</b> The write transaction holds the routine call and a projection
 * of the routine's own result columns, and nothing else. Anything past that capture is a
 * post-commit query under the caller's identity, and the two seats differ only there: the chain
 * seat re-reads from its first hop's table keyed by the captured values, joining the remaining hops
 * forward and projecting the terminus type; the carrier seat returns the captured keys, typed as the
 * target table's key fields, and leaves the re-read to the payload's data field. The routine never
 * appears in the follow-up {@code FROM}: re-invoking it would re-execute the write. An error in the
 * routine rolls back at the {@code transactionResult} boundary; an error in the follow-up cannot
 * undo the committed write.
 *
 * <p>Two facts come from the caller as values because the store does not state them yet: the
 * error channel ({@link Errors}) and the run's tenancy ({@link Tenancy}). Generated names (the
 * method, the projection unit, the runtime units) are the caller's too, minted where every other
 * reference to them is.
 */
public final class RoutineWriteEmitter {

    private RoutineWriteEmitter() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");

    /** What the {@code catch} arm does with a throw. */
    public sealed interface Errors {

        /** No typed-error channel: the router's privacy disposition. */
        record Redacting(ClassName errorRouter) implements Errors {
            public Redacting {
                Objects.requireNonNull(errorRouter, "errorRouter");
            }
        }

        /**
         * The typed {@code Outcome} transport: the value is wrapped in {@code Outcome.Success}, and a
         * throw matching the channel's mappings constant returns {@code Outcome.ErrorList}.
         */
        record OutcomeWrapped(ClassName errorRouter, ClassName errorMappings, ClassName outcome,
                              String mappingsConstantName) implements Errors {
            public OutcomeWrapped {
                Objects.requireNonNull(errorRouter, "errorRouter");
                Objects.requireNonNull(errorMappings, "errorMappings");
                Objects.requireNonNull(outcome, "outcome");
                if (mappingsConstantName == null || mappingsConstantName.isBlank()) {
                    throw new IllegalArgumentException(
                        "an Outcome-wrapped channel names its mappings constant; a blank one would"
                        + " emit a reference to nothing");
                }
            }
        }
    }

    /**
     * Where the write's connection comes from. A routine write mints no tenant slot and a
     * tenant-scoped one is rejected before emission, so a multi-tenant run reaches only the fixed
     * default source.
     */
    public sealed interface Tenancy {

        /** A single-tenant run: the request context's one connection. */
        record Unrouted() implements Tenancy {}

        /** A multi-tenant run: the default source, through the generated connections carrier. */
        record DefaultSource(ClassName connections) implements Tenancy {
            public DefaultSource {
                Objects.requireNonNull(connections, "connections");
            }
        }
    }

    /**
     * Renders the entry point for {@code row}.
     *
     * @param methodName        the entry point's generated name
     * @param returnTypeClass   the return type's projection unit; read on the chain seat only
     * @param selectionOccurrences the generated selection scaffold the chain seat's re-read
     *                          groups the fetched field's own fields through
     * @param graphitronContext the host class's {@code graphitronContext(env)} call; calling it
     *                          records that the host needs the helper, so it is called only where
     *                          the call is emitted
     * @param argHelpers        the host class's collector of dotted-path descent helpers
     * @param keys              the method's node-id decodes, for an argument whose path projects a
     *                          key column
     */
    public static MethodSpec render(Row row, String methodName, ClassName returnTypeClass,
            ClassName selectionOccurrences, Errors errors, Tenancy tenancy, Supplier<CodeBlock> graphitronContext,
            ArgPathHelperRegistry argHelpers, ProjectedKeyReads keys) {
        var dsl = switch (tenancy) {
            case Tenancy.Unrouted ignored -> CodeBlock.builder()
                .addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, graphitronContext.get())
                .build();
            case Tenancy.DefaultSource d -> CodeBlock.builder()
                .addStatement("$T dsl = $T.dslDefault(env)", DSL_CONTEXT, d.connections())
                .build();
        };
        var call = routineCall(row, argHelpers, keys);
        return switch (row.seat()) {
            case CHAIN -> chainReread(row, methodName, returnTypeClass, selectionOccurrences, errors, dsl, call, keys);
            case CARRIER -> carrierKeys(row, methodName, errors, dsl, call, keys);
        };
    }

    private static MethodSpec chainReread(Row row, String methodName, ClassName returnTypeClass,
            ClassName selectionOccurrences,
            Errors errors, CodeBlock dsl, CodeBlock call, ProjectedKeyReads keys) {
        boolean isList = row.listReturn();
        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, RECORD) : RECORD;
        TypeName declaredType = declaredValueType(errors, valueType);
        var builder = entryPoint(methodName, declaredType);
        var anchor = row.hops().getFirst();
        var tail = row.hops().subList(1, row.hops().size());

        // Node-id decodes go ahead of the try: a malformed id is a client error about an argument,
        // not a database error for the catch arm's error channel.
        builder.addCode(keys.declarations());
        builder.beginControlFlow("try");
        builder.addStatement("$T source = $L", tableClass(row.call().resultTable()), call);
        for (var hop : row.hops()) {
            builder.addStatement(aliasDeclaration(hop.table(), hop.alias()));
        }
        builder.addCode(dsl);

        var capturedColumns = anchor.pairs().stream().map(Pair::source).toList();
        TypeName keyRowType = keyRecordType(capturedColumns);
        builder.addCode(captureTransaction(
            isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType,
            capturedColumns, null, isList));
        builder.addCode(nullKeysGuard(declaredType, isList));

        // The post-commit re-read: from the anchor keyed by the captured values, the tail hops
        // joining forward as a read chain does.
        String anchorAlias = anchor.alias();
        String terminalAlias = row.hops().getLast().alias();
        var sel = CodeBlock.builder()
            .add("$T payload = dsl\n", valueType)
            .indent()
            .add(".select($L)\n", ProjectionCall.fromEnvSelection(returnTypeClass, terminalAlias,
                selectionOccurrences, ProjectionCall.SelectionScope.OWN_FIELDS))
            .add(".from($L)\n", anchorAlias);
        var filters = new ArrayList<CodeBlock>();
        String prev = anchorAlias;
        for (var hop : tail) {
            sel.add("$L\n", forwardJoin(hop, prev));
            if (hop.filter() != null) {
                filters.add(CodeBlock.of("$T.$L($L, $L)", ClassName.bestGuess(hop.filter().className()),
                    hop.filter().methodName(), prev, hop.alias()));
            }
            prev = hop.alias();
        }
        var anchorCols = anchor.pairs().stream()
            .map(p -> CodeBlock.of("$L.$L", anchorAlias, p.target().javaName()))
            .toList();
        var capturedCols = anchor.pairs().stream()
            .map(p -> CodeBlock.of("source.$L", p.source().javaName()))
            .toList();
        var where = filters.stream().reduce(keysCondition(anchorCols, capturedCols, isList),
            (a, b) -> CodeBlock.of("$L.and($L)", a, b));
        sel.add(".where($L)\n", where);
        sel.add(isList ? ".fetch();\n" : ".fetchOne();\n").unindent();
        builder.addCode(sel.build());

        return closeEntryPoint(builder, errors, declaredType, "payload");
    }

    private static MethodSpec carrierKeys(Row row, String methodName, Errors errors, CodeBlock dsl,
            CodeBlock call, ProjectedKeyReads keys) {
        boolean isList = row.listReturn();
        var targetKeyColumns = row.capturedPairs().stream().map(Pair::target).toList();
        TypeName keyRowType = keyRecordType(targetKeyColumns);
        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType;
        TypeName declaredType = declaredValueType(errors, valueType);
        var builder = entryPoint(methodName, declaredType);

        builder.addCode(keys.declarations());
        builder.beginControlFlow("try");
        builder.addStatement("$T source = $L", tableClass(row.call().resultTable()), call);
        builder.addCode(dsl);

        // Re-typed under the target table's key fields, so the data field reads them back by field
        // identity rather than by jOOQ's name-lookup fallback.
        var target = row.targetTable();
        var coerceTo = CodeBlock.join(targetKeyColumns.stream()
            .map(c -> CodeBlock.of("$T.$L.$L", CatalogRefs.className(target.constantsClassName()),
                target.javaFieldName(), c.javaName()))
            .toList(), ", ");
        builder.addCode(captureTransaction(valueType,
            row.capturedPairs().stream().map(Pair::source).toList(), coerceTo, isList));
        builder.addCode(nullKeysGuard(declaredType, isList));

        return closeEntryPoint(builder, errors, declaredType, "keys");
    }

    /**
     * {@code Routines.<method>(<args>)}, the value overload: a routine write is a mutation root's,
     * so every parameter binds a request value and none a previous node's column.
     */
    private static CodeBlock routineCall(Row row, ArgPathHelperRegistry argHelpers,
            ProjectedKeyReads keys) {
        var call = row.call();
        var args = CodeBlock.join(call.arguments().stream()
            .map(a -> RoutineCallEmitter.envArgumentRead(a.segments(),
                CatalogRefs.typeName(a.javaTypeName()), argHelpers, keys))
            .toList(), ", ");
        return CodeBlock.of("$T.$L($L)", CatalogRefs.className(call.routinesClassName()),
            call.methodName(), args);
    }

    private static MethodSpec.Builder entryPoint(String methodName, TypeName declaredType) {
        return MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(FetcherResult.syncResultType(declaredType))
            .addParameter(ENV, "env");
    }

    private static TypeName declaredValueType(Errors errors, TypeName valueType) {
        return errors instanceof Errors.OutcomeWrapped o
            ? ErrorDispatchFragments.outcomeType(o.outcome(), valueType)
            : valueType;
    }

    /** The success return and the {@code catch} arm, closing the {@code try}. No tenant rides down. */
    private static MethodSpec closeEntryPoint(MethodSpec.Builder builder, Errors errors,
            TypeName declaredType, String payloadLocal) {
        var handDown = TenantHandDown.none();
        switch (errors) {
            case Errors.Redacting r -> {
                builder.addCode(FetcherResult.success(declaredType, payloadLocal, handDown));
                builder.nextControlFlow("catch ($T e)", Exception.class);
                builder.addCode(ErrorDispatchFragments.redactArm(r.errorRouter(), "e"));
            }
            case Errors.OutcomeWrapped o -> {
                builder.addCode(ErrorDispatchFragments.outcomeSuccessReturn(o.outcome(), declaredType,
                    payloadLocal, handDown));
                builder.nextControlFlow("catch ($T e)", Exception.class);
                builder.addCode(ErrorDispatchFragments.outcomeCatchArm(o.errorRouter(),
                    o.errorMappings(), o.mappingsConstantName(), declaredType, o.outcome()));
            }
        }
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * {@code <keysType> keys = dsl.transactionResult(tx -> DSL.using(tx).select(...).from(source)
     * [.coerce(...)].fetch[One]());}, the write half both seats emit.
     */
    private static CodeBlock captureTransaction(TypeName keysType, List<Column> capturedColumns,
            CodeBlock coerceTo, boolean isList) {
        var b = CodeBlock.builder()
            .add("$T keys = dsl.transactionResult(tx -> $T.using(tx)\n", keysType, DSL).indent()
            .add(".select($L)\n", CodeBlock.join(capturedColumns.stream()
                .map(c -> CodeBlock.of("source.$L", c.javaName())).toList(), ", "))
            .add(".from(source)\n");
        if (coerceTo != null) {
            b.add(".coerce($L)\n", coerceTo);
        }
        return b.add(isList ? ".fetch());\n" : ".fetchOne());\n").unindent().build();
    }

    /**
     * The single shape's empty capture: the routine returned no row, so the payload is null. The
     * many shape needs no guard, an empty {@code Result} being a value.
     */
    private static CodeBlock nullKeysGuard(TypeName declaredType, boolean isList) {
        return isList
            ? CodeBlock.of("")
            : CodeBlock.builder().add("if (keys == null) ").add(FetcherResult.nullData(declaredType)).build();
    }

    /**
     * One tail hop's {@code .join(...)}: on its foreign key's generated constant, or by name, each
     * pair compared through {@link ColumnComparison} so a converter on one side still compiles.
     */
    private static CodeBlock forwardJoin(Hop hop, String prevAlias) {
        return switch (hop.keying()) {
            case Keying.ForeignKey k -> CodeBlock.of(".join($L).onKey($T.$L)",
                hop.alias(), CatalogRefs.className(k.keysClassName()), k.constantName());
            case Keying.NameMatched ignored -> {
                var on = CodeBlock.builder();
                int i = 0;
                for (var pair : hop.pairs()) {
                    if (i > 0) on.add(".and(");
                    on.add("$L", ColumnComparison.equality(
                        CodeBlock.of("$L.$L", prevAlias, pair.source().javaName()),
                        CatalogRefs.decodeBindingType(pair.source().bindingType()),
                        CodeBlock.of("$L.$L", hop.alias(), pair.target().javaName()),
                        CatalogRefs.decodeBindingType(pair.target().bindingType())));
                    if (i > 0) on.add(")");
                    i++;
                }
                yield CodeBlock.of(".join($L).on($L)", hop.alias(), on.build());
            }
        };
    }

    /**
     * The re-read's key predicate: the anchor's target-side columns against the captured
     * source-side values. A {@code Result} of keys reads through {@code getValues} or a row-value
     * {@code IN}, a single record through {@code eq}; a composite key goes through
     * {@code DSL.row(...)}.
     */
    private static CodeBlock keysCondition(List<CodeBlock> conditionCols, List<CodeBlock> keyCols,
            boolean isList) {
        boolean single = conditionCols.size() == 1;
        if (isList) {
            if (single) {
                return CodeBlock.of("$L.in(keys.getValues($L))", conditionCols.getFirst(), keyCols.getFirst());
            }
            return CodeBlock.of("$T.row($L).in(keys.stream().map(r -> $T.row($L)).toList())",
                DSL, CodeBlock.join(conditionCols, ", "), DSL,
                CodeBlock.join(keyCols.stream().map(k -> CodeBlock.of("r.get($L)", k)).toList(), ", "));
        }
        if (single) {
            return CodeBlock.of("$L.eq(keys.value1())", conditionCols.getFirst());
        }
        return CodeBlock.of("$T.row($L).eq($T.row($L))",
            DSL, CodeBlock.join(conditionCols, ", "), DSL,
            CodeBlock.join(keyCols.stream().map(k -> CodeBlock.of("keys.get($L)", k)).toList(), ", "));
    }

    private static ClassName tableClass(Table table) {
        return CatalogRefs.className(table.tableClassName());
    }

    /** {@code <Table> <alias> = <Tables>.<TABLE>.as("<alias>");} */
    private static CodeBlock aliasDeclaration(Table table, String alias) {
        return CodeBlock.of("$T $L = $T.$L.as($S)", tableClass(table), alias,
            CatalogRefs.className(table.constantsClassName()), table.javaFieldName(), alias);
    }

    /** {@code Record<n><T1, ...>}: the type of a captured key tuple. */
    private static TypeName keyRecordType(List<Column> columns) {
        return ParameterizedTypeName.get(ClassName.get("org.jooq", "Record" + columns.size()),
            columns.stream().map(c -> CatalogRefs.decodeBindingType(c.bindingType()))
                .toArray(TypeName[]::new));
    }
}
