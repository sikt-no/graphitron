package no.sikt.graphitron.emit;

import no.sikt.graphitron.emit.RoutineWriteQuery.Column;
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
import no.sikt.graphitron.render.ProjectedKeyReads;
import no.sikt.graphitron.render.RoutineCallEmitter;

import javax.lang.model.element.Modifier;

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
 * {@link RecordsResult} hands the captured keys, typed as its anchor's key fields, to its lift,
 * {@link LiftEmitter#lift}, which reads the type the path lands on along the path from the anchor in
 * the order the routine delivered the keys; an {@link ObjectResult} returns the captured keys, typed
 * as the re-read table's key fields, and leaves the re-read to the field under it that takes them.
 * The routine never appears in the follow-up {@code FROM}: re-invoking it would re-execute the write.
 * An error in the routine rolls back at the {@code transactionResult} boundary; an error in the
 * follow-up cannot undo the committed write.
 *
 * <p>Generated names (the body, the lift, the transaction provider) are the caller's, minted where
 * every other reference to them is.
 */
public final class RoutineWriteEmitter {

    private RoutineWriteEmitter() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");

    /**
     * Renders the body for {@code row}, answering what the write produces: the lifted rows as a
     * {@code List<Record>}, or the one {@code Record}, for a records result, the captured keys'
     * {@code Result<RecordN>} or {@code RecordN} for an object result, {@code null} where a single
     * capture finds no row. Its return type is the value type the frame declares.
     *
     * @param bodyName          the body's generated name, which the frame calls
     * @param lift              the method {@link LiftEmitter#lift} rendered for a records result's
     *                          lift; read for a records result only
     * @param argHelpers        the host class's collector of dotted-path descent helpers
     * @param keys              the method's node-id decodes, for an argument whose path projects a
     *                          key column
     * @param transactionProvider the emitted provider the write's transaction is opened through,
     *                          from {@link OwnedScope#provider}
     */
    public static MethodSpec render(Row row, String bodyName, java.util.Optional<CallEmitter.Lift> lift,
            ArgPathHelperRegistry argHelpers, ProjectedKeyReads keys, ClassName transactionProvider) {
        var call = routineCall(row, argHelpers, keys);
        return switch (row) {
            case RecordsResult records -> recordsLift(records, bodyName, lift.orElseThrow(
                () -> new IllegalArgumentException("the records result at " + records.typeName() + "."
                    + records.fieldName() + " is rendered with no lift to hand its keys to")),
                call, keys, transactionProvider);
            case ObjectResult object -> objectResultKeys(object, bodyName, call, keys, transactionProvider);
        };
    }

    private static MethodSpec recordsLift(RecordsResult row, String bodyName, CallEmitter.Lift lift,
            CodeBlock call, ProjectedKeyReads keys, ClassName transactionProvider) {
        boolean isList = row.listReturn();
        TypeName valueType = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
        var builder = body(bodyName, valueType);
        var anchorKeyColumns = row.capturedPairs().stream().map(Pair::target).toList();
        TypeName keyRowType = keyRecordType(anchorKeyColumns);

        // Node-id decodes go first, ahead of the routine call and the write's transaction: a
        // malformed id is a client error about an argument, raised before anything is written.
        builder.addCode(keys.declarations());
        builder.addStatement("$T source = $L", tableClass(row.call().resultTable()), call);
        // Re-typed under the anchor's key fields, which the lift's derived source table reads them by.
        builder.addCode(captureTransaction(
            isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType,
            row.capturedPairs().stream().map(Pair::source).toList(),
            keyFields(row.anchor(), anchorKeyColumns), isList, transactionProvider));
        // The post-commit read: the lift reads the path from the anchor, keyed by the captured
        // values, in the order the routine delivered them.
        return builder.addStatement("return $T.$L(env, dsl, keys)", lift.host(), lift.method()).build();
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
        builder.addCode(captureTransaction(valueType,
            reRead.capturedPairs().stream().map(Pair::source).toList(),
            keyFields(reRead.table(), targetKeyColumns), isList, transactionProvider));
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
                CatalogRefs.decodeBindingType(a.javaTypeName()), argHelpers, keys))
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
     * .coerce(...).fetch[One]());}, the write half both result shapes emit, the keys re-typed under
     * {@code coerceTo}'s fields.
     *
     * <p>A list's keys are captured in the order the routine returned its rows: the function's table is
     * read {@code WITH ORDINALITY}, the one order a set-returning function defines, and the capture is
     * ordered by it, so the lift and every re-read keyed on the captured list keep that order.
     */
    private static CodeBlock captureTransaction(TypeName keysType, List<Column> capturedColumns,
            CodeBlock coerceTo, boolean isList, ClassName transactionProvider) {
        var b = CodeBlock.builder();
        String from = "source";
        if (isList) {
            // Unaliased: jOOQ aliases the numbered table itself, naming the ordinal column it reads back.
            b.addStatement("$T<$T> numbered = source.withOrdinality()", TABLE, RECORD);
            from = "numbered";
        }
        b.add("$T keys = $L$T.using(tx)\n", keysType, OwnedScope.transactionResult(transactionProvider, "dsl"), DSL).indent()
            .add(".select($L)\n", CodeBlock.join(capturedColumns.stream()
                .map(c -> isList
                    ? CodeBlock.of("numbered.field(source.$L)", c.javaName())
                    : CodeBlock.of("source.$L", c.javaName()))
                .toList(), ", "))
            .add(".from($L)\n", from);
        if (isList) {
            b.add(".orderBy(numbered.field($S, $T.class))\n", "ordinal", Long.class);
        }
        b.add(".coerce($L)\n", coerceTo);
        return b.add(isList ? ".fetch());\n" : ".fetchOne());\n").unindent().build();
    }

    /** {@code <Tables>.<TABLE>.<COLUMN>, ...}: {@code table}'s own fields for {@code columns}. */
    private static CodeBlock keyFields(Table table, List<Column> columns) {
        return CodeBlock.join(columns.stream()
            .map(c -> CodeBlock.of("$T.$L.$L", CatalogRefs.className(table.constantsClassName()),
                table.javaFieldName(), c.javaName()))
            .toList(), ", ");
    }

    /**
     * The single shape's empty capture: the routine returned no row, so the body answers null. The
     * many shape needs no guard, an empty {@code Result} being a value.
     */
    private static CodeBlock nullKeysGuard(boolean isList) {
        return isList ? CodeBlock.of("") : CodeBlock.of("if (keys == null) return null;\n");
    }

    private static ClassName tableClass(Table table) {
        return CatalogRefs.className(table.tableClassName());
    }

    /** {@code Record<n><T1, ...>}: the type of a captured key tuple. */
    private static TypeName keyRecordType(List<Column> columns) {
        return ParameterizedTypeName.get(ClassName.get("org.jooq", "Record" + columns.size()),
            columns.stream().map(c -> CatalogRefs.decodeBindingType(c.bindingType()))
                .toArray(TypeName[]::new));
    }
}
