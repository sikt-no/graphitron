package no.sikt.graphitron.emit;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.emit.RootQuery.Emitted;
import no.sikt.graphitron.emit.RootQuery.FieldKey;
import no.sikt.graphitron.emit.RootQuery.Key;
import no.sikt.graphitron.emit.RootQuery.LookupArg;
import no.sikt.graphitron.emit.RootQuery.NodeDecode;
import no.sikt.graphitron.emit.RootQuery.Routine;
import no.sikt.graphitron.emit.RootQuery.RoutineArgument;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.render.ColumnComparison;
import no.sikt.graphitron.render.ColumnValuesRef;
import no.sikt.graphitron.render.NodeIdDecodeFailure;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The body of a root read the store states whole: {@code public static <V> rows<Field>(DSLContext dsl,
 * DataFetchingEnvironment env)}, rendered from {@link RootQuery}'s plain values alone. A read is where its
 * rows come from, a table or a table-valued routine called with its arguments and stepped on from its
 * result, and what is done with them: the root's condition method over the table the rows are read off,
 * that type's projection, the order, and the fetch of a list, one record or a connection's page.
 *
 * <p>The source is the variation point. A table, a routine call and a lookup joining its keys' rows
 * are rendered here; a root over several tables is to arrive as a source of its own, over the same
 * condition, projection and fetch.
 */
public final class RootReadEmitter {

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final TypeName SORT_FIELD_LIST = ParameterizedTypeName.get(LIST,
        ParameterizedTypeName.get(ClassName.get("org.jooq", "SortField"), WildcardTypeName.subtypeOf(Object.class)));
    private static final TypeName FIELD_LIST = ParameterizedTypeName.get(LIST,
        ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"), WildcardTypeName.subtypeOf(Object.class)));
    private static final TypeName RESULT_OF_RECORD = ParameterizedTypeName.get(RESULT, RECORD);
    private static final TypeName LIST_OF_RECORD = ParameterizedTypeName.get(LIST, RECORD);
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName ARRAYS = ClassName.get("java.util", "Arrays");

    /**
     * jOOQ's typed rows stop at {@code Row22}, and a lookup's row carries its key's index before its key
     * columns, so a lookup keys on at most 21 columns; the store refuses a wider one.
     */
    private static final int MAX_ROW_ARITY = 22;

    /** The locals a body declares itself, which no parameter's local may take. */
    private static final Set<String> TAKEN = Set.of("dsl", "env", "condition", "orderBy", "ordering", "extraFields",
        "first", "last", "after", "before", "page", "result", "pageCondition", "selectFields", "n", "rows", "i",
        "input", "flat", "out", "r", "idx");

    private RootReadEmitter() {}

    /**
     * The names a root read calls by: its own method, the conditions class and method its occurrence's
     * filter is rendered as, the {@code @orderBy} helper its choice is rendered as, the projection unit of
     * the type it returns, the selection scaffold, the carrier that helper hands back, and the connection
     * helper a page is requested and its connection built through. Each is the name the emitter of that
     * unit gives it.
     */
    public interface Names {
        String launcherMethod(String parentType, String fieldName);
        String orderByHelper(String parentType, String fieldName);
        ClassName conditionsClass(String parentType);
        String conditionMethod(String parentType, String fieldName);
        ClassName projectionUnit(String typeName);
        ClassName selectionOccurrences();
        ClassName connectionHelper();
        ClassName orderByResult();

        /**
         * The run's output package, which places the generated node-id encoder a lookup's node id is
         * decoded by, the column-values helper its keys are coerced through and the client error a
         * node id that does not decode raises.
         */
        String outputPackage();
    }

    /**
     * Whether this emitter renders {@code root}: a list, one record or a connection's page, of a table or a
     * routine, in its order or as the request's {@code @orderBy} argument chooses, or a lookup's list or
     * one record. A root over a discriminated table and a faceted connection are rendered elsewhere until
     * their piece lands here; a lookup is never a connection.
     */
    public static boolean renders(Emitted root) {
        return root.discriminated() == null && root.facets().isEmpty()
            && (root.keys().isEmpty() || root.pageSize() == null);
    }

    /**
     * What the body hands back: the store's connection class for a connection, {@code connection} being
     * the store's answer for the root's field, the fetched rows for a list, one slot per key for a
     * lookup's list, a slot for a key no row matched holding {@code null}, which a jOOQ {@code Result}
     * does not carry, and the one record or {@code null} otherwise.
     */
    public static TypeName valueTypeOf(Emitted root, Names names, ConnectionsQuery.FieldConnection connection) {
        if (root.pageSize() != null) {
            return ConnectionConstruction.classOf(names.connectionHelper(), connection);
        }
        if (!root.list()) {
            return RECORD;
        }
        return root.keys().isEmpty() ? RESULT_OF_RECORD : LIST_OF_RECORD;
    }

    /**
     * Whether {@code root}'s keys carry a node id, as an argument or as a field of the input object
     * an argument takes: the body then decodes it through the generated node-id encoder, which the
     * fetchers class the body sits in must reach.
     */
    public static boolean decodesNodeId(Emitted root) {
        return root.keys().stream().anyMatch(key -> switch (key) {
            case LookupArg.NodeId ignored -> true;
            case LookupArg.Scalar ignored -> false;
            case LookupArg.Fields fields -> fields.fields().stream().anyMatch(field -> field instanceof FieldKey.NodeId);
        });
    }

    /**
     * The body. {@code connection} is the store's answer for the root's field where it is a connection.
     * Where the type's projection reads the session its {@code $project} takes the executing
     * {@code DSLContext}, a session-reading condition inside it reading the handle off it.
     */
    public static MethodSpec render(Emitted root, Names names, ConnectionsQuery.FieldConnection connection) {
        if (!renders(root)) {
            throw new IllegalStateException("Graphitron generator bug (root read): " + root.coordinate()
                + " is no read this emitter renders; its caller dispatched it here regardless");
        }
        boolean page = root.pageSize() != null;
        if (page && connection == null) {
            throw new IllegalStateException("Graphitron generator bug (root read): the store states "
                + root.coordinate() + " a connection and states no connection class for its field");
        }
        var method = MethodSpec.methodBuilder(names.launcherMethod(root.parentType(), root.fieldName()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(valueTypeOf(root, names, connection))
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ENV, "env");
        var source = !root.keys().isEmpty() ? lookupRows(root, names)
            : root.routine() == null ? table(root.table()) : routine(root.routine());
        method.addCode(source.declarations());

        var condition = root.condition() == null
            ? CodeBlock.of("$T.noCondition()", DSL)
            : conditionCall(root, names, source.terminal());
        for (var filter : source.filters()) {
            condition = CodeBlock.of("$L.and($L)", condition, filter);
        }
        method.addStatement("$T condition = $L", CONDITION, condition);
        // The type's projection over the fetched field's own selection, or its connection's nodes.
        var projection = CodeBlock.of("$T.$$project($T.$L(env.getSelectionSet()), $L, env$L)",
            names.projectionUnit(root.returnType()), names.selectionOccurrences(),
            page ? "connectionNodes" : "ownFields", source.terminal(), root.projectionTakesSession() ? ", dsl" : "");
        if (page) {
            method.addCode(order(root, names, source.terminal(), true));
            method.addCode(connectionPage(root, names, connection, source, projection));
            return method.build();
        }

        if (source.shape() instanceof Shape.KeyedList keyed) {
            method.addCode(keyedList(source, keyed, projection));
            return method.build();
        }
        boolean ordered = root.list() && (root.choice() != null || !root.order().isEmpty());
        if (ordered) {
            method.addCode(order(root, names, source.terminal(), false));
        }
        var fetch = CodeBlock.builder()
            .add("dsl\n").indent()
            .add(".select($L)\n", projection)
            .add(".from($L)\n", source.from())
            .add(".where(condition)\n");
        if (ordered) {
            fetch.add(".orderBy(orderBy)\n");
        }
        fetch.add(root.list() ? ".fetch()" : ".fetchOne()").unindent();
        method.addStatement("return $L", fetch.build());
        return method.build();
    }

    // ---------------------------------------------------------------------------------------------
    // The keyed list: a lookup's list, one slot per key.
    // ---------------------------------------------------------------------------------------------

    /**
     * A lookup's list: the rows its keys match, fetched with no order, the key index the source states
     * selected beside the projection under {@link OrderKeys#LOOKUP_INDEX_ALIAS}, each row placed at
     * that index among the slots the source states, so the answer holds one slot per key in key order.
     * A key no row matched leaves its slot {@code null}, and a key several rows match keeps the first
     * the database yields, since a lookup's key columns need not be unique; a key given twice is
     * answered at each of its positions. The input order is the scatter's, so the select needs none.
     */
    private static CodeBlock keyedList(Source source, Shape.KeyedList keyed, CodeBlock projection) {
        var code = CodeBlock.builder();
        code.addStatement("$T selectFields = new $T<>($L)", FIELD_LIST, ARRAY_LIST, projection);
        code.addStatement("selectFields.add($L.as($S))", keyed.indexField(), OrderKeys.LOOKUP_INDEX_ALIAS);
        code.add("$T flat = dsl\n", RESULT_OF_RECORD).indent()
            .add(".select(selectFields)\n")
            .add(".from($L)\n", source.from())
            .add(".where(condition)\n")
            .add(".fetch();\n").unindent();
        code.addStatement("$T[] out = new $T[$L]", RECORD, RECORD, keyed.slotCount());
        code.beginControlFlow("for ($T r : flat)", RECORD)
            .addStatement("int idx = r.get($S, $T.class)", OrderKeys.LOOKUP_INDEX_ALIAS, Integer.class)
            .beginControlFlow("if (out[idx] == null)")
            .addStatement("out[idx] = r")
            .endControlFlow()
            .endControlFlow();
        return code.addStatement("return $T.asList(out)", ARRAYS).build();
    }

    /**
     * The {@code orderBy} local, and for a page the {@code extraFields} its cursor reads: the order the
     * request's argument chooses, or the default, from the root's {@code @orderBy} helper, else the root's
     * own order, each derived key numbered among the order's derived keys as the helper numbers them.
     */
    private static CodeBlock order(Emitted root, Names names, String table, boolean page) {
        var code = CodeBlock.builder();
        if (root.choice() != null) {
            var helper = names.orderByHelper(root.parentType(), root.fieldName());
            if (!page) {
                return code.addStatement("$T orderBy = $L(env, $L).sortFields()", SORT_FIELD_LIST, helper, table).build();
            }
            code.addStatement("$T ordering = $L(env, $L)", names.orderByResult(), helper, table);
            code.addStatement("$T orderBy = ordering.sortFields()", SORT_FIELD_LIST);
            return code.addStatement("$T extraFields = ordering.columns()", FIELD_LIST).build();
        }
        code.addStatement("$T orderBy = $T.of($L)", SORT_FIELD_LIST, LIST, sortParts(root.order(), table));
        if (page) {
            var columns = new ArrayList<CodeBlock>();
            int ordinal = 0;
            for (var key : root.order()) {
                columns.add(OrderKeys.cursorColumn(key, table, key.derived() == null ? -1 : ordinal++));
            }
            code.addStatement("$T extraFields = $T.of($L)", FIELD_LIST, LIST, CodeBlock.join(columns, ", "));
        }
        return code.build();
    }

    /**
     * A connection's page: the four page arguments, the page the helper requests over the order and the
     * projection, the seek over it, and the store's connection class built over the page, its count
     * reading the same source and condition the page ran under on the root's own connection where the
     * store states one, else on the request's.
     */
    private static CodeBlock connectionPage(Emitted root, Names names, ConnectionsQuery.FieldConnection connection,
            Source source, CodeBlock projection) {
        var helper = names.connectionHelper();
        var code = CodeBlock.builder();
        code.addStatement("Integer first = env.getArgument($S)", "first");
        code.addStatement("Integer last = env.getArgument($S)", "last");
        code.addStatement("String after = env.getArgument($S)", "after");
        code.addStatement("String before = env.getArgument($S)", "before");
        code.addStatement("$T page = $T.pageRequest(first, last, after, before, $L, orderBy, extraFields, $L)",
            helper.nestedClass("PageRequest"), helper, root.pageSize(), projection);
        code.add("$T result = dsl\n", RESULT_OF_RECORD).indent()
            .add(".select(page.selectFields())\n")
            .add(".from($L)\n", source.from())
            .add(".where(condition)\n")
            .add(".orderBy(page.effectiveOrderBy())\n")
            .add(".seek(page.seekFields())\n")
            .add(".limit(page.limit())\n")
            .add(".fetch();\n").unindent();
        code.addStatement("$T pageCondition = condition", CONDITION);
        var count = ConnectionConstruction.countOf(CodeBlock.of("new $T($L, pageCondition).count($L)",
            helper.nestedClass("Scoped"), source.from(),
            ConnectionConstruction.closureDsl(helper, root.connection() != null, ConnectionConstruction.COUNT_ENV)));
        return code.addStatement("return $L", ConnectionConstruction.build(helper, connection, "result", count, null))
            .build();
    }

    /**
     * Where a read's rows come from: the statements it opens the body with, a return that answers
     * before any row is read among them, the table local the condition, projection and order read the
     * rows off, what the select runs over, the filters its steps add, and the shape its answer takes.
     */
    private record Source(CodeBlock declarations, String terminal, CodeBlock from, List<CodeBlock> filters,
                          Shape shape) {

        /** A source whose answer is the shape the root states. */
        Source(CodeBlock declarations, String terminal, CodeBlock from, List<CodeBlock> filters) {
            this(declarations, terminal, from, filters, new Shape.Stated());
        }
    }

    /** The shape a source's answer takes. */
    private sealed interface Shape {

        /** The root's own: its list, its one record or its connection's page. */
        record Stated() implements Shape {}

        /**
         * One slot per key: {@code slotCount} the number of slots, and {@code indexField} the field
         * of the source's rows carrying each row's slot.
         */
        record KeyedList(CodeBlock slotCount, CodeBlock indexField) implements Shape {}
    }

    /** A table, read whole. */
    private static Source table(TableRef table) {
        String local = localOf(table);
        return new Source(declare(table, local), local, CodeBlock.of("$L", local), List.of());
    }

    /** The table's local, its class's simple name lower-camelled: {@code Film} to {@code filmTable}. */
    private static String localOf(TableRef table) {
        var simple = CatalogRefs.tableClass(table).simpleName();
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + "Table";
    }

    /** {@code <TableClass> <local> = <Tables>.<FIELD>;}. */
    private static CodeBlock declare(TableRef table, String local) {
        return CodeBlock.builder().addStatement("$T $L = $T.$L", CatalogRefs.tableClass(table), local,
            CatalogRefs.constantsClass(table), table.javaFieldName()).build();
    }

    /**
     * A table-valued routine called with its arguments, each read into a local first, then joined on to
     * each table a step reaches, by the columns the step pairs, its condition methods filtering it.
     */
    private static Source routine(Routine routine) {
        var code = CodeBlock.builder();
        var args = new ArrayList<CodeBlock>();
        for (var argument : routine.arguments()) {
            String local = TAKEN.contains(argument.parameter()) ? argument.parameter() + "Parameter" : argument.parameter();
            argumentReads(argument, local).forEach(code::addStatement);
            args.add(CodeBlock.of("$L", local));
        }
        var locals = new HashSet<String>();
        String resultLocal = localOf(routine.result());
        locals.add(resultLocal);
        code.addStatement("$T $L = $T.$L($L)", CatalogRefs.tableClass(routine.result()), resultLocal,
            CatalogRefs.className(routine.routinesClass()), routine.method(), CodeBlock.join(args, ", "));
        var from = CodeBlock.builder().add("$L", resultLocal);
        var filters = new ArrayList<CodeBlock>();
        String left = resultLocal;
        int step = 0;
        for (var hop : routine.hops()) {
            step++;
            String local = localOf(hop.target());
            // A table reached twice is aliased apart from the first.
            boolean again = !locals.add(local);
            if (again) {
                local = local + step;
                locals.add(local);
                code.addStatement("$T $L = $T.$L.as($S)", CatalogRefs.tableClass(hop.target()), local,
                    CatalogRefs.constantsClass(hop.target()), hop.target().javaFieldName(), local);
            } else {
                code.add(declare(hop.target(), local));
            }
            var on = new ArrayList<CodeBlock>();
            for (var pair : hop.pairs()) {
                on.add(ColumnComparison.equality(left, pair.from(), local, pair.to()));
            }
            from.add(".join($L).on($L)", local, CodeBlock.join(on, ", "));
            for (var filter : hop.filters()) {
                filters.add(CodeBlock.of("$T.$L($L, $L)", CatalogRefs.className(filter.className()),
                    filter.methodName(), left, local));
            }
            left = local;
        }
        return new Source(code.build(), left, from.build(), filters);
    }

    /**
     * The statements reading one argument into {@code local}, as the store states its value reaches the
     * parameter: as it arrives, as the parameter's enum's constant of its name, or a list copied into the
     * array the parameter takes.
     */
    private static List<CodeBlock> argumentReads(RoutineArgument argument, String local) {
        var type = CatalogRefs.decodeBindingType(argument.bindingType());
        return switch (argument.extraction()) {
            case "AS_SENT" -> List.of(ArgumentReads.value(type, local, argument.path()));
            case "ENUM_CONSTANT" -> ArgumentReads.enumConstant(type, local, argument.path());
            case "LIST_TO_ARRAY" -> {
                if (!(type instanceof ArrayTypeName array)) {
                    throw new IllegalStateException("Graphitron generator bug (root read): parameter "
                        + argument.parameter() + " takes a list copied into an array, and its class "
                        + argument.bindingType() + " is no array");
                }
                yield ArgumentReads.array(array, local, argument.path());
            }
            default -> throw new IllegalStateException("Graphitron generator bug (root read): parameter "
                + argument.parameter() + " is read " + argument.extraction() + ", which RootQuery holds back");
        };
    }

    /**
     * One column of a lookup's key row: the column of the lookup's table it lands on, and the value
     * read for it per key, a client's wire value coerced at that column or a position of a decoded
     * node id's record.
     */
    private record KeyCell(ColumnRef column, CodeBlock value) {}

    /**
     * A lookup's keys, joined to its table as rows of a {@code VALUES} table: each key argument read
     * once, {@code n} keys, the length of the first list argument, or one where none is a list, no keys
     * answering the empty list or {@code null} before any row is built, since {@code VALUES} takes no
     * empty set of rows, then per key one typed row, its index followed by its key columns, each bound
     * at its column's own {@code DataType}, a column key's wire value read at its column as the store
     * states its conversion. A node id is decoded once per key, a top-level one failing the field where
     * it is absent or does not decode, one read off an input object's field leaving its columns
     * {@code null} where it is absent and failing the field where it does not decode. The table joins
     * the rows on every key column; a list is the keyed list, {@code n} slots placed by each row's
     * index. The condition method already leaves the key arguments out.
     */
    private static Source lookupRows(Emitted root, Names names) {
        String table = localOf(root.table());
        var used = new HashSet<>(TAKEN);
        used.add(table);
        var declarations = CodeBlock.builder().add(declare(root.table(), table));
        // Each argument read once, into a local named after it.
        var locals = new ArrayList<String>();
        String primary = null;
        for (var key : root.keys()) {
            String local = localFor(key.argument(), used);
            locals.add(local);
            if (key.list()) {
                declarations.addStatement("$T<?> $L = env.getArgument($S)", LIST, local, key.argument());
                if (primary == null) primary = local;
            } else {
                declarations.addStatement("$T $L = env.getArgument($S)", Object.class, local, key.argument());
            }
        }
        if (primary == null) {
            declarations.addStatement("int n = 1");
        } else {
            declarations.addStatement("int n = $L == null ? 0 : $L.size()", primary, primary);
        }
        if (root.list()) {
            declarations.addStatement("if (n == 0) return $T.of()", LIST);
        } else {
            declarations.addStatement("if (n == 0) return null");
        }

        // Per key, the decodes first, then the cells they and the wire values fill. The encoder and
        // the client error are reached only where a key decodes a node id.
        var outputPackage = names.outputPackage();
        var columnValues = ColumnValuesRef.of(outputPackage);
        boolean decodes = decodesNodeId(root);
        var encoder = decodes ? ClassName.get(outputPackage + ".util", "NodeIdEncoder") : null;
        var clientException = decodes ? NodeIdDecodeFailure.clientException(outputPackage) : null;
        var decoded = CodeBlock.builder();
        var cells = new ArrayList<KeyCell>();
        for (int a = 0; a < root.keys().size(); a++) {
            var key = root.keys().get(a);
            String local = locals.get(a);
            var element = key.list() ? CodeBlock.of("$L.get(i)", local) : CodeBlock.of("$L", local);
            switch (key) {
                case LookupArg.Scalar scalar -> cells.add(new KeyCell(scalar.column(),
                    atColumn(scalar.conversion(), table, scalar.column(), element, local, columnValues, used)));
                case LookupArg.NodeId nodeId -> {
                    String record = localFor(local + "Record", used);
                    decoded.add(decode(nodeId.decode(), element, local + "Wire", record, true, encoder,
                        clientException, used));
                    var columns = nodeId.decode().keyColumns();
                    for (int j = 0; j < columns.size(); j++) {
                        cells.add(new KeyCell(columns.get(j), CodeBlock.of("$L == null ? null : $L.value$L()",
                            record, record, j + 1)));
                    }
                }
                case LookupArg.Fields fields -> {
                    for (var field : fields.fields()) {
                        var wire = CodeBlock.of("(($T<?, ?>) $L).get($S)", MAP, element, field.field());
                        String stem = local + Character.toUpperCase(field.field().charAt(0))
                            + field.field().substring(1);
                        switch (field) {
                            case FieldKey.Column column -> cells.add(new KeyCell(column.column(),
                                atColumn(column.conversion(), table, column.column(), wire, stem, columnValues, used)));
                            case FieldKey.NodeId nodeId -> {
                                String record = localFor(stem + "Record", used);
                                decoded.add(decode(nodeId.decode(), wire, stem + "Wire", record, false, encoder,
                                    clientException, used));
                                for (int j = 0; j < nodeId.targetColumns().size(); j++) {
                                    cells.add(new KeyCell(nodeId.targetColumns().get(j), CodeBlock.of(
                                        "$L == null ? null : $L.value$L()", record, record, j + 1)));
                                }
                            }
                        }
                    }
                }
            }
        }
        if (cells.size() + 1 > MAX_ROW_ARITY) {
            throw new IllegalStateException("Graphitron generator bug (root read): the lookup " + root.coordinate()
                + " keys on " + cells.size() + " columns, more than jOOQ's typed rows carry beside the key's index,"
                + " which the store refuses");
        }

        // Row{k+1}<Integer, T1..Tk>[] rows, filled one key at a time.
        var typeArgs = new ArrayList<TypeName>();
        typeArgs.add(ClassName.get(Integer.class));
        cells.forEach(cell -> typeArgs.add(CatalogRefs.columnType(cell.column())));
        var rowClass = ClassName.get("org.jooq", "Row" + (cells.size() + 1));
        var rowType = ParameterizedTypeName.get(rowClass, typeArgs.toArray(TypeName[]::new));
        var recordType = ParameterizedTypeName.get(ClassName.get("org.jooq", "Record" + (cells.size() + 1)),
            typeArgs.toArray(TypeName[]::new));
        declarations.add("@$T({$S, $S})\n", SuppressWarnings.class, "unchecked", "rawtypes");
        declarations.addStatement("$T[] rows = ($T[]) new $T[n]", rowType, rowType, rowClass);
        declarations.beginControlFlow("for (int i = 0; i < n; i++)");
        declarations.add(decoded.build());
        var row = CodeBlock.builder().add("$T.inline(i)", DSL);
        for (var cell : cells) {
            row.add(", $T.val($L, $L.$L.getDataType())", DSL, cell.value(), table, cell.column().javaName());
        }
        declarations.addStatement("rows[i] = $T.row($L)", DSL, row.build());
        declarations.endControlFlow();

        // The rows as a table, its index column first, each key column labelled by the SQL name of the
        // column it lands on.
        String index = "idx";
        var labels = CodeBlock.builder().add("$S, $S", root.fieldName() + "Input", index);
        cells.forEach(cell -> labels.add(", $S", cell.column().sqlName()));
        declarations.addStatement("$T input = $T.values(rows).as($L)",
            ParameterizedTypeName.get(ClassName.get("org.jooq", "Table"), recordType), DSL, labels.build());

        var on = new ArrayList<CodeBlock>();
        for (var cell : cells) {
            var operand = CodeBlock.of("input.field($S, $L.$L.getDataType())", cell.column().sqlName(), table,
                cell.column().javaName());
            on.add(ColumnComparison.equalityAgainstField(table, cell.column(), cell.column(), operand));
        }
        var joined = CodeBlock.builder().add("$L", on.getFirst());
        on.stream().skip(1).forEach(eq -> joined.add(".and($L)", eq));
        var from = CodeBlock.of("$L.join(input).on($L)", table, joined.build());
        Shape shape = root.list()
            ? new Shape.KeyedList(CodeBlock.of("n"), CodeBlock.of("input.field($S)", index))
            : new Shape.Stated();
        return new Source(declarations.build(), table, from, List.of(), shape);
    }

    /**
     * A client's wire value as a value of the lookup table's column it is a key of, as the store
     * states its conversion ({@link ColumnConversion#atColumn}), an enum constant's pattern variable
     * named after {@code stem} apart from the body's other locals.
     */
    private static CodeBlock atColumn(String conversion, String table, ColumnRef column, CodeBlock wire, String stem,
                                      ClassName columnValues, Set<String> used) {
        return ColumnConversion.atColumn(conversion, CatalogRefs.columnType(column),
            CodeBlock.of("$L.$L", table, column.javaName()), wire, () -> localFor(stem + "Name", used), columnValues);
    }

    /**
     * One node id decoded into {@code record}, the record of its node's key columns, by the node's
     * decoder on the generated encoder. A {@code required} id fails the field where it is absent or
     * does not decode; another fails it only where it is present and does not decode, its record
     * {@code null} where it is absent.
     */
    private static CodeBlock decode(NodeDecode decode, CodeBlock element, String wireStem, String record,
            boolean required, ClassName encoder, ClassName clientException, Set<String> used) {
        String wire = localFor(wireStem, used);
        String text = localFor(wire + "String", used);
        // The refusal declares these two off the wire local's name.
        used.add(wire + "Text");
        used.add(wire + "Peeked");
        var recordType = ParameterizedTypeName.get(ClassName.get("org.jooq", "Record" + decode.keyColumns().size()),
            decode.keyColumns().stream().map(CatalogRefs::columnType).toArray(TypeName[]::new));
        return CodeBlock.builder()
            .addStatement("$T $L = $L", Object.class, wire, element)
            .addStatement("$T $L = $L instanceof $T $L ? $T.$L($L) : null", recordType, record, wire, String.class,
                text, encoder, NodeIdHelperNames.decode(decode.nodeType()), text)
            .beginControlFlow(required ? "if ($L == null)" : "if ($L != null && $L == null)",
                required ? new Object[] {record} : new Object[] {wire, record})
            .add(NodeIdDecodeFailure.throwStatement(clientException, encoder, decode.typeId(), decode.nodeType(),
                wire))
            .endControlFlow()
            .build();
    }

    /**
     * A local named after {@code name}, apart from every local the body already declares and from the
     * language's keywords.
     */
    private static String localFor(String name, Set<String> used) {
        String local = name;
        if (!SourceVersion.isName(local) || used.contains(local)) {
            local = name + "Argument";
        }
        for (int k = 2; used.contains(local); k++) {
            local = name + "Argument" + k;
        }
        used.add(local);
        return local;
    }

    /** {@code <Conditions>.<field>Condition(<table>, env.getArguments()[, env][, dsl])}. */
    private static CodeBlock conditionCall(Emitted root, Names names, String terminal) {
        var call = CodeBlock.builder().add("$T.$L($L, env.getArguments()", names.conditionsClass(root.parentType()),
            names.conditionMethod(root.parentType(), root.fieldName()), terminal);
        if (root.condition().takesContext()) {
            call.add(", env");
        }
        if (root.condition().takesSession()) {
            call.add(", dsl");
        }
        return call.add(")").build();
    }

    /** Each key's sort term over {@code table}, in the order's sequence, as {@link OrderKeys} renders it. */
    private static CodeBlock sortParts(List<Key> keys, String table) {
        return keys.stream().map(key -> OrderKeys.sortTerm(key, table, null)).collect(CodeBlock.joining(", "));
    }
}
