package no.sikt.graphitron.emit;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.emit.RootQuery.Emitted;
import no.sikt.graphitron.emit.RootQuery.Key;
import no.sikt.graphitron.emit.RootQuery.Routine;
import no.sikt.graphitron.emit.RootQuery.RoutineArgument;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.render.CatalogRefs;

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
 * that type's projection, the order, and the fetch of a list or one record.
 *
 * <p>The source is the variation point. A table and a routine call are rendered here; a lookup joining
 * its keys' rows, a root over several tables and a connection's page are each to arrive as a source or a
 * shape of their own, over the same condition, projection and fetch.
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

    /** The locals a body declares itself, which no parameter's local may take. */
    private static final Set<String> TAKEN = Set.of("dsl", "env", "condition", "orderBy");

    private RootReadEmitter() {}

    /**
     * The names a root read calls by: its own method, the conditions class and method its occurrence's
     * filter is rendered as, the {@code @orderBy} helper its choice is rendered as, the projection unit of
     * the type it returns, and the selection scaffold. Each is the name the emitter of that unit gives it.
     */
    public interface Names {
        String launcherMethod(String parentType, String fieldName);
        String orderByHelper(String parentType, String fieldName);
        ClassName conditionsClass(String parentType);
        String conditionMethod(String parentType, String fieldName);
        ClassName projectionUnit(String typeName);
        ClassName selectionOccurrences();
    }

    /**
     * Whether this emitter renders {@code root}: a list or one record, of a table or a routine, ordered by
     * columns of the table its rows are on or as the request's {@code @orderBy} argument chooses. A lookup,
     * a connection, a root over a discriminated table and a fixed key down a path or computed by a method
     * are rendered elsewhere until their piece lands here.
     */
    public static boolean renders(Emitted root) {
        return root.keys().isEmpty() && root.pageSize() == null
            && root.discriminated() == null && root.facets().isEmpty()
            && (root.choice() != null || root.order().stream().allMatch(key -> key.derived() == null));
    }

    /** What the body hands back: the fetched rows for a list, the one record or {@code null} otherwise. */
    public static TypeName valueTypeOf(Emitted root) {
        return root.list() ? ParameterizedTypeName.get(RESULT, RECORD) : RECORD;
    }

    /**
     * The body. {@code projectionTakesDsl} is whether the projection unit's {@code $project} takes the
     * executing {@code DSLContext}, a session-reading condition inside it reading the handle off it.
     */
    public static MethodSpec render(Emitted root, Names names, boolean projectionTakesDsl) {
        if (!renders(root)) {
            throw new IllegalStateException("Graphitron generator bug (root read): " + root.coordinate()
                + " is no read this emitter renders; its caller dispatched it here regardless");
        }
        var method = MethodSpec.methodBuilder(names.launcherMethod(root.parentType(), root.fieldName()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(valueTypeOf(root))
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ENV, "env");
        var source = root.routine() == null ? table(root.table()) : routine(root.routine());
        method.addCode(source.declarations());

        var condition = root.condition() == null
            ? CodeBlock.of("$T.noCondition()", DSL)
            : conditionCall(root, names, source.terminal());
        for (var filter : source.filters()) {
            condition = CodeBlock.of("$L.and($L)", condition, filter);
        }
        method.addStatement("$T condition = $L", CONDITION, condition);

        boolean ordered = root.list() && (root.choice() != null || !root.order().isEmpty());
        if (ordered && root.choice() != null) {
            // The order the request's argument chooses, or the default, from the root's @orderBy helper.
            method.addStatement("$T orderBy = $L(env, $L).sortFields()", SORT_FIELD_LIST,
                names.orderByHelper(root.parentType(), root.fieldName()), source.terminal());
        } else if (ordered) {
            method.addStatement("$T orderBy = $T.of($L)", SORT_FIELD_LIST, LIST, sortParts(root.order(), source.terminal()));
        }
        // The type's projection over the fetched field's own selection.
        var projection = CodeBlock.of("$T.$$project($T.ownFields(env.getSelectionSet()), $L, env$L)",
            names.projectionUnit(root.returnType()), names.selectionOccurrences(), source.terminal(),
            projectionTakesDsl ? ", dsl" : "");
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

    /**
     * Where a read's rows come from: the locals it declares, the table local the condition, projection
     * and order read the rows off, what the select runs over, and the filters its steps add.
     */
    private record Source(CodeBlock declarations, String terminal, CodeBlock from, List<CodeBlock> filters) {}

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
                on.add(CodeBlock.of("$L.$L.eq($L.$L)", left, pair.from().javaName(), local, pair.to().javaName()));
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
