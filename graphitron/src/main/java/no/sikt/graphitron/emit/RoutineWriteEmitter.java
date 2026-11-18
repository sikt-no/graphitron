package no.sikt.graphitron.emit;

import no.sikt.graphitron.emit.RoutineWriteQuery.Column;
import no.sikt.graphitron.emit.RoutineWriteQuery.Hop;
import no.sikt.graphitron.emit.RoutineWriteQuery.Keying;
import no.sikt.graphitron.emit.RoutineWriteQuery.ObjectResult;
import no.sikt.graphitron.emit.RoutineWriteQuery.Pair;
import no.sikt.graphitron.emit.RoutineWriteQuery.RecordsResult;
import no.sikt.graphitron.emit.RoutineWriteQuery.Row;
import no.sikt.graphitron.emit.RoutineWriteQuery.Table;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.render.ArgPathHelperRegistry;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.render.ColumnComparison;
import no.sikt.graphitron.render.ProjectedKeyReads;
import no.sikt.graphitron.render.ProjectionCall;
import no.sikt.graphitron.render.RoutineCallEmitter;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits the body of one {@code @routine}-writing mutation coordinate, from that coordinate's row of
 * {@link RoutineWriteQuery}: {@code public static <V> <body>(DataFetchingEnvironment env, DSLContext dsl)}.
 * The fetcher graphql-java calls is {@link FrameEmitter}'s frame around it, which reaches the
 * connection the store states for the write, runs this body on it and routes what it throws; the
 * body holds the write and nothing of the frame.
 *
 * <p><b>The two-statements rule.</b> The write transaction holds the routine call and a projection
 * of the routine's own result columns, and nothing else. Anything past that capture is a
 * post-commit query under the caller's identity, and the two result shapes differ only there: a
 * {@link RecordsResult} re-reads from its first hop's table keyed by the captured values, joining the
 * remaining hops forward and projecting the type the path lands on; an {@link ObjectResult} returns
 * the captured keys, typed as the re-read table's key fields, and leaves the re-read to the field
 * under it that takes them. The routine never
 * appears in the follow-up {@code FROM}: re-invoking it would re-execute the write. An error in the
 * routine rolls back at the {@code transactionResult} boundary; an error in the follow-up cannot
 * undo the committed write.
 *
 * <p>Generated names (the body, the projection unit, the transaction provider) are the caller's, minted
 * where every other reference to them is.
 */
public final class RoutineWriteEmitter {

    private RoutineWriteEmitter() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");

    /**
     * Renders the body for {@code row}, answering what the write produces: the re-read's
     * {@code Result<Record>} or {@code Record} for a records result, the captured keys'
     * {@code Result<RecordN>} or {@code RecordN} for an object result, {@code null} where a single
     * capture finds no row. Its return type is the value type the frame declares.
     *
     * @param bodyName          the body's generated name, which the frame calls
     * @param returnTypeClass   the return type's projection unit; read for a records result only
     * @param selectionOccurrences the generated selection scaffold a records result's re-read
     *                          groups the fetched field's own fields through
     * @param argHelpers        the host class's collector of dotted-path descent helpers
     * @param keys              the method's node-id decodes, for an argument whose path projects a
     *                          key column
     * @param transactionProvider the emitted provider the write's transaction is opened through,
     *                          from {@link OwnedScope#provider}
     */
    public static MethodSpec render(Row row, String bodyName, ClassName returnTypeClass,
            ClassName selectionOccurrences, ArgPathHelperRegistry argHelpers, ProjectedKeyReads keys,
            ClassName transactionProvider) {
        var call = routineCall(row, argHelpers, keys);
        return switch (row) {
            case RecordsResult records -> recordsReRead(records, bodyName, returnTypeClass,
                selectionOccurrences, call, keys, transactionProvider);
            case ObjectResult object -> objectResultKeys(object, bodyName, call, keys, transactionProvider);
        };
    }

    private static MethodSpec recordsReRead(RecordsResult row, String bodyName, ClassName returnTypeClass,
            ClassName selectionOccurrences, CodeBlock call, ProjectedKeyReads keys, ClassName transactionProvider) {
        boolean isList = row.listReturn();
        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, RECORD) : RECORD;
        var builder = body(bodyName, valueType);
        var anchor = row.hops().getFirst();
        var tail = row.hops().subList(1, row.hops().size());

        // Node-id decodes go first, ahead of the routine call and the write's transaction: a
        // malformed id is a client error about an argument, raised before anything is written.
        builder.addCode(keys.declarations());
        builder.addStatement("$T source = $L", tableClass(row.call().resultTable()), call);
        for (var hop : row.hops()) {
            builder.addStatement(aliasDeclaration(hop.table(), hop.alias()));
        }

        var capturedColumns = anchor.pairs().stream().map(Pair::source).toList();
        TypeName keyRowType = keyRecordType(capturedColumns);
        builder.addCode(captureTransaction(
            isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType,
            capturedColumns, null, isList, transactionProvider));
        builder.addCode(nullKeysGuard(isList));

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

        return builder.addStatement("return payload").build();
    }

    private static MethodSpec objectResultKeys(ObjectResult row, String bodyName, CodeBlock call,
            ProjectedKeyReads keys, ClassName transactionProvider) {
        var reRead = row.reRead();
        boolean isList = reRead.listReturn();
        var targetKeyColumns = reRead.capturedPairs().stream().map(Pair::target).toList();
        TypeName keyRowType = keyRecordType(targetKeyColumns);
        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType;
        var builder = body(bodyName, valueType);

        builder.addCode(keys.declarations());
        builder.addStatement("$T source = $L", tableClass(row.call().resultTable()), call);

        // Re-typed under the re-read table's key fields, so the re-reading field reads them back by
        // field identity rather than by jOOQ's name-lookup fallback.
        var target = reRead.table();
        var coerceTo = CodeBlock.join(targetKeyColumns.stream()
            .map(c -> CodeBlock.of("$T.$L.$L", CatalogRefs.className(target.constantsClassName()),
                target.javaFieldName(), c.javaName()))
            .toList(), ", ");
        builder.addCode(captureTransaction(valueType,
            reRead.capturedPairs().stream().map(Pair::source).toList(), coerceTo, isList, transactionProvider));
        builder.addCode(nullKeysGuard(isList));

        return builder.addStatement("return keys").build();
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

    /** {@code public static <valueType> <bodyName>(DataFetchingEnvironment env, DSLContext dsl)}. */
    private static MethodSpec.Builder body(String bodyName, TypeName valueType) {
        return MethodSpec.methodBuilder(bodyName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(valueType)
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl")
            .addException(Exception.class);
    }

    /**
     * {@code <keysType> keys = <Provider>.owned(dsl).transactionResult(tx -> DSL.using(tx).select(...).from(source)
     * [.coerce(...)].fetch[One]());}, the write half both result shapes emit.
     */
    private static CodeBlock captureTransaction(TypeName keysType, List<Column> capturedColumns,
            CodeBlock coerceTo, boolean isList, ClassName transactionProvider) {
        var b = CodeBlock.builder()
            .add("$T keys = $L$T.using(tx)\n", keysType, OwnedScope.transactionResult(transactionProvider, "dsl"), DSL).indent()
            .add(".select($L)\n", CodeBlock.join(capturedColumns.stream()
                .map(c -> CodeBlock.of("source.$L", c.javaName())).toList(), ", "))
            .add(".from(source)\n");
        if (coerceTo != null) {
            b.add(".coerce($L)\n", coerceTo);
        }
        return b.add(isList ? ".fetch());\n" : ".fetchOne());\n").unindent().build();
    }

    /**
     * The single shape's empty capture: the routine returned no row, so the body answers null. The
     * many shape needs no guard, an empty {@code Result} being a value.
     */
    private static CodeBlock nullKeysGuard(boolean isList) {
        return isList ? CodeBlock.of("") : CodeBlock.of("if (keys == null) return null;\n");
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
