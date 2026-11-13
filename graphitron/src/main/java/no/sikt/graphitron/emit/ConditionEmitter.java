package no.sikt.graphitron.emit;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Emits a field occurrence's condition from one row of {@link ConditionQuery}: the predicate its
 * arguments and input fields write over the table it reads, as one method of the table and the
 * argument map.
 *
 * <pre>{@code
 * public static Condition filmsByFilter(Film table, Map<String, Object> args) {
 *     String title = DSL.val(args.get("filter") instanceof Map<?, ?> m1 ? m1.get("title") : null, table.TITLE).getValue();
 *     List<Short> lengths = args.get("lengths") instanceof List<?> l1
 *         ? l1.stream().map(e1 -> DSL.val(e1, table.LENGTH).getValue()).toList() : null;
 *     List<Condition> conditions = new ArrayList<>();
 *     if (title != null) conditions.add(table.TITLE.eq(title));
 *     if (lengths != null && !lengths.isEmpty()) conditions.add(table.LENGTH.in(lengths));
 *     return DSL.and(conditions);
 * }
 * }</pre>
 *
 * <p>Each value is read off the argument map through the input objects above it, {@code null}
 * wherever one is absent, and typed at its column through the column's own data type, so a converter
 * on the column runs and a wire value of another Java type (an {@code ID}'s string for an integer key)
 * is converted the way jOOQ binds it; an enum on a column of a Java enum is the constant of its name.
 * A scalar compares by equality and a list by membership. A value
 * that can be absent drops its predicate when it is, and a list drops it when it is empty too, so an
 * empty filter list filters nothing. A value every step of whose path is declared non-null is always
 * present and is compared unguarded.
 *
 * <p>A value whose path steps from the field's table to another compares at the table the path lands
 * on, inside a correlated {@code EXISTS}: the first step correlates to the condition's table, each later
 * step joins the one before on its key's columns and its condition method where it has them, so a
 * path to many rows filters without multiplying the field's own. Each step's table is aliased under
 * the table the condition is handed, so a condition applied inside another statement stays distinct.
 *
 * <p>A node id is decoded through a helper of its own into a record, refused as the client's error
 * where it is not an id of its type, and its key compared as a row value where it has several
 * columns: on the condition's own table where the id decodes onto it, its own key or the one foreign
 * key referencing the node, and otherwise on the table its path lands on.
 *
 * <p>A list of input objects compares each element as one row, its fields grouped under the list the
 * store says they are read through: the column, or the row of columns, a member of the elements' rows
 * where every element field is declared non-null below the list, and otherwise the disjunction over the
 * elements of the fields each gives, a field an element leaves out placing no constraint. Its fields
 * share one path, so a reached list compares inside one {@code EXISTS} over it.
 *
 * <p>A {@code @lookupKey} argument is the lookup's key, not a filter, and writes nothing here. What
 * this emitter does not write yet it says, and emits nothing for the occurrence: see {@link Reason}.
 * The method name is the caller's.
 */
public final class ConditionEmitter {

    private ConditionEmitter() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");

    /** The method's own names, and the pattern its read locals follow, which no term's local may take. */
    private static final Set<String> TAKEN = Set.of("table", "args", "conditions", "env", "dsl");
    private static final Pattern READ_LOCAL = Pattern.compile("[mles]\\d+");

    /** The producers whose arguments a condition filters: a read, its lookup and its count. */
    private static final Set<String> FILTERED = Set.of("SELECT", "LOOKUP", "COUNT");

    /** Why an occurrence's condition is not emitted here yet. */
    public enum Reason {
        /** The field runs a call or a write, which takes its arguments rather than filtering by them. */
        CONSUMED,
        /** A {@code @condition} takes an input object as a bean, built from the map before the call. */
        AUTHORED_BEAN,
        /**
         * A {@code @condition} is applied to the table a node id identifies, under an {@code EXISTS}
         * over the foreign key, or takes a node id's decoded key.
         */
        AUTHORED_NODE_ID,
        /** A {@code @condition} has a parameter nothing fills, its defect the store's. */
        AUTHORED_UNFILLED,
        /** A term reaches its column over a path the store states no steps for, or one calling a routine. */
        REACHED,
        /** A term is a node id the store cannot state a decode of into the table it lands on. */
        NODE_ID,
        /**
         * A term is a node id each participant of its field decodes as a node of its own, binding one
         * node table per participant, where an id of another participant's type answers no row on
         * that branch rather than a refusal. A node id naming one node type binds one table and is
         * decoded on every participant alike.
         */
        NODE_ID_PARTICIPANT,
        /**
         * A term is read through a list of input objects whose elements cannot be compared as rows: an
         * element field that is a list, under a second list, a node id or bound to no column, or element
         * fields reaching their columns by different paths; or a call's argument is read through one.
         */
        THROUGH_LIST,
        /** A term's column is of a Java type this emitter cannot spell. */
        UNSPELLED_TYPE
    }

    /** What the emitter makes of a row: the method, nothing to filter, or why it is not emitted yet. */
    public sealed interface Result {
        record Emitted(MethodSpec method, List<MethodSpec> helpers) implements Result {
            public Emitted {
                helpers = List.copyOf(helpers);
            }
        }
        record Nothing() implements Result {}
        record NotYet(Reason reason, String term) implements Result {}
    }

    /**
     * The row's condition as the method {@code name}, with the helpers that decode its node ids beside
     * it, or why it is not emitted.
     */
    public static Result emit(ConditionQuery.Row row, String name, NodeIdDecodeQuery decodes,
                              FillEmitter.Runtime runtime) {
        return emit(row, name, decodes, runtime, Map.of());
    }

    /**
     * The row's condition as the method {@code name}, its input-object parameters made into the beans
     * {@code builds} says how to make, keyed by the call, the position and the input object taken.
     */
    public static Result emit(ConditionQuery.Row row, String name, NodeIdDecodeQuery decodes,
                              FillEmitter.Runtime runtime, Map<BuildQuery.Root, BuildQuery.Result> builds) {
        if (row.producerKind() != null && !FILTERED.contains(row.producerKind())) {
            return new Result.NotYet(Reason.CONSUMED, row.coordinate());
        }
        var terms = row.terms().stream().filter(term -> !term.lookupKey()).toList();
        if (terms.isEmpty() && row.calls().isEmpty()) {
            return new Result.Nothing();
        }
        // A term read per element of a list compares with the other fields of its element, grouped
        // under the list the store says it is read through; one under two lists is not emitted.
        var plain = new ArrayList<ConditionQuery.Term>();
        var listed = new LinkedHashMap<String, List<ConditionQuery.Term>>();
        for (var term : terms) {
            if (term.lists().isEmpty()) {
                plain.add(term);
            } else if (term.lists().size() > 1) {
                return new Result.NotYet(Reason.THROUGH_LIST, term.coordinate());
            } else {
                listed.computeIfAbsent(term.lists().getFirst().coordinate(), k -> new ArrayList<>()).add(term);
            }
        }
        for (var term : plain) {
            var reason = reasonFor(row, term, decodes);
            if (reason.isPresent()) {
                return new Result.NotYet(reason.get(), term.coordinate());
            }
        }
        for (var group : listed.entrySet()) {
            var reason = reasonFor(row, group.getValue());
            if (reason.isPresent()) {
                return new Result.NotYet(reason.get(), group.getKey());
            }
        }
        for (var call : row.calls()) {
            var reason = reasonFor(call, builds);
            if (reason.isPresent()) {
                return new Result.NotYet(reason.get(), call.coordinate());
            }
        }
        var tableClass = ClassName.bestGuess(row.table().tableClassName());
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(tableClass, "table")
            .addParameter(ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class)), "args");
        // The request context and the session are not in the argument map: the method takes the
        // environment where a call reads a context key, and the executing context where one reads
        // the session, as today's glue does; the row says which, so its callers read the same.
        if (row.takesEnv()) {
            method.addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env");
        }
        if (row.takesDsl()) {
            method.addParameter(ClassName.get("org.jooq", "DSLContext"), "dsl");
        }
        var locals = new HashSet<String>();
        var counter = new int[1];
        var adds = CodeBlock.builder();
        var helpers = new java.util.ArrayList<MethodSpec>();
        for (var term : plain) {
            var local = local(term, locals);
            if (term.nodeId()) {
                var decode = decodeOnto(row, term, decodes).orElseThrow();
                var onOwn = decode.recordClass().equals(row.table().recordClassName());
                var hops = onOwn ? List.<String>of() : hops(method, term, local);
                var alias = hops.isEmpty() ? "table" : hops.getLast();
                var compare = nodeId(method, helpers, term, decode, name + "_" + local, local, alias, counter,
                    runtime.decode());
                var guard = term.list()
                    ? (term.present() ? CodeBlock.of("!$L.isEmpty()", local)
                        : CodeBlock.of("$L != null && !$L.isEmpty()", local, local))
                    : (term.present() ? null : CodeBlock.of("$L != null", local));
                var predicate = hops.isEmpty() ? compare : reached(term, hops, compare);
                adds.addStatement(guard == null ? CodeBlock.of("conditions.add($L)", predicate)
                    : CodeBlock.of("if ($L) conditions.add($L)", guard, predicate));
                continue;
            }
            var hops = hops(method, term, local);
            var alias = hops.isEmpty() ? "table" : hops.getLast();
            var column = CodeBlock.of("$L.$L", alias, term.column().javaName());
            var type = type(term.column().bindingType()).orElseThrow();
            var read = read(term.names(), counter);
            CodeBlock compare;
            CodeBlock guard;
            if (term.list()) {
                var l = "l" + ++counter[0];
                var e = "e" + ++counter[0];
                method.addStatement("$T $L = $L instanceof $T<?> $L ? $L.stream().map($L -> $L).toList() : null",
                    ParameterizedTypeName.get(LIST, type.box()), local, read, LIST, l, l, e,
                    typed(term, type, CodeBlock.of("$L", e), column, counter));
                compare = CodeBlock.of("$L.in($L)", column, local);
                guard = term.present() ? CodeBlock.of("!$L.isEmpty()", local)
                    : CodeBlock.of("$L != null && !$L.isEmpty()", local, local);
            } else {
                method.addStatement("$T $L = $L", type.box(), local, typed(term, type, read, column, counter));
                compare = CodeBlock.of("$L.eq($L)", column, local);
                guard = term.present() ? null : CodeBlock.of("$L != null", local);
            }
            var predicate = hops.isEmpty() ? compare : reached(term, hops, compare);
            if (guard == null) {
                adds.addStatement("conditions.add($L)", predicate);
            } else {
                adds.addStatement("if ($L) conditions.add($L)", guard, predicate);
            }
        }
        for (var elements : listed.values()) {
            list(method, adds, helpers, name, row, elements, locals, counter);
        }
        if (row.calls().stream().flatMap(call -> call.fills().stream())
                .anyMatch(fill -> "ARGUMENT".equals(fill.fill()) && !"ENUM_VALUE_OF".equals(fill.extraction())
                    && javaType(fill.parameterType()).orElseThrow() instanceof ParameterizedTypeName)) {
            method.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked").build());
        }
        for (var call : row.calls()) {
            var arguments = new ArrayList<CodeBlock>();
            for (var fill : call.fills()) {
                arguments.add(argument(method, helpers, name, call, fill, counter, runtime, row.coordinate(), builds));
            }
            adds.addStatement("conditions.add($T.$L($L))", ClassName.bestGuess(call.className()), call.methodName(),
                CodeBlock.join(arguments, ", "));
        }
        method.addStatement("$T<$T> conditions = new $T<>()", LIST, CONDITION, ARRAY_LIST);
        method.addCode(adds.build());
        method.addStatement("return $T.and(conditions)", DSL);
        return new Result.Emitted(method.build(), helpers);
    }

    /**
     * Why a list's element fields are not emitted yet: a field that is not one value bound to a column,
     * fields reaching their columns by different paths, or a path or type every term is held to.
     */
    private static Optional<Reason> reasonFor(ConditionQuery.Row row, List<ConditionQuery.Term> elements) {
        var first = elements.getFirst();
        for (var element : elements) {
            if (element.nodeId() || element.list() || element.column() == null
                    || !element.steps().equals(first.steps())) {
                return Optional.of(Reason.THROUGH_LIST);
            }
            boolean ownTable = element.column().tableSqlName().equals(row.table().sqlName());
            if (element.steps().isEmpty() ? !ownTable : element.steps().stream()
                    .anyMatch(step -> step.routine() || step.pairs().isEmpty() && step.method() == null)) {
                return Optional.of(Reason.REACHED);
            }
            if (type(element.column().bindingType()).isEmpty()) return Optional.of(Reason.UNSPELLED_TYPE);
        }
        return Optional.empty();
    }

    /**
     * A list of input objects' filter: each element compared as one row, by a helper of its own reading
     * the list off the argument map. Where every element field is declared non-null below the list,
     * the element is a row and the column, or the row of columns, is a member of the rows; a non-object
     * element is a row of nulls, matching nothing. Otherwise each element compares only the fields it
     * gives and the filter is their disjunction: an element giving none matches every row, a
     * non-object element none. Absent or empty, the list filters nothing. Its fields share one path,
     * so a reached list compares inside one {@code EXISTS} over it, a reached row matching a whole
     * element.
     */
    private static void list(MethodSpec.Builder method, CodeBlock.Builder adds, List<MethodSpec> helpers,
                             String name, ConditionQuery.Row row, List<ConditionQuery.Term> elements,
                             Set<String> locals, int[] counter) {
        var first = elements.getFirst();
        int at = first.lists().getFirst().position();
        var names = first.names().subList(0, at + 1);
        var local = fresh(names.getLast(), locals);
        var hops = hops(method, first, local);
        var alias = hops.isEmpty() ? "table" : hops.getLast();
        var tableClass = ClassName.bestGuess(hops.isEmpty() ? row.table().tableClassName()
            : first.steps().getLast().to().tableClassName());
        boolean whole = elements.stream().allMatch(element -> java.util.stream.IntStream
            .range(at + 1, element.names().size()).allMatch(element.path().nonNull()::contains));
        boolean present = java.util.stream.IntStream.rangeClosed(0, at).allMatch(first.path().nonNull()::contains);
        var helper = name + "_" + local;
        var wire = read(names, counter);
        CodeBlock compare;
        CodeBlock guard;
        if (whole) {
            var rows = rowsHelper(helper, tableClass, elements, at, counter);
            helpers.add(rows);
            method.addStatement("$T $L = $L($L, $L)", ParameterizedTypeName.get(LIST, elements.size() == 1
                ? type(first.column().bindingType()).orElseThrow().box() : ClassName.get("org.jooq", "RowN")),
                local, helper, wire, alias);
            compare = elements.size() == 1
                ? CodeBlock.of("$L.$L.in($L)", alias, first.column().javaName(), local)
                : CodeBlock.of("$T.row(($T<?>) $T.of($L)).in($L)", DSL, java.util.Collection.class, LIST,
                    columns(alias, elements), local);
            guard = present ? CodeBlock.of("!$L.isEmpty()", local)
                : CodeBlock.of("$L != null && !$L.isEmpty()", local, local);
        } else {
            helpers.add(matchHelper(helper, tableClass, elements, at, counter));
            method.addStatement("$T $L = $L($L, $L)", CONDITION, local, helper, wire, alias);
            compare = CodeBlock.of("$L", local);
            guard = CodeBlock.of("$L != null", local);
        }
        var predicate = hops.isEmpty() ? compare : reached(first, hops, compare);
        adds.addStatement("if ($L) conditions.add($L)", guard, predicate);
    }

    /** The element fields' columns on {@code alias}, in term order. */
    private static CodeBlock columns(String alias, List<ConditionQuery.Term> elements) {
        return CodeBlock.join(elements.stream()
            .map(element -> CodeBlock.of("$L.$L", alias, element.column().javaName())).toList(), ", ");
    }

    /**
     * The rows a list's elements are, each its fields' values typed at their columns, or the values
     * themselves where the element has one field; {@code null} where the wire value is no list.
     */
    private static MethodSpec rowsHelper(String name, ClassName tableClass, List<ConditionQuery.Term> elements,
                                         int at, int[] counter) {
        boolean single = elements.size() == 1;
        var first = elements.getFirst();
        TypeName row = single ? type(first.column().bindingType()).orElseThrow().box()
            : ClassName.get("org.jooq", "RowN");
        var returns = ParameterizedTypeName.get(LIST, row);
        var body = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(returns)
            .addParameter(Object.class, "wire")
            .addParameter(tableClass, "table")
            .addStatement("if (!(wire instanceof $T<?> elements)) return null", LIST)
            .addStatement("$T rows = new $T<>(elements.size())", returns, ARRAY_LIST)
            .beginControlFlow("for ($T element : elements)", Object.class);
        var nulls = single ? CodeBlock.of("null") : CodeBlock.of("$T.row(($T<?>) $T.of($L))", DSL,
            java.util.Collection.class, LIST, CodeBlock.join(elements.stream()
                .map(e -> CodeBlock.of("$T.val(null, table.$L)", DSL, e.column().javaName())).toList(), ", "));
        body.beginControlFlow("if (!(element instanceof $T<?, ?> fields))", MAP)
            .addStatement("rows.add($L)", nulls)
            .addStatement("continue")
            .endControlFlow();
        var cells = cells(body, elements, at, counter);
        body.addStatement("rows.add($L)", single ? CodeBlock.of("$L", cells.getFirst())
            : CodeBlock.of("$T.row(($T<?>) $T.of($L))", DSL, java.util.Collection.class, LIST,
                CodeBlock.join(java.util.stream.IntStream.range(0, elements.size())
                    .mapToObj(i -> CodeBlock.of("$T.val($L, table.$L)", DSL, cells.get(i),
                        elements.get(i).column().javaName())).toList(), ", ")));
        return body.endControlFlow().addStatement("return rows").build();
    }

    /**
     * The disjunction over a list's elements of the fields each gives, compared at their columns;
     * {@code null} where the wire value is no list or an empty one.
     */
    private static MethodSpec matchHelper(String name, ClassName tableClass, List<ConditionQuery.Term> elements,
                                          int at, int[] counter) {
        var conditions = ParameterizedTypeName.get(LIST, CONDITION);
        var body = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(Object.class, "wire")
            .addParameter(tableClass, "table")
            .addStatement("if (!(wire instanceof $T<?> elements) || elements.isEmpty()) return null", LIST)
            .addStatement("$T anyOf = new $T<>(elements.size())", conditions, ARRAY_LIST)
            .beginControlFlow("for ($T element : elements)", Object.class)
            .beginControlFlow("if (!(element instanceof $T<?, ?> fields))", MAP)
            .addStatement("anyOf.add($T.falseCondition())", DSL)
            .addStatement("continue")
            .endControlFlow();
        var cells = cells(body, elements, at, counter);
        body.addStatement("$T cells = new $T<>()", conditions, ARRAY_LIST);
        for (int i = 0; i < elements.size(); i++) {
            var element = elements.get(i);
            var compare = CodeBlock.of("cells.add(table.$L.eq($L))", element.column().javaName(), cells.get(i));
            boolean required = java.util.stream.IntStream.range(at + 1, element.names().size())
                .allMatch(element.path().nonNull()::contains);
            if (required) {
                body.addStatement(compare);
            } else {
                body.addStatement("if ($L != null) $L", cells.get(i), compare);
            }
        }
        body.addStatement("anyOf.add(cells.isEmpty() ? $T.trueCondition() : $T.and(cells))", DSL, DSL);
        return body.endControlFlow().addStatement("return $T.or(anyOf)", DSL).build();
    }

    /** Declares each element field's value off the element's map, typed at its column, and names them. */
    private static List<String> cells(MethodSpec.Builder body, List<ConditionQuery.Term> elements, int at,
                                      int[] counter) {
        var out = new ArrayList<String>();
        for (var element : elements) {
            var cell = "c" + (out.size() + 1);
            var type = type(element.column().bindingType()).orElseThrow();
            var column = CodeBlock.of("table.$L", element.column().javaName());
            var value = read("fields", element.names().subList(at + 1, element.names().size()), counter);
            body.addStatement("$T $L = $L", type.box(), cell, typed(element, type, value, column, counter));
            out.add(cell);
        }
        return out;
    }

    /** A local not yet taken, from {@code base}. */
    private static String fresh(String base, Set<String> locals) {
        var unique = free(base, locals) ? base : base + "Value";
        for (int n = 2; !free(unique, locals); n++) unique = base + "Value" + n;
        locals.add(unique);
        return unique;
    }

    /**
     * Why an authored call is not emitted yet: a fill it takes that this emitter does not hand over, or
     * a table it is applied to by way of a node id.
     */
    private static Optional<Reason> reasonFor(ConditionQuery.Call call, Map<BuildQuery.Root, BuildQuery.Result> builds) {
        if ("NODE_ID".equals(call.bindingKind())) return Optional.of(Reason.AUTHORED_NODE_ID);
        for (var fill : call.fills()) {
            switch (fill.fill()) {
                case "TABLE" -> { }
                case "ARGUMENT" -> {
                    if (fill.landingNodeId()) return Optional.of(Reason.AUTHORED_NODE_ID);
                    if (fill.throughList()) {
                        return Optional.of(Reason.THROUGH_LIST);
                    }
                    if ("INPUT_OBJECT".equals(fill.landingKind()) && !takesMap(fill)
                            && !(builds.get(new BuildQuery.Root(call.coordinate(), fill.position(), fill.landing()))
                                instanceof BuildQuery.Result.Built)) {
                        return Optional.of(Reason.AUTHORED_BEAN);
                    }
                    if (javaType(fill.parameterType()).isEmpty()) return Optional.of(Reason.UNSPELLED_TYPE);
                    if ("ENUM_VALUE_OF".equals(fill.extraction()) && fill.path().lists().contains(fill.path().names().size() - 1)) {
                        return Optional.of(Reason.UNSPELLED_TYPE);
                    }
                }
                case "UNFILLED" -> {
                    return Optional.of(Reason.AUTHORED_UNFILLED);
                }
                case "CONTEXT_KEY", "SESSION", "SESSION_MEMBER" -> {
                    if (javaType(fill.parameterType()).isEmpty()) return Optional.of(Reason.UNSPELLED_TYPE);
                }
                default -> {
                    return Optional.of(Reason.UNSPELLED_TYPE);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether an input object's parameter takes the map graphql-java delivers rather than a bean: the
     * class it delivers, its list peeled off, is a map or any object.
     */
    private static boolean takesMap(ConditionQuery.Fill fill) {
        return "java.util.Map".equals(fill.element()) || "java.lang.Object".equals(fill.element());
    }

    /**
     * The value one parameter of an authored call takes: the table the condition is applied to, or an
     * argument's value off the map, cast to the parameter's declared type, an enum by its constant's name.
     */
    private static CodeBlock argument(MethodSpec.Builder method, List<MethodSpec> helpers, String name,
                                      ConditionQuery.Call call, ConditionQuery.Fill fill, int[] counter,
                                      FillEmitter.Runtime runtime, String coordinate,
                                      Map<BuildQuery.Root, BuildQuery.Result> builds) {
        switch (fill.fill()) {
            case "TABLE" -> {
                return CodeBlock.of("table");
            }
            case "CONTEXT_KEY" -> {
                var type = javaType(fill.parameterType()).orElseThrow();
                return CodeBlock.of("($T) $L", type.isPrimitive() ? type.box() : type,
                    runtime.contextArgument().apply(fill.contextKey()));
            }
            case "SESSION" -> {
                return runtime.sessionHandle().apply(coordinate);
            }
            case "SESSION_MEMBER" -> {
                return runtime.sessionMember().apply(fill.sessionMember(), coordinate);
            }
            default -> { }
        }
        var type = javaType(fill.parameterType()).orElseThrow();
        var read = read(fill.path().names(), counter);
        // An input object a bean takes: the map made into it by a helper of its own, before the call.
        if (builds.get(new BuildQuery.Root(call.coordinate(), fill.position(), fill.landing()))
                instanceof BuildQuery.Result.Built made && !takesMap(fill)) {
            var local = "b" + ++counter[0];
            var helper = name + "_build" + counter[0];
            helpers.addAll(BuildEmitter.helpers(made.build(), helper, runtime.decode()));
            method.addStatement("$T $LWire = $L", Object.class, local, read);
            method.addStatement(FillEmitter.made(type, local, helper, helpers));
            return CodeBlock.of("$L", local);
        }
        if ("ENUM_VALUE_OF".equals(fill.extraction())) {
            var spelled = "s" + ++counter[0];
            return CodeBlock.of("$L instanceof $T $L ? $T.valueOf($L) : null", read, String.class, spelled, type,
                spelled);
        }
        return CodeBlock.of("($T) $L", type.isPrimitive() ? type.box() : type, read);
    }

    /** A declared Java type as the census spells it, decoded as every emitter decodes it; empty where it cannot be. */
    static Optional<TypeName> javaType(String spelled) {
        try {
            return Optional.of(SessionIdentityRows.typeOf(spelled));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Reason> reasonFor(ConditionQuery.Row row, ConditionQuery.Term term,
                                              NodeIdDecodeQuery decodes) {
        if (term.nodeId()) {
            if (term.nodeIdBindings() > 1) return Optional.of(Reason.NODE_ID_PARTICIPANT);
            if (decodeOnto(row, term, decodes).isEmpty()) return Optional.of(Reason.NODE_ID);
            return Optional.empty();
        }
        boolean ownTable = term.column().tableSqlName().equals(row.table().sqlName());
        if (term.steps().isEmpty() ? !ownTable : term.steps().stream()
                .anyMatch(step -> step.routine() || step.pairs().isEmpty() && step.method() == null)) {
            return Optional.of(Reason.REACHED);
        }
        if (type(term.column().bindingType()).isEmpty()) return Optional.of(Reason.UNSPELLED_TYPE);
        return Optional.empty();
    }

    /**
     * Where a node id term's id decodes to: onto the condition's own table where the store states that
     * decode, the node's own key there or the one foreign key referencing it, so an id whose path is a
     * single step over that key compares on the table itself; otherwise onto the table its path lands
     * on, reached over the path's steps. Empty where neither decode is stated, or the path is longer
     * than a step and lands on no table.
     */
    private static Optional<NodeIdDecodeQuery.Decode> decodeOnto(ConditionQuery.Row row, ConditionQuery.Term term,
                                                                 NodeIdDecodeQuery decodes) {
        if (term.steps().size() <= 1 && term.steps().stream().allMatch(step -> step.method() == null && !step.routine())
                && decodes.bounds(term.coordinate()).size() == 1
                && decodes.resolve(term.coordinate(), decodes.bounds(term.coordinate()).getFirst(),
                        Optional.of(row.table().recordClassName()))
                    instanceof NodeIdDecodeQuery.Resolution.Resolved own) {
            return Optional.of(own.decode());
        }
        if (term.steps().isEmpty() || term.landingRecordClass() == null
                || term.steps().stream().anyMatch(step -> step.routine() || step.pairs().isEmpty() && step.method() == null)) {
            return Optional.empty();
        }
        if (decodes.bounds(term.coordinate()).size() != 1) {
            return Optional.empty();
        }
        return decodes.resolve(term.coordinate(), decodes.bounds(term.coordinate()).getFirst(),
                Optional.of(term.landingRecordClass()))
            instanceof NodeIdDecodeQuery.Resolution.Resolved landed ? Optional.of(landed.decode()) : Optional.empty();
    }

    /**
     * A node id term: its id decoded, through a helper of its own, into a record of the table its path
     * lands on, or a list of them, whose key columns are compared with the same columns of that table:
     * by equality or membership, as a row value where the key has several columns. An id the decode
     * refuses is a client error; a null element of a list compares as a key of nulls, matching nothing.
     */
    private static CodeBlock nodeId(MethodSpec.Builder method, List<MethodSpec> helpers, ConditionQuery.Term term,
                                    NodeIdDecodeQuery.Decode decode, String helper, String local, String alias,
                                    int[] counter, NodeIdDecodeEmitter.Runtime runtime) {
        var record = SessionIdentityRows.classOf(decode.recordClass());
        var type = term.list() ? ParameterizedTypeName.get(LIST, record) : record;
        helpers.add(NodeIdDecodeEmitter.parameterHelper(helper, type,
            new BuildQuery.Result.Decoded(List.of(decode), "RECORD", Optional.empty(),
                term.list() ? "java.util.List" : decode.recordClass()),
            runtime));
        method.addStatement("$T $L = $L($L)", type, local, helper, read(term.names(), counter));
        var tables = SessionIdentityRows.classOf(decode.tablesClass());
        var keys = decode.columnJavaNames().stream()
            .map(c -> CodeBlock.of("$T.$L.$L", tables, decode.tableField(), c)).toList();
        var columns = CodeBlock.join(decode.columnJavaNames().stream()
            .map(c -> CodeBlock.of("$L.$L", alias, c)).toList(), ", ");
        boolean single = keys.size() == 1;
        if (!term.list()) {
            return single
                ? CodeBlock.of("$L.eq($L.get($L))", columns, local, keys.getFirst())
                : CodeBlock.of("$T.row($L).eq($T.row($L))", DSL, columns, DSL,
                    CodeBlock.join(keys.stream().map(k -> CodeBlock.of("$L.get($L)", local, k)).toList(), ", "));
        }
        var e = "e" + ++counter[0];
        if (single) {
            return CodeBlock.of("$L.in($L.stream().map($L -> $L == null ? null : $L.get($L)).toList())",
                columns, local, e, e, e, keys.getFirst());
        }
        return CodeBlock.of("$T.row($L).in($L.stream().map($L -> $L == null ? new $T() : $L).map($L -> $T.row($L)).toList())",
            DSL, columns, local, e, e, record, e, e, DSL,
            CodeBlock.join(keys.stream().map(k -> CodeBlock.of("$L.get($L)", e, k)).toList(), ", "));
    }

    /**
     * Declares the table each step of a reached term's path lands on, aliased under the table the
     * condition is handed so a condition applied inside another statement's alias stays distinct, and
     * returns their locals in step order; none for a term on the field's own table.
     */
    private static List<String> hops(MethodSpec.Builder method, ConditionQuery.Term term, String local) {
        var hops = new java.util.ArrayList<String>();
        for (var step : term.steps()) {
            var hop = local + "Hop" + step.position();
            method.addStatement("$T $L = $T.$L.as(table.getName() + $S)", ClassName.bestGuess(step.to().tableClassName()),
                hop, ClassName.bestGuess(step.to().constantsClassName()), step.to().javaFieldName(),
                "_" + local + "_" + step.position());
            hops.add(hop);
        }
        return hops;
    }

    /**
     * A predicate on the last table of a term's path, as a correlated {@code EXISTS} from the condition's
     * table: the first step correlates to it, each later step joins the one before, on its column pairs
     * and its condition method where it has them.
     */
    private static CodeBlock reached(ConditionQuery.Term term, List<String> hops, CodeBlock compare) {
        var steps = term.steps();
        var select = CodeBlock.builder().add("$T.exists($T.selectOne().from($L)", DSL, DSL, hops.getFirst());
        for (int i = 1; i < steps.size(); i++) {
            select.add(".join($L).on($L)", hops.get(i), on(steps.get(i), hops.get(i - 1), hops.get(i)));
        }
        select.add(".where($L).and($L))", on(steps.getFirst(), "table", hops.getFirst()), compare);
        return select.build();
    }

    /** What a step joins on: its column pairs and its condition method, from the table before to its own. */
    private static CodeBlock on(ConditionQuery.Step step, String from, String to) {
        var parts = new java.util.ArrayList<CodeBlock>();
        for (var pair : step.pairs()) {
            parts.add(CodeBlock.of("$L.$L.eq($L.$L)", from, pair.fromJavaName(), to, pair.toJavaName()));
        }
        if (step.method() != null) {
            parts.add(CodeBlock.of("$T.$L($L, $L)", ClassName.bestGuess(step.method().className()),
                step.method().methodName(), from, to));
        }
        if (parts.size() == 1) return parts.getFirst();
        return CodeBlock.of("$T.and($L)", DSL, CodeBlock.join(parts, ", "));
    }

    /**
     * The wire value {@code value} as the column's Java type: an enum on a column of a Java enum by the
     * constant of its name, graphql-java handing an enum value over as its name, and anything else
     * through the column's own data type.
     */
    private static CodeBlock typed(ConditionQuery.Term term, TypeName type, CodeBlock value, CodeBlock column,
                                   int[] counter) {
        if ("ENUM".equals(term.typeKind()) && !"java.lang.String".equals(term.column().bindingType())) {
            var name = "s" + ++counter[0];
            return CodeBlock.of("$L instanceof $T $L ? $T.valueOf($L) : null", value, String.class, name, type, name);
        }
        return CodeBlock.of("$T.val($L, $L).getValue()", DSL, value, column);
    }

    /**
     * The value at the end of {@code names} off the argument map, through the input objects above it,
     * {@code null} wherever one is absent.
     */
    private static CodeBlock read(List<String> names, int[] counter) {
        return read("args", names, counter);
    }

    /** The value at the end of {@code names} off the map {@code root}, as {@link #read(List, int[])}. */
    private static CodeBlock read(String root, List<String> names, int[] counter) {
        if (names.size() == 1) {
            return CodeBlock.of("$L.get($S)", root, names.getFirst());
        }
        var test = CodeBlock.builder();
        String previous = null;
        for (int i = 0; i < names.size() - 1; i++) {
            var map = "m" + ++counter[0];
            var source = previous == null ? CodeBlock.of("$L.get($S)", root, names.get(i))
                : CodeBlock.of("$L.get($S)", previous, names.get(i));
            if (i > 0) test.add(" && ");
            test.add("$L instanceof $T<?, ?> $L", source, MAP, map);
            previous = map;
        }
        return CodeBlock.of("($L ? $L.get($S) : null)", test.build(), previous, names.getLast());
    }

    /** The local a term's value is held in: its own name, its parents' prepended while that is taken. */
    private static String local(ConditionQuery.Term term, Set<String> locals) {
        var names = term.names();
        var name = names.getLast();
        for (int i = names.size() - 2; i >= 0 && !free(name, locals); i--) {
            name = names.get(i) + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        var unique = free(name, locals) ? name : name + "Value";
        for (int n = 2; !free(unique, locals); n++) unique = name + "Value" + n;
        locals.add(unique);
        return unique;
    }

    private static boolean free(String name, Set<String> locals) {
        return !TAKEN.contains(name) && !READ_LOCAL.matcher(name).matches() && !locals.contains(name);
    }

    /** The Java type a column's binding type names, where it can be spelled. */
    private static Optional<TypeName> type(String bindingType) {
        try {
            if (bindingType.endsWith("[]")) {
                var element = bindingType.substring(0, bindingType.length() - 2);
                return Optional.of(ArrayTypeName.of("byte".equals(element) ? TypeName.BYTE : ClassName.bestGuess(element)));
            }
            return Optional.of(ClassName.bestGuess(bindingType));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
