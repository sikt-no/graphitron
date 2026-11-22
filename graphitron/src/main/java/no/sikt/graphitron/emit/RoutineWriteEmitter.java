package no.sikt.graphitron.emit;

import no.sikt.graphitron.emit.RoutineWriteQuery.Argument;
import no.sikt.graphitron.emit.RoutineWriteQuery.Column;
import no.sikt.graphitron.emit.RoutineWriteQuery.ObjectResult;
import no.sikt.graphitron.emit.RoutineWriteQuery.OrderKey;
import no.sikt.graphitron.emit.RoutineWriteQuery.Pair;
import no.sikt.graphitron.emit.RoutineWriteQuery.RecordsResult;
import no.sikt.graphitron.emit.RoutineWriteQuery.Row;
import no.sikt.graphitron.emit.RoutineWriteQuery.Rows;
import no.sikt.graphitron.emit.RoutineWriteQuery.Table;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.render.CatalogRefs;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * the order the keys were captured, the routine's delivered order or, for a plural identifying write,
 * the order stated on its result; an {@link ObjectResult} returns the captured keys, typed
 * as the re-read table's key fields, and leaves the re-read to the field under it that takes them.
 * The routine never appears in the follow-up {@code FROM}: re-invoking it would re-execute the write.
 * An error in the routine rolls back at the {@code transactionResult} boundary; an error in the
 * follow-up cannot undo the committed write.
 *
 * <p>The routine's arguments are the store's fills, {@link RoutineWriteQuery.Argument}: each read
 * off the request along the path the store states, null-safe through the input objects on the way, a
 * list into an array where the parameter takes one, and a node id decoded into the one key column the
 * parameter takes, all ahead of the call and the write's transaction.
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

    /** The body's own names, which a local named after a parameter must not shadow. */
    private static final Set<String> TAKEN = Set.of("env", "dsl", "source", "keys", "numbered", "tx");

    /**
     * The body and the helpers it calls, each node id's decode, which the caller adds to the class
     * beside the body.
     */
    public record Rendered(MethodSpec body, List<MethodSpec> helpers) {
        public Rendered {
            helpers = List.copyOf(helpers);
        }
    }

    /**
     * Renders the body for {@code row}, answering what the write produces: the lifted rows as a
     * {@code List<Record>}, or the one {@code Record}, for a records result, the captured keys'
     * {@code Result<RecordN>} or {@code RecordN} for an object result, {@code null} where a single
     * capture finds no row. Its return type is the value type the frame declares.
     *
     * @param rows              the rows {@code row} was read with, whose node id decodes its
     *                          arguments' are resolved against
     * @param bodyName          the body's generated name, which the frame calls
     * @param lift              the method {@link LiftEmitter#lift} rendered for a records result's
     *                          lift; read for a records result only
     * @param decode            the generated runtime a node id is decoded through
     * @param transactionProvider the emitted provider the write's transaction is opened through,
     *                          from {@link OwnedScope#provider}
     */
    public static Rendered render(Row row, Rows rows, String bodyName, Optional<CallEmitter.Lift> lift,
            NodeIdDecodeEmitter.Runtime decode, ClassName transactionProvider) {
        var reads = new ArrayList<CodeBlock>();
        var helpers = new ArrayList<MethodSpec>();
        var call = routineCall(row, rows, decode, reads, helpers);
        var body = switch (row) {
            case RecordsResult records -> recordsLift(records, bodyName, lift.orElseThrow(
                () -> new IllegalArgumentException("the records result at " + records.typeName() + "."
                    + records.fieldName() + " is rendered with no lift to hand its keys to")),
                call, reads, transactionProvider);
            case ObjectResult object -> objectResultKeys(object, bodyName, call, reads, transactionProvider);
        };
        return new Rendered(body, helpers);
    }

    private static MethodSpec recordsLift(RecordsResult row, String bodyName, CallEmitter.Lift lift,
            CodeBlock call, List<CodeBlock> reads, ClassName transactionProvider) {
        boolean isList = row.listReturn();
        TypeName valueType = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
        var builder = body(bodyName, valueType);
        var anchorKeyColumns = row.capturedPairs().stream().map(Pair::target).toList();
        TypeName keyRowType = keyRecordType(anchorKeyColumns);

        // The arguments go first, node-id decodes among them, ahead of the routine call and the
        // write's transaction: a malformed id is a client error about an argument, raised before
        // anything is written.
        reads.forEach(builder::addStatement);
        builder.addStatement("$T source = $L", tableClass(row.call().resultTable()), call);
        // Re-typed under the anchor's key fields, which the lift's derived source table reads them by.
        builder.addCode(captureTransaction(
            isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType,
            row.capturedPairs().stream().map(Pair::source).toList(),
            keyFields(row.anchor(), anchorKeyColumns), isList, row.statedOrder(), transactionProvider));
        // The post-commit read: the lift reads the path from the anchor, keyed by the captured
        // values, in the order they were captured.
        return builder.addStatement("return $T.$L(env, dsl, keys)", lift.host(), lift.method()).build();
    }

    private static MethodSpec objectResultKeys(ObjectResult row, String bodyName, CodeBlock call,
            List<CodeBlock> reads, ClassName transactionProvider) {
        var reRead = row.reRead();
        boolean isList = reRead.listReturn();
        var targetKeyColumns = reRead.capturedPairs().stream().map(Pair::target).toList();
        TypeName keyRowType = keyRecordType(targetKeyColumns);
        TypeName valueType = isList ? ParameterizedTypeName.get(RESULT, keyRowType) : keyRowType;
        var builder = body(bodyName, valueType);

        reads.forEach(builder::addStatement);
        builder.addStatement("$T source = $L", tableClass(row.call().resultTable()), call);

        // Re-typed under the re-read table's key fields, so the re-reading field reads them back by
        // field identity rather than by jOOQ's name-lookup fallback.
        builder.addCode(captureTransaction(valueType,
            reRead.capturedPairs().stream().map(Pair::source).toList(),
            keyFields(reRead.table(), targetKeyColumns), isList, List.of(), transactionProvider));
        builder.addCode(nullKeysGuard(isList));

        return builder.addStatement("return keys").build();
    }

    /**
     * {@code Routines.<method>(<args>)}, the value overload: a routine write is a mutation root's,
     * so every parameter binds a request value and none a previous node's column. Each argument is
     * read into a local first, added to {@code reads}, and a node id's decode helper to
     * {@code helpers}; the call names the locals.
     */
    private static CodeBlock routineCall(Row row, Rows rows, NodeIdDecodeEmitter.Runtime decode,
            List<CodeBlock> reads, List<MethodSpec> helpers) {
        var call = row.call();
        var args = new ArrayList<CodeBlock>();
        for (int position = 0; position < call.arguments().size(); position++) {
            var argument = call.arguments().get(position);
            var local = TAKEN.contains(argument.parameterName())
                ? argument.parameterName() + "Parameter" : argument.parameterName();
            reads.addAll(argumentRead(row, rows, argument, position, local, decode, helpers));
            args.add(CodeBlock.of("$L", local));
        }
        return CodeBlock.of("$T.$L($L)", CatalogRefs.className(call.routinesClassName()),
            call.methodName(), CodeBlock.join(args, ", "));
    }

    /**
     * The statements reading one argument into {@code local}: the value at the end of the stored path,
     * null wherever an input object on the way is absent; a list copied into an array of the
     * parameter's component type, in the list's order, graphql-java handing a list over as a
     * {@code java.util.List}, which the routine's array parameter does not take; or a node id read as
     * it arrives and decoded into the key column the parameter takes, a malformed id a client error.
     * A path read through a list above its last step, or a node id with no {@code KEY_VALUE} shape, is
     * a shape the store refuses, so meeting one is a generator bug.
     */
    private static List<CodeBlock> argumentRead(Row row, Rows rows, Argument argument, int position, String local,
            NodeIdDecodeEmitter.Runtime decode, List<MethodSpec> helpers) {
        var coordinate = row.typeName() + "." + row.fieldName();
        var path = argument.pathNames();
        if (path.isEmpty()) {
            throw new IllegalStateException("Graphitron generator bug (routine-write arguments): parameter "
                + argument.parameterName() + " of " + coordinate + " has no fill, which the build refuses");
        }
        if (argument.lists().stream().anyMatch(i -> i < path.size() - 1)) {
            throw new IllegalStateException("Graphitron generator bug (routine-write arguments): parameter "
                + argument.parameterName() + " of " + coordinate + " is read through a list along "
                + String.join(".", path) + ", which the build defers");
        }
        var type = CatalogRefs.decodeBindingType(argument.javaTypeName());
        if (argument.nodeId()) {
            if (argument.decode().isEmpty()) {
                throw new IllegalStateException("Graphitron generator bug (routine-write arguments): parameter "
                    + argument.parameterName() + " of " + coordinate + " takes a node id the store states no"
                    + " KEY_VALUE shape for, which the build refuses");
            }
            var keyValue = argument.decode().get();
            var decoded = new BuildQuery.Result.Decoded(List.of(rows.decodeFor(row, argument)), "KEY_VALUE",
                Optional.of(keyValue.columnJavaName()), argument.javaTypeName());
            var helper = "decode" + coordinate.replaceAll("[^A-Za-z0-9]", "_") + "_" + position;
            helpers.add(NodeIdDecodeEmitter.parameterHelper(helper, type, decoded, decode));
            return List.of(FillEmitter.argument(ClassName.get(Object.class), local + "Wire", path),
                CodeBlock.of("$T $L = $L($LWire)", type, local, helper, local));
        }
        if (type instanceof ArrayTypeName array) {
            var elements = ParameterizedTypeName.get(LIST, array.componentType());
            return List.of(FillEmitter.argument(elements, local + "Elements", path),
                CodeBlock.of("$T $L = $LElements == null ? null : $LElements.toArray(new $T[0])",
                    type, local, local, local, array.componentType()));
        }
        return List.of(FillEmitter.argument(type, local, path));
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
     * ordered by it, so the lift and every re-read keyed on the captured list keep that order. A plural
     * identifying write, whose answer owes the order of its input, is captured in the order
     * {@code statedOrder} states on the routine's own result instead, which is the author's statement
     * of the order that keeps the input's; the capture's position is then that order's.
     */
    private static CodeBlock captureTransaction(TypeName keysType, List<Column> capturedColumns,
            CodeBlock coerceTo, boolean isList, List<OrderKey> statedOrder,
            ClassName transactionProvider) {
        if (isList && !statedOrder.isEmpty()) {
            return CodeBlock.builder()
                .add("$T keys = $L$T.using(tx)\n", keysType,
                    OwnedScope.transactionResult(transactionProvider, "dsl"), DSL).indent()
                .add(".select($L)\n", CodeBlock.join(capturedColumns.stream()
                    .map(c -> CodeBlock.of("source.$L", c.javaName())).toList(), ", "))
                .add(".from(source)\n")
                .add(".orderBy($L)\n", CodeBlock.join(statedOrder.stream()
                    .map(k -> CodeBlock.of("source.$L.$L()", k.column().javaName(),
                        k.descending() ? "desc" : "asc"))
                    .toList(), ", "))
                .add(".coerce($L)\n", coerceTo)
                .add(".fetch());\n").unindent().build();
        }
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
