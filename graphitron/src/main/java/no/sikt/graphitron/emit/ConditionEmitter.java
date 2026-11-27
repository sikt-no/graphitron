package no.sikt.graphitron.emit;

import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.render.TableAliasFragments;
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
 *     String title = ColumnValues.fromWire(table.TITLE.getDataType(), args.get("filter") instanceof Map<?, ?> m1 ? m1.get("title") : null);
 *     List<Short> lengths = args.get("lengths") instanceof List<?> l1
 *         ? l1.stream().map(e1 -> ColumnValues.fromWire(table.LENGTH.getDataType(), e1)).toList() : null;
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
 * key referencing the node, and otherwise on the table its path lands on. A node id each participant
 * of the field decodes as a node of its own is dispatched by the type id: each participant decodes
 * only the ids carrying its own type id, and an id of another participant's type answers no row there.
 *
 * <p>A list of input objects compares each element as one row, its fields grouped under the list the
 * store says they are read through: the column, or the row of columns, a member of the elements' rows
 * where every element field is declared non-null below the list, and otherwise the disjunction over the
 * elements of the fields each gives, a field an element leaves out placing no constraint. Its fields
 * share one path, so a reached list compares inside one {@code EXISTS} over it.
 *
 * <p>A {@code @condition} a node id applies to the table it identifies is called on that table,
 * aliased per call, inside a correlated {@code EXISTS} over the node id's join path from the
 * condition's table, and only where the node id is present. A parameter a node id fills takes the
 * decoded key in the shape the store states at it: the node's record, a {@code RecordN} or
 * {@code RowN} of the key, or the one key column a pair projects; {@code null} where the id is absent.
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

    /**
     * Why an occurrence's condition is not emitted here, each the shape of a stored defect: an accepted
     * graph meets none, so one met when the glue is rendered is a generator bug, which names the codes
     * that should have refused it.
     */
    public enum Reason {
        /** A {@code @condition} takes an input object as a bean the store states no way to build. */
        AUTHORED_BEAN("CONDITION_PARAMETER_RECORD", "BUILT_CLASS_UNBUILDABLE", "MEMBER_UNKNOWN", "MEMBER_UNFILLED",
            "MEMBER_FILLED_TWICE", "GROUPING_LIST"),
        /**
         * A {@code @condition} is applied to a node id's table the store states no join path to, or
         * takes a node id's decoded key in no one shape the store states at the parameter, or read
         * through a list.
         */
        AUTHORED_NODE_ID("NODE_ID_LANDS_ON_TABLE", "NODE_ID_SLOT_SHAPE_MISMATCH", "NODE_ID_KEY_ARITY_EXCEEDS_SLOT",
            "NODE_ID_CONTAINER_NOT_AT_A_SLOT", "NODE_ID_TABLE_WITHOUT_RECORD", "LIST_CONDITION_BELOW_LIST"),
        /** A {@code @condition} has a parameter nothing fills. */
        AUTHORED_UNFILLED("PARAMETER_UNFILLED", "PARAMETER_NAMES_MISSING"),
        /** A term's column is on another table than the row's, with no join path stated to it. */
        REACHED("NO_ROUTE_TO_TARGET"),
        /**
         * A term is a node id the store cannot state a decode of on the row's table: no node of the id
         * on it, no path from it landing on the id's one node, or a node table without a record.
         */
        NODE_ID("NODE_ID_LANDS_ON_TABLE", "NODE_ID_PARTICIPANT_WITHOUT_NODE", "NODE_ID_CONTAINER_NOT_AT_A_SLOT",
            "NODE_ID_TABLE_WITHOUT_RECORD"),
        /**
         * A term is read through a list of input objects whose elements cannot be compared as rows: an
         * element field that is a list, under a second list, a node id or bound to no column, or element
         * fields reaching their columns by different paths; or a call's argument is read through one.
         */
        THROUGH_LIST("LIST_ELEMENT_NODE_ID", "LIST_ELEMENT_NOT_ONE_VALUE", "LIST_ELEMENT_PATHS_DIFFER",
            "LIST_CONDITION_BELOW_LIST"),
        /** A parameter is of a Java type this emitter cannot spell. */
        UNSPELLED_TYPE("PARAMETER_MISMATCH", "PARAMETER_TYPE_UNSPELLED");

        private final List<String> refusedBy;

        Reason(String... refusedBy) {
            this.refusedBy = List.of(refusedBy);
        }

        /** The stored defect codes that refuse a graph holding this shape. */
        public List<String> refusedBy() {
            return refusedBy;
        }
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
     * it, or why it is not emitted. {@code tableAliases} is the generated scaffold that names the
     * tables a path reaches ({@link TableAliasFragments}).
     */
    public static Result emit(ConditionQuery.Row row, String name, NodeIdDecodeQuery decodes,
                              FillEmitter.Runtime runtime, ClassName tableAliases) {
        return emit(row, name, decodes, runtime, tableAliases, Map.of());
    }

    /**
     * The row's condition as the method {@code name}, its input-object parameters made into the beans
     * {@code builds} says how to make, keyed by the call, the position and the input object taken.
     */
    public static Result emit(ConditionQuery.Row row, String name, NodeIdDecodeQuery decodes,
                              FillEmitter.Runtime runtime, ClassName tableAliases,
                              Map<BuildQuery.Root, BuildQuery.Result> builds) {
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
            var reason = reasonFor(call, builds, decodes);
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
        var columnValues = columnValues(runtime);
        var locals = new HashSet<String>();
        var counter = new int[1];
        var adds = CodeBlock.builder();
        var helpers = new java.util.ArrayList<MethodSpec>();
        for (var term : plain) {
            var local = local(term, locals);
            if (term.nodeIdBindings() > 1) {
                participant(method, adds, helpers, term, participantDecode(row, term, decodes).orElseThrow(),
                    name + "_" + local, local, locals, counter, runtime.decode());
                continue;
            }
            if (term.nodeId()) {
                var onto = decodeOnto(row, term, decodes).orElseThrow();
                var decode = onto.decode();
                var hops = onto.own() ? List.<String>of() : hops(method, term.steps(), local, tableAliases);
                var alias = hops.isEmpty() ? "table" : hops.getLast();
                var compare = nodeId(method, helpers, term, decode, name + "_" + local, local, alias, counter,
                    runtime.decode());
                var guard = term.list()
                    ? (term.present() ? CodeBlock.of("!$L.isEmpty()", local)
                        : CodeBlock.of("$L != null && !$L.isEmpty()", local, local))
                    : (term.present() ? null : CodeBlock.of("$L != null", local));
                var predicate = hops.isEmpty() ? compare : reached(term.steps(), hops, compare);
                adds.addStatement(guard == null ? CodeBlock.of("conditions.add($L)", predicate)
                    : CodeBlock.of("if ($L) conditions.add($L)", guard, predicate));
                continue;
            }
            var hops = hops(method, term.steps(), local, tableAliases);
            var alias = hops.isEmpty() ? "table" : hops.getLast();
            var column = CodeBlock.of("$L.$L", alias, term.column().javaName());
            var type = type(term.column().bindingType());
            var read = read(term.names(), counter);
            CodeBlock compare;
            CodeBlock guard;
            if (arrayColumn(term.column())) {
                // A list against an array column is the array, compared whole; a single value against one
                // is FILTER_SCALAR_AGAINST_ARRAY_COLUMN's.
                if (!term.list()) throw scalarAgainstArray(term);
                var l = "l" + ++counter[0];
                method.addStatement("$T $L = $T.fromWire($L.getDataType(), $L instanceof $T<?> $L ? $L.toArray() : null)",
                    type, local, columnValues, column, read, LIST, l, l);
                compare = CodeBlock.of("$L.eq($L)", column, local);
                guard = CodeBlock.of("$L != null", local);
            } else if (term.list()) {
                var l = "l" + ++counter[0];
                var e = "e" + ++counter[0];
                method.addStatement("$T $L = $L instanceof $T<?> $L ? $L.stream().map($L -> $L).toList() : null",
                    ParameterizedTypeName.get(LIST, type.box()), local, read, LIST, l, l, e,
                    typed(term, type, CodeBlock.of("$L", e), column, counter, columnValues));
                compare = CodeBlock.of("$L.in($L)", column, local);
                guard = term.present() ? CodeBlock.of("!$L.isEmpty()", local)
                    : CodeBlock.of("$L != null && !$L.isEmpty()", local, local);
            } else {
                method.addStatement("$T $L = $L", type.box(), local, typed(term, type, read, column, counter, columnValues));
                compare = CodeBlock.of("$L.eq($L)", column, local);
                guard = term.present() ? null : CodeBlock.of("$L != null", local);
            }
            var predicate = hops.isEmpty() ? compare : reached(term.steps(), hops, compare);
            if (guard == null) {
                adds.addStatement("conditions.add($L)", predicate);
            } else {
                adds.addStatement("if ($L) conditions.add($L)", guard, predicate);
            }
        }
        for (var elements : listed.values()) {
            list(method, adds, helpers, name, row, elements, locals, counter, tableAliases, columnValues);
        }
        if (row.calls().stream().flatMap(call -> call.fills().stream())
                .anyMatch(fill -> "ARGUMENT".equals(fill.fill()) && !fill.landingNodeId()
                    && !"ENUM_VALUE_OF".equals(fill.extraction())
                    && javaType(fill.parameterType()).orElseThrow() instanceof ParameterizedTypeName)) {
            method.addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked").build());
        }
        for (var call : row.calls()) {
            // A call a node id applies to the table it identifies is called on that table, reached
            // over the node id's join path and aliased per call.
            // A node id with no path from the row's table is an override applied to the row's own
            // table, the node id's key handed to it: with no route, a call that is no override is
            // NODE_ID_LANDS_ON_TABLE's.
            boolean onNode = "NODE_ID".equals(call.bindingKind());
            var hops = onNode && !call.steps().isEmpty()
                ? hops(method, call.steps(), fresh(call.path().names().getLast(), locals), tableAliases)
                : List.<String>of();
            var alias = hops.isEmpty() ? "table" : hops.getLast();
            // The session is asked for by the field, not the occurrence: the method is one per field,
            // its occurrences under several parents rendering it alike.
            var arguments = new ArrayList<CodeBlock>();
            for (var fill : call.fills()) {
                arguments.add(argument(method, helpers, name, call, fill, alias, counter, runtime,
                    row.parentType() + "." + row.fieldName(), builds, decodes));
            }
            var invoke = CodeBlock.of("$T.$L($L)", ClassName.bestGuess(call.className()), call.methodName(),
                CodeBlock.join(arguments, ", "));
            if (!onNode) {
                adds.addStatement("conditions.add($L)", invoke);
            } else {
                var applied = hops.isEmpty() ? invoke : reached(call.steps(), hops, invoke);
                if (call.path().present()) {
                    adds.addStatement("conditions.add($L)", applied);
                } else {
                    adds.addStatement("if ($L != null) conditions.add($L)", read(call.path().names(), counter), applied);
                }
            }
        }
        method.addStatement("$T<$T> conditions = new $T<>()", LIST, CONDITION, ARRAY_LIST);
        method.addCode(adds.build());
        method.addStatement("return $T.and(conditions)", DSL);
        return new Result.Emitted(method.build(), helpers);
    }

    /**
     * Why a list's element fields are not emitted yet: a field that is not one value bound to a column,
     * fields reaching their columns by different paths, or a path every term is held to.
     */
    private static Optional<Reason> reasonFor(ConditionQuery.Row row, List<ConditionQuery.Term> elements) {
        var first = elements.getFirst();
        for (var element : elements) {
            if (element.nodeId() || element.list() || element.column() == null
                    || !element.steps().equals(first.steps())) {
                return Optional.of(Reason.THROUGH_LIST);
            }
            requireJoinable(element.coordinate(), element.steps());
            if (arrayColumn(element.column())) throw scalarAgainstArray(element);
            if (element.steps().isEmpty() && !element.column().tableSqlName().equals(row.table().sqlName())) {
                return Optional.of(Reason.REACHED);
            }
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
                             Set<String> locals, int[] counter, ClassName tableAliases, ClassName columnValues) {
        var first = elements.getFirst();
        int at = first.lists().getFirst().position();
        var names = first.names().subList(0, at + 1);
        var local = fresh(names.getLast(), locals);
        var hops = hops(method, first.steps(), local, tableAliases);
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
            var rows = rowsHelper(helper, tableClass, elements, at, counter, columnValues);
            helpers.add(rows);
            method.addStatement("$T $L = $L($L, $L)", ParameterizedTypeName.get(LIST, elements.size() == 1
                ? type(first.column().bindingType()).box() : ClassName.get("org.jooq", "RowN")),
                local, helper, wire, alias);
            compare = elements.size() == 1
                ? CodeBlock.of("$L.$L.in($L)", alias, first.column().javaName(), local)
                : CodeBlock.of("$T.row(($T<?>) $T.of($L)).in($L)", DSL, java.util.Collection.class, LIST,
                    columns(alias, elements), local);
            guard = present ? CodeBlock.of("!$L.isEmpty()", local)
                : CodeBlock.of("$L != null && !$L.isEmpty()", local, local);
        } else {
            helpers.add(matchHelper(helper, tableClass, elements, at, counter, columnValues));
            method.addStatement("$T $L = $L($L, $L)", CONDITION, local, helper, wire, alias);
            compare = CodeBlock.of("$L", local);
            guard = CodeBlock.of("$L != null", local);
        }
        var predicate = hops.isEmpty() ? compare : reached(first.steps(), hops, compare);
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
                                         int at, int[] counter, ClassName columnValues) {
        boolean single = elements.size() == 1;
        var first = elements.getFirst();
        TypeName row = single ? type(first.column().bindingType()).box()
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
        var cells = cells(body, elements, at, counter, columnValues);
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
                                          int at, int[] counter, ClassName columnValues) {
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
        var cells = cells(body, elements, at, counter, columnValues);
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
                                      int[] counter, ClassName columnValues) {
        var out = new ArrayList<String>();
        for (var element : elements) {
            var cell = "c" + (out.size() + 1);
            var type = type(element.column().bindingType());
            var column = CodeBlock.of("table.$L", element.column().javaName());
            var value = read("fields", element.names().subList(at + 1, element.names().size()), counter);
            body.addStatement("$T $L = $L", type.box(), cell, typed(element, type, value, column, counter, columnValues));
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
     * a node id's table it is applied to that the store states no join path to.
     */
    private static Optional<Reason> reasonFor(ConditionQuery.Call call, Map<BuildQuery.Root, BuildQuery.Result> builds,
                                              NodeIdDecodeQuery decodes) {
        if ("NODE_ID".equals(call.bindingKind())) {
            if (call.path() == null) return Optional.of(Reason.AUTHORED_NODE_ID);
            requireJoinable(call.coordinate(), call.steps());
        }
        for (var fill : call.fills()) {
            switch (fill.fill()) {
                case "TABLE" -> { }
                case "ARGUMENT" -> {
                    if (fill.landingNodeId()) {
                        if (fill.throughList() || decoded(fill, decodes).isEmpty()) {
                            return Optional.of(Reason.AUTHORED_NODE_ID);
                        }
                        if (javaType(fill.parameterType()).isEmpty()) return Optional.of(Reason.UNSPELLED_TYPE);
                        continue;
                    }
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
                default -> throw new IllegalStateException("Graphitron generator bug: " + call.coordinate()
                    + " fills a parameter by " + fill.fill() + ", a fill the store writes for no @condition");
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
     * How a node id fills a parameter, in the one shape the store states at it for its one node table:
     * the node's record, a {@code RecordN} or {@code RowN} of its key, or the projected key column's
     * value. Empty where no shape fits, the id is of several node types, or its decode is not stated.
     */
    private static Optional<BuildQuery.Result.Decoded> decoded(ConditionQuery.Fill fill, NodeIdDecodeQuery decodes) {
        if (fill.shapes().size() != 1) return Optional.empty();
        var landing = fill.shapes().getFirst();
        var shape = switch (landing.shape()) {
            case "OWN_RECORD" -> "RECORD";
            case "RECORD_N", "ROW_N" -> landing.shape();
            case "KEY_VALUE" -> landing.projectedColumn() == null ? null : landing.shape();
            default -> null;
        };
        if (shape == null) return Optional.empty();
        return decodes.resolve(fill.landing(), landing.bound(), Optional.empty())
                instanceof NodeIdDecodeQuery.Resolution.Resolved resolved
            ? Optional.of(new BuildQuery.Result.Decoded(List.of(resolved.decode()), shape,
                Optional.ofNullable(landing.projectedColumn()), landing.erasedClass()))
            : Optional.empty();
    }

    /**
     * The value one parameter of an authored call takes: the table the condition is applied to, as
     * {@code table} names it, or an argument's value off the map, cast to the parameter's declared type,
     * an enum by its constant's name, a node id decoded by a helper of its own.
     */
    private static CodeBlock argument(MethodSpec.Builder method, List<MethodSpec> helpers, String name,
                                      ConditionQuery.Call call, ConditionQuery.Fill fill, String table, int[] counter,
                                      FillEmitter.Runtime runtime, String coordinate,
                                      Map<BuildQuery.Root, BuildQuery.Result> builds, NodeIdDecodeQuery decodes) {
        switch (fill.fill()) {
            case "TABLE" -> {
                return CodeBlock.of("$L", table);
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
        if (fill.landingNodeId()) {
            var boxed = type.isPrimitive() ? type.box() : type;
            var local = "d" + ++counter[0];
            var helper = name + "_decode" + counter[0];
            helpers.add(NodeIdDecodeEmitter.parameterHelper(helper, boxed, decoded(fill, decodes).orElseThrow(),
                runtime.decode()));
            method.addStatement("$T $L = $L($L)", boxed, local, helper, read);
            return CodeBlock.of("$L", local);
        }
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
            if (term.nodeIdBindings() > 1) {
                return participantDecode(row, term, decodes).isPresent() ? Optional.empty()
                    : Optional.of(Reason.NODE_ID);
            }
            if (decodeOnto(row, term, decodes).isEmpty()) return Optional.of(Reason.NODE_ID);
            return Optional.empty();
        }
        requireJoinable(term.coordinate(), term.steps());
        if (term.steps().isEmpty() && !term.column().tableSqlName().equals(row.table().sqlName())) {
            return Optional.of(Reason.REACHED);
        }
        return Optional.empty();
    }

    /**
     * Holds the invariant every term and call path is read under: each step joins on column pairs or a
     * condition method. A routine step is taken only by a {@code @routine} element, which is declared on
     * FIELD_DEFINITION alone and so never on an argument's or input field's path, and a condition step
     * whose method resolved to no overload is refused as CONDITION_NO_OVERLOAD_FOR_PAIR or
     * CONDITION_OVERLOAD_TIE.
     */
    private static void requireJoinable(String coordinate, List<ConditionQuery.Step> steps) {
        for (var step : steps) {
            if (step.routine()) {
                throw new IllegalStateException("Graphitron generator bug: " + coordinate + " reaches "
                    + step.to().sqlName() + " over a routine step, which only a @routine element takes, and"
                    + " @routine is declared on FIELD_DEFINITION only");
            }
            if (step.pairs().isEmpty() && step.method() == null) {
                throw new IllegalStateException("Graphitron generator bug: " + coordinate + " reaches "
                    + step.to().sqlName() + " over a step with neither column pairs nor a condition method,"
                    + " which CONDITION_NO_OVERLOAD_FOR_PAIR or CONDITION_OVERLOAD_TIE should have refused");
            }
        }
    }

    /** Where a node id term decodes: its decode, and whether it compares on the row's own table. */
    private record Onto(NodeIdDecodeQuery.Decode decode, boolean own) {}

    /**
     * Where a node id term's id decodes to: onto the condition's own table where the store states that
     * decode, the node's own key there or the one foreign key referencing it, so an id whose path is a
     * single step over that key compares on the table itself; otherwise onto the node's own key on the
     * table its path lands on, reached over the path's steps. Empty where neither decode is stated: the
     * path lands off the node's table, or there is no path and the node is on another table.
     */
    private static Optional<Onto> decodeOnto(ConditionQuery.Row row, ConditionQuery.Term term,
                                             NodeIdDecodeQuery decodes) {
        requireJoinable(term.coordinate(), term.steps());
        if (decodes.bounds(term.coordinate()).size() != 1) {
            return Optional.empty();
        }
        var bound = decodes.bounds(term.coordinate()).getFirst();
        if (term.steps().size() <= 1 && term.steps().stream().allMatch(step -> step.method() == null)
                && decodes.resolve(term.coordinate(), bound, Optional.of(row.table().recordClassName()))
                    instanceof NodeIdDecodeQuery.Resolution.Resolved own) {
            return Optional.of(new Onto(own.decode(), true));
        }
        // On the table the path lands on the id is the node's own key there, whatever the path's
        // steps carry back to the row's table, so a path that leaves the node's table and returns to
        // it compares at the landing.
        if (term.steps().isEmpty() || term.landingRecordClass() == null) {
            return Optional.empty();
        }
        return decodes.resolveOnNode(term.coordinate(), bound) instanceof NodeIdDecodeQuery.Resolution.Resolved landed
                && landed.decode().recordClass().equals(term.landingRecordClass())
            ? Optional.of(new Onto(landed.decode(), false)) : Optional.empty();
    }

    /**
     * Where a node id each participant decodes as a node of its own decodes on the row's table: onto
     * the table itself, as the node the id binds there, which carries the type id an id must carry to
     * be decoded there. Empty where the id binds no node on the row's table or the decode is not stated.
     */
    private static Optional<NodeIdDecodeQuery.Decode> participantDecode(ConditionQuery.Row row,
                                                                        ConditionQuery.Term term,
                                                                        NodeIdDecodeQuery decodes) {
        if (term.ownTypeId() == null) return Optional.empty();
        return decodes.resolve(term.coordinate(), row.bound(), Optional.of(row.table().recordClassName()))
            instanceof NodeIdDecodeQuery.Resolution.Resolved own ? Optional.of(own.decode()) : Optional.empty();
    }

    /**
     * A node id term each participant decodes as a node of its own: of the ids on the wire, this
     * participant keeps those carrying its own type id, peeked off each id, and decodes only those onto
     * its table, so an id goes to the participant whose type id it carries and no other decodes it. An
     * id of this participant's type that does not decode is a client error, as any node id's is.
     *
     * <p>Absent, or a list empty on the wire, filters nothing. A list whose ids all name other types,
     * or a single id of another type, answers no row on this participant.
     */
    private static void participant(MethodSpec.Builder method, CodeBlock.Builder adds, List<MethodSpec> helpers,
                                    ConditionQuery.Term term, NodeIdDecodeQuery.Decode decode, String helper,
                                    String local, Set<String> locals, int[] counter,
                                    NodeIdDecodeEmitter.Runtime runtime) {
        var record = SessionIdentityRows.classOf(decode.recordClass());
        var type = term.list() ? ParameterizedTypeName.get(LIST, record) : record;
        var decoder = helper + "Decode";
        helpers.add(NodeIdDecodeEmitter.parameterHelper(decoder, type,
            new BuildQuery.Result.Decoded(List.of(decode), "RECORD", Optional.empty(),
                term.list() ? "java.util.List" : decode.recordClass()),
            runtime));
        var own = MethodSpec.methodBuilder(helper)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(type)
            .addParameter(Object.class, "wire");
        if (term.list()) {
            own.addStatement("if (!(wire instanceof $T<?> ids) || ids.isEmpty()) return null",
                    ClassName.get("java.util", "Collection"))
                .addStatement("$T<$T> kept = new $T<>()", LIST, String.class, ARRAY_LIST)
                .beginControlFlow("for ($T element : ids)", Object.class)
                .addStatement("if (element instanceof $T id && $S.equals($T.peekTypeId(id))) kept.add(id)",
                    String.class, term.ownTypeId(), runtime.nodeIdEncoder())
                .endControlFlow()
                .addStatement("return $L(kept)", decoder);
        } else {
            own.addStatement("if (!(wire instanceof $T id) || !$S.equals($T.peekTypeId(id))) return null",
                    String.class, term.ownTypeId(), runtime.nodeIdEncoder())
                .addStatement("return $L(id)", decoder);
        }
        helpers.add(own.build());
        var compare = keyCompare(term, decode, local, "table", counter);
        if (term.list()) {
            // Null where the wire list is absent or empty; empty where it names only other types.
            method.addStatement("$T $L = $L($L)", type, local, helper, read(term.names(), counter));
            adds.addStatement("if ($L != null) conditions.add($L.isEmpty() ? $T.falseCondition() : $L)",
                local, local, DSL, compare);
            return;
        }
        // Null where the id is absent or of another type; the wire tells the two apart.
        var wire = fresh(local + "Wire", locals);
        method.addStatement("$T $L = $L", Object.class, wire, read(term.names(), counter));
        method.addStatement("$T $L = $L($L)", type, local, helper, wire);
        var predicate = CodeBlock.of("$L == null ? $T.falseCondition() : $L", local, DSL, compare);
        adds.addStatement(term.present() ? CodeBlock.of("conditions.add($L)", predicate)
            : CodeBlock.of("if ($L != null) conditions.add($L)", wire, predicate));
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
        return keyCompare(term, decode, local, alias, counter);
    }

    /**
     * The decoded record, or list of records, in {@code local} compared on the key columns of
     * {@code alias}: by equality or membership, as a row value where the key has several columns; a
     * null element of a list compares as a key of nulls, matching nothing.
     */
    private static CodeBlock keyCompare(ConditionQuery.Term term, NodeIdDecodeQuery.Decode decode, String local,
                                        String alias, int[] counter) {
        var record = SessionIdentityRows.classOf(decode.recordClass());
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
     * Declares the table each step of a path lands on, aliased one nesting level deeper than the table
     * the condition is handed ({@link TableAliasFragments}) so a condition applied inside another
     * statement's alias never shadows it, and returns their locals in step order; none for an empty path.
     */
    private static List<String> hops(MethodSpec.Builder method, List<ConditionQuery.Step> steps, String local,
            ClassName tableAliases) {
        var hops = new java.util.ArrayList<String>();
        for (var step : steps) {
            var hop = local + "Hop" + step.position();
            method.addStatement("$T $L = $T.$L.as($L)", ClassName.bestGuess(step.to().tableClassName()),
                hop, ClassName.bestGuess(step.to().constantsClassName()), step.to().javaFieldName(),
                TableAliasFragments.nested(tableAliases, "table", step.to().javaFieldName(),
                    local + "_" + step.position()));
            hops.add(hop);
        }
        return hops;
    }

    /**
     * A predicate on the last table of a path, as a correlated {@code EXISTS} from the condition's
     * table: the first step correlates to it, each later step joins the one before, on its column pairs
     * and its condition method where it has them.
     */
    private static CodeBlock reached(List<ConditionQuery.Step> steps, List<String> hops, CodeBlock compare) {
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
     * The wire value {@code value} as the column's Java type, as the store states the term's
     * conversion ({@link ColumnConversion#atColumn}), an enum constant's pattern variable numbered
     * among the body's locals.
     */
    private static CodeBlock typed(ConditionQuery.Term term, TypeName type, CodeBlock value, CodeBlock column,
                                   int[] counter, ClassName columnValues) {
        return ColumnConversion.atColumn(term.conversion(), type, column, value, () -> "s" + ++counter[0],
            columnValues);
    }

    /** The generated {@code ColumnValues} of the run, which every typed wire value is coerced through. */
    private static ClassName columnValues(FillEmitter.Runtime runtime) {
        if (runtime.decode() == null) {
            throw new IllegalArgumentException("a condition types its wire values through the run's ColumnValues,"
                + " and the runtime names no output package");
        }
        return no.sikt.graphitron.render.ColumnValuesRef.of(runtime.decode().outputPackage());
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

    /**
     * Whether a column holds an array, as its binding type's {@code Class.getName()} spells one: any
     * array but {@code byte[]}, which is one binary value.
     */
    private static boolean arrayColumn(ConditionQuery.Column column) {
        return column.bindingType().startsWith("[") && !"[B".equals(column.bindingType());
    }

    /** The invariant a single value compared against an array column breaks. */
    private static IllegalStateException scalarAgainstArray(ConditionQuery.Term term) {
        return new IllegalStateException("Graphitron generator bug: " + term.coordinate() + " compares one value"
            + " with the array column " + term.column().sqlName()
            + ", which FILTER_SCALAR_AGAINST_ARRAY_COLUMN should have refused");
    }

    /**
     * The Java type a column's binding type names. sql_column.binding_type is {@code Class.getName()},
     * which {@link CatalogRefs#decodeBindingType} decodes whole, arrays and primitives included, so a
     * name it cannot decode is a generator bug.
     */
    private static TypeName type(String bindingType) {
        var type = CatalogRefs.decodeBindingType(bindingType);
        if (type == null) {
            throw new IllegalStateException("Graphitron generator bug: the column binding type '" + bindingType
                + "' is no Class.getName() spelling");
        }
        return type;
    }
}
