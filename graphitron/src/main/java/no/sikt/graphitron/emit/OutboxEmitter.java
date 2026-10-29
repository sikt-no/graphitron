package no.sikt.graphitron.emit;

import no.sikt.graphitron.emit.OutboxQuery.Leaf;
import no.sikt.graphitron.emit.OutboxQuery.LeafKind;
import no.sikt.graphitron.emit.OutboxQuery.Message;
import no.sikt.graphitron.emit.OutboxQuery.PayloadKind;
import no.sikt.graphitron.emit.OutboxQuery.Row;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.render.CatalogRefs;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Emits the companion method of one {@code @outbox}-carrying write coordinate, and the call to it,
 * from that coordinate's row of {@link OutboxQuery}:
 * {@code static void outbox<Field>(DSLContext tx, DataFetchingEnvironment env, <written> written)}.
 *
 * <p>The method writes the field's messages, one statement each in directive order, on the
 * transaction the write ran in. A message is an {@code INSERT ... SELECT} into the configured outbox
 * table: the event, the written table's name and the JSON payload built with jOOQ's JSON
 * constructors from the message's keys, the common payload's first. A key that reads a column of the
 * written table is read by the statement's own {@code SELECT ... FROM <written> WHERE <captured
 * key>}, so the outcome costs no statement of its own and the write's {@code RETURNING} is as it
 * was; a message with no such key selects bind values only. An argument the client left out is an
 * absent key, an explicit {@code null} is {@code null}.
 *
 * <p>The captured columns come from the seam that calls the method: each site of the write
 * captures the key its own re-read is keyed on, and the method reads the written rows back by that
 * same key. The method is package-private and static so a test in the fetchers' package calls it
 * directly.
 */
public final class OutboxEmitter {

    private OutboxEmitter() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName JOOQ_RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName JSON_ENTRY = ClassName.get("org.jooq", "JSONEntry");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName MAP = ClassName.get("java.util", "Map");

    /** The name of the helper that renders a Java value as a JSON-valued field. */
    public static final String JSON_VALUE_HELPER = "outboxJsonValue";

    /** The companion method's generated name: {@code outbox} and the field's name. */
    public static String methodName(Row row) {
        var name = row.fieldName();
        return "outbox" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * The call to the companion method, for the seam to place after the write inside its
     * transaction: {@code outbox<Field>(DSL.using(tx), env, <written>);}.
     */
    public static CodeBlock call(Row row, String txLocal, String writtenLocal) {
        return CodeBlock.builder()
            .addStatement("$L($T.using($L), env, $L)", methodName(row), DSL, txLocal, writtenLocal)
            .build();
    }

    /**
     * The helper every companion method of a fetchers class shares: a Java value, as an argument
     * map or list carries it, as a JSON-valued field.
     */
    public static MethodSpec jsonValueHelper() {
        var wildcardList = ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(Object.class));
        return MethodSpec.methodBuilder(JSON_VALUE_HELPER)
            .addModifiers(Modifier.STATIC)
            .returns(ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class)))
            .addParameter(Object.class, "value")
            .addJavadoc("A Java value, as an argument map or list carries it, as a JSON-valued field: a map\n"
                + "an object of its entries, a collection an array of its elements, an enum its name,\n"
                + "and a scalar itself.\n")
            .beginControlFlow("if (value instanceof $T<?, ?> map)", MAP)
            .addStatement("$T<$T<?>> entries = new $T<>()", LIST, JSON_ENTRY, ARRAY_LIST)
            .addStatement("map.forEach((k, v) -> entries.add($T.key($T.valueOf(k)).value($L(v))))",
                DSL, String.class, JSON_VALUE_HELPER)
            .addStatement("return $T.jsonbObject(entries)", DSL)
            .endControlFlow()
            .beginControlFlow("if (value instanceof $T<?> collection)", java.util.Collection.class)
            .addStatement("$T<$T<?>> items = new $T<>()", LIST, FIELD, ARRAY_LIST)
            .addStatement("collection.forEach(v -> items.add($L(v)))", JSON_VALUE_HELPER)
            .addStatement("return $T.jsonbArray(items)", DSL)
            .endControlFlow()
            .beginControlFlow("if (value instanceof $T<?> e)", Enum.class)
            .addStatement("return $T.val(e.name())", DSL)
            .endControlFlow()
            .beginControlFlow("if (value == null)")
            .addStatement("return $T.val(($T) null, $T.VARCHAR)", DSL, Object.class,
                ClassName.get("org.jooq.impl", "SQLDataType"))
            .endControlFlow()
            .beginControlFlow("if (value instanceof $T || value instanceof $T || value instanceof $T)",
                String.class, Number.class, Boolean.class)
            .addStatement("return $T.val(value)", DSL)
            .endControlFlow()
            .addStatement("return $T.val($T.valueOf(value))", DSL, String.class)
            .build();
    }

    /**
     * Renders the companion method for {@code row}.
     *
     * @param listResult      whether the write captured many rows: the written parameter is a
     *                        {@code Result} and a column or id leaf becomes an array
     * @param capturedColumns the written table's columns the write captured, by generated name: the
     *                        key the statements read the written rows back by
     * @param outputPackage   the package the node-id encoder is generated under
     * @param runtime         what the emitter takes from the generated runtime it may not name itself
     */
    public static MethodSpec render(Row row, boolean listResult, List<String> capturedColumns,
                                    String outputPackage, Runtime runtime) {
        Objects.requireNonNull(row, "row");
        if (capturedColumns.isEmpty()) {
            throw new IllegalStateException("the write at " + row.typeName() + "." + row.fieldName()
                + " captures no column, so its messages cannot read the written rows back");
        }
        var table = CatalogRefs.className(row.writtenTable().tableClassName());
        var constants = CatalogRefs.className(row.writtenTable().constantsClassName());
        TypeName writtenType = listResult
            ? ParameterizedTypeName.get(RESULT, WildcardTypeName.subtypeOf(JOOQ_RECORD))
            : JOOQ_RECORD;
        var method = MethodSpec.methodBuilder(methodName(row))
            .addModifiers(Modifier.STATIC)
            .addParameter(DSL_CONTEXT, "tx")
            .addParameter(ENV, "env")
            .addParameter(writtenType, "written")
            .addJavadoc("Writes the messages of {@code $L.$L}, one statement each in directive order, on the\n"
                + "transaction of the write that produced {@code written}. Nothing is written when\n"
                + "the write matched nothing.\n", row.typeName(), row.fieldName());
        method.addStatement(listResult ? "if (written.isEmpty()) return" : "if (written == null) return");
        method.addStatement("$T t = $T.$L", table, constants, row.writtenTable().javaFieldName());
        var body = CodeBlock.builder();
        var keyed = keyCondition(capturedColumns, listResult);
        body.addStatement("$T keyed = $L", CONDITION, keyed);

        var needsOperation = anyLeaf(row, l -> l.kind() == LeafKind.SCOPE && "operationName".equals(l.path()));
        var needsAlias = anyLeaf(row, l -> l.kind() == LeafKind.SCOPE && "alias".equals(l.path()));
        if (needsOperation) {
            body.addStatement("$T operationName = env.getOperationDefinition().getName()", String.class);
        }
        if (needsAlias) {
            body.addStatement("$T alias = env.getField().getAlias()", String.class);
        }
        for (var argument : argumentRoots(row)) {
            body.addStatement("$T $L = env.getArgument($S)", Object.class, argLocal(argument), argument);
        }
        for (var message : row.messages()) {
            body.add(message(row, message, listResult, capturedColumns, outputPackage, runtime));
        }
        method.beginControlFlow("try");
        method.addCode(body.build());
        method.nextControlFlow("catch ($T e)", RuntimeException.class);
        method.addStatement("throw new $T(e)", runtime.emitException());
        method.endControlFlow();
        return method.build();
    }

    /**
     * What the emitter takes from the generated runtime and may not name itself: the exception a
     * failing statement is wrapped in, and the read of one session handle member.
     */
    public record Runtime(ClassName emitException, SessionMemberRead sessionMember) {
        public Runtime {
            Objects.requireNonNull(emitException, "emitException");
            Objects.requireNonNull(sessionMember, "sessionMember");
        }
    }

    /**
     * The expression that reads one member of the session handle on the companion's own
     * {@code tx}, the connection the message's statement runs on, and fails where no handle was
     * mounted on it.
     */
    @FunctionalInterface
    public interface SessionMemberRead {
        CodeBlock read(String member, String coordinate);
    }

    private static boolean anyLeaf(Row row, java.util.function.Predicate<Leaf> test) {
        return row.messages().stream().flatMap(m -> m.leaves().stream()).anyMatch(test);
    }

    /** The distinct arguments the messages read, in first-read order. */
    private static List<String> argumentRoots(Row row) {
        var roots = new LinkedHashSet<String>();
        row.messages().stream().flatMap(m -> m.leaves().stream())
            .filter(l -> l.kind() == LeafKind.ARGUMENT)
            .forEach(l -> roots.add(l.segments().getFirst()));
        return List.copyOf(roots);
    }

    private static String argLocal(String argument) {
        return "arg_" + argument.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** The condition that reads the written rows back by the captured key. */
    private static CodeBlock keyCondition(List<String> columns, boolean listResult) {
        var conditionCols = columns.stream().map(c -> CodeBlock.of("t.$L", c)).toList();
        boolean single = columns.size() == 1;
        if (listResult) {
            if (single) {
                return CodeBlock.of("$L.in(written.getValues($L))", conditionCols.getFirst(),
                    conditionCols.getFirst());
            }
            return CodeBlock.of("$T.row($L).in(written.stream().map(r -> $T.row($L)).toList())",
                DSL, CodeBlock.join(conditionCols, ", "), DSL,
                CodeBlock.join(conditionCols.stream().map(c -> CodeBlock.of("r.get($L)", c)).toList(), ", "));
        }
        if (single) {
            return CodeBlock.of("$L.eq(written.get($L))", conditionCols.getFirst(), conditionCols.getFirst());
        }
        return CodeBlock.of("$T.row($L).eq($T.row($L))", DSL, CodeBlock.join(conditionCols, ", "), DSL,
            CodeBlock.join(conditionCols.stream().map(c -> CodeBlock.of("written.get($L)", c)).toList(), ", "));
    }

    /** The JSON constructor family a payload of this Java type takes: jsonb unless the type is json. */
    private static String jsonFamily(String payloadBindingType) {
        return payloadBindingType.endsWith(".JSON") ? "json" : "jsonb";
    }

    private static CodeBlock message(Row row, Message message, boolean listResult,
                                     List<String> capturedColumns, String outputPackage,
                                     Runtime runtime) {
        var destination = row.target();
        var family = jsonFamily(switch (destination) {
            case OutboxQuery.Target t -> t.payloadColumn().bindingType();
            case OutboxQuery.RoutineTarget r -> r.payloadBindingType();
        });
        var entries = "m" + message.ordinal();
        var b = CodeBlock.builder();
        b.addStatement("$T<$T<?>> $L = new $T<>()", LIST, JSON_ENTRY, entries, ARRAY_LIST);
        boolean readsColumns = false;
        int leafIndex = 0;
        for (var leaf : message.leaves()) {
            readsColumns |= leaf.kind() == LeafKind.RESULT_COLUMN;
            b.add(leaf(row, leaf, entries, "p" + message.ordinal() + "_" + leafIndex++, listResult,
                capturedColumns, outputPackage, runtime));
        }
        // The payload as the constructor builds it, cast to text where the destination takes text.
        CodeBlock payload = CodeBlock.of("$T.$LObject($L)", DSL, family, entries);
        var payloadKind = switch (destination) {
            case OutboxQuery.Target t -> t.payloadKind();
            case OutboxQuery.RoutineTarget r -> r.payloadKind();
        };
        if (payloadKind == PayloadKind.TEXT) {
            payload = CodeBlock.of("$L.cast($T.class)", payload, String.class);
        }
        if (destination instanceof OutboxQuery.RoutineTarget routine) {
            b.add(routineCall(row, message, routine, payload, readsColumns));
            return b.build();
        }
        var target = (OutboxQuery.Target) destination;
        var typeColumn = columnRef(target, target.typeColumn());
        var columns = new ArrayList<CodeBlock>();
        var values = new ArrayList<CodeBlock>();
        columns.add(typeColumn);
        values.add(CodeBlock.of("$T.val($S)", DSL, message.event()));
        target.aggregateTypeColumn().ifPresent(c -> {
            columns.add(columnRef(target, c));
            values.add(CodeBlock.of("$T.val($S)", DSL, row.writtenTable().sqlName()));
        });
        columns.add(columnRef(target, target.payloadColumn()));
        values.add(payload);
        var select = CodeBlock.of("$T.select($L)", DSL, CodeBlock.join(values, ", "));
        if (readsColumns) {
            select = CodeBlock.of("$L.from(t).where(keyed)", select);
        }
        b.addStatement("tx.insertInto($T.$L, $L).select($L).execute()",
            CatalogRefs.className(target.table().constantsClassName()), target.table().javaFieldName(),
            CodeBlock.join(columns, ", "), select);
        return b.build();
    }

    /**
     * A message written through the outbox routine: the payload is evaluated once, by a select of its
     * JSON constructor (from the written rows where a leaf reads a column of them), and the routine is
     * called with it and the event, the written table's name and no aggregate id, each argument at the
     * position of the parameter its role landed in. A written row the select cannot find passes
     * nothing, as the table form inserts nothing.
     */
    private static CodeBlock routineCall(Row row, Message message, OutboxQuery.RoutineTarget routine,
                                         CodeBlock payload, boolean readsColumns) {
        var b = CodeBlock.builder();
        var body = "body" + message.ordinal();
        TypeName bodyType = routine.payloadKind() == PayloadKind.TEXT ? ClassName.get(String.class)
            : ClassName.get("org.jooq", routine.payloadBindingType().endsWith(".JSON") ? "JSON" : "JSONB");
        var select = CodeBlock.of("tx.select($L)", payload);
        if (readsColumns) {
            select = CodeBlock.of("$L.from(t).where(keyed)", select);
        }
        b.addStatement("$T $L = $L.fetchOne(0, $T.class)", bodyType, body, select, bodyType);
        var arguments = new ArrayList<CodeBlock>();
        arguments.add(CodeBlock.of("tx.configuration()"));
        for (int position = 0; position < routine.parameterCount(); position++) {
            if (position == routine.typePosition()) {
                arguments.add(CodeBlock.of("$S", message.event()));
            } else if (routine.aggregateTypePosition().isPresent()
                    && position == routine.aggregateTypePosition().getAsInt()) {
                arguments.add(CodeBlock.of("$S", row.writtenTable().sqlName()));
            } else if (routine.aggregateIdPosition().isPresent()
                    && position == routine.aggregateIdPosition().getAsInt()) {
                arguments.add(CodeBlock.of("($T) null", String.class));
            } else {
                arguments.add(CodeBlock.of("$L", body));
            }
        }
        b.beginControlFlow("if ($L != null)", body)
            .addStatement("$T.$L($L)", CatalogRefs.className(routine.routinesClassName()),
                routine.methodName(), CodeBlock.join(arguments, ", "))
            .endControlFlow();
        return b.build();
    }

    private static CodeBlock columnRef(OutboxQuery.Target target, RoutineWriteQuery.Column column) {
        return CodeBlock.of("$T.$L.$L", CatalogRefs.className(target.table().constantsClassName()),
            target.table().javaFieldName(), column.javaName());
    }

    /** The statements that add one leaf's entry to the message's list. */
    private static CodeBlock leaf(Row row, Leaf leaf, String entries, String local, boolean listResult,
                                  List<String> capturedColumns, String outputPackage,
                                  Runtime runtime) {
        var key = CodeBlock.of("$T.key($S)", DSL, leaf.key());
        return switch (leaf.kind()) {
            case SCOPE -> scopeLeaf(row, leaf, entries, key);
            case SESSION -> CodeBlock.builder()
                .addStatement("$L.add($L.value($L($L)))", entries, key, JSON_VALUE_HELPER,
                    runtime.sessionMember().read(leaf.path(), row.typeName() + "." + row.fieldName()))
                .build();
            case ARGUMENT -> argumentLeaf(leaf, entries, key, local);
            case RESULT_COLUMN -> CodeBlock.builder()
                .addStatement("$L.add($L.value($L))", entries, key,
                    listResult ? CodeBlock.of("$T.jsonbArrayAgg(t.$L)", DSL, leaf.column().javaName())
                               : CodeBlock.of("t.$L", leaf.column().javaName()))
                .build();
            case RESULT_ROOT, RESULT_NODE_ID -> CodeBlock.builder()
                .addStatement("$L.add($L.value($L))", entries, key,
                    nodeIds(row, leaf, listResult, outputPackage))
                .build();
        };
    }

    private static CodeBlock scopeLeaf(Row row, Leaf leaf, String entries, CodeBlock key) {
        return switch (leaf.path()) {
            case "coordinate" -> CodeBlock.builder()
                .addStatement("$L.add($L.value($S))", entries, key, row.typeName() + "." + row.fieldName())
                .build();
            case "operationName" -> CodeBlock.builder()
                .beginControlFlow("if (operationName != null)")
                .addStatement("$L.add($L.value(operationName))", entries, key)
                .endControlFlow().build();
            case "alias" -> CodeBlock.builder()
                .beginControlFlow("if (alias != null)")
                .addStatement("$L.add($L.value(alias))", entries, key)
                .endControlFlow().build();
            default -> throw new IllegalStateException("the scope member '" + leaf.path()
                + "' of " + row.typeName() + "." + row.fieldName() + " is not one the store admits");
        };
    }

    /**
     * An argument leaf: the value the client sent at the path, absent where an argument or an input
     * field on the way was left out, {@code null} where the last was set to it. A value that is an
     * object or a list is rendered as the JSON it is.
     */
    private static CodeBlock argumentLeaf(Leaf leaf, String entries, CodeBlock key, String local) {
        var segments = leaf.segments();
        var root = argLocal(segments.getFirst());
        var b = CodeBlock.builder();
        if (segments.size() == 1) {
            b.beginControlFlow("if (env.containsArgument($S))", segments.getFirst())
                .addStatement("$L.add($L.value($L($L)))", entries, key, JSON_VALUE_HELPER, root)
                .endControlFlow();
            return b.build();
        }
        // arg instanceof Map<?, ?> p1 && p1.get("b") instanceof Map<?, ?> p2 && p2.containsKey("c")
        var condition = CodeBlock.builder();
        String current = root;
        for (int i = 1; i < segments.size(); i++) {
            String pattern = local + "_" + i;
            boolean last = i == segments.size() - 1;
            if (i == 1) {
                condition.add("$L instanceof $T<?, ?> $L", current, MAP, pattern);
            } else {
                condition.add(" && $L.get($S) instanceof $T<?, ?> $L", current, segments.get(i - 1), MAP, pattern);
            }
            if (last) {
                condition.add(" && $L.containsKey($S)", pattern, segments.get(i));
            }
            current = pattern;
        }
        b.beginControlFlow("if ($L)", condition.build())
            .addStatement("$L.add($L.value($L($L.get($S))))", entries, key, JSON_VALUE_HELPER, current,
                segments.getLast())
            .endControlFlow();
        return b.build();
    }

    /** The encoded node id(s) of the written rows: a value, or an array of them for a list result. */
    private static CodeBlock nodeIds(Row row, Leaf leaf, boolean listResult, String outputPackage) {
        var encoder = ClassName.get(outputPackage + ".util", "NodeIdEncoder");
        var columns = row.nodeColumns().get(leaf.nodeType());
        if (columns == null || columns.isEmpty()) {
            throw new IllegalStateException("the node type " + leaf.nodeType() + " read by "
                + row.typeName() + "." + row.fieldName() + " states no key column");
        }
        String method = "encode" + leaf.nodeType();
        if (listResult) {
            var args = CodeBlock.join(columns.stream()
                .map(c -> CodeBlock.of("r.get(t.$L)", c.javaName())).toList(), ", ");
            return CodeBlock.of("$T.jsonbArray(written.stream().map(r -> $T.val($T.$L($L))).toList())",
                DSL, DSL, encoder, method, args);
        }
        var args = CodeBlock.join(columns.stream()
            .map(c -> CodeBlock.of("written.get(t.$L)", c.javaName())).toList(), ", ");
        return CodeBlock.of("$T.val($T.$L($L))", DSL, encoder, method, args);
    }
}
