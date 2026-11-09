package no.sikt.graphitron.emit;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.Modifier;
import java.util.HashSet;
import java.util.List;
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
 * is converted the way jOOQ binds it. A scalar compares by equality and a list by membership. A value
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
    private static final Pattern READ_LOCAL = Pattern.compile("[mle]\\d+");

    /** The producers whose arguments a condition filters: a read, its lookup and its count. */
    private static final Set<String> FILTERED = Set.of("SELECT", "LOOKUP", "COUNT");

    /** Why an occurrence's condition is not emitted here yet. */
    public enum Reason {
        /** The field runs a call or a write, which takes its arguments rather than filtering by them. */
        CONSUMED,
        /** A {@code @condition} is written on the field or under it, composed with the generated terms. */
        AUTHORED,
        /** A term reaches its column over a path the store states no steps for, or one calling a routine. */
        REACHED,
        /** A term is a node id, decoded into its key columns. */
        NODE_ID,
        /** A term is an enum, converted from its GraphQL name to the column's value. */
        ENUM,
        /** A term is read through a list of input objects, a match per element. */
        THROUGH_LIST,
        /** A term's column is of a Java type this emitter cannot spell. */
        UNSPELLED_TYPE
    }

    /** What the emitter makes of a row: the method, nothing to filter, or why it is not emitted yet. */
    public sealed interface Result {
        record Emitted(MethodSpec method) implements Result {}
        record Nothing() implements Result {}
        record NotYet(Reason reason, String term) implements Result {}
    }

    /** The row's condition as the method {@code name}, or why it is not emitted. */
    public static Result emit(ConditionQuery.Row row, String name) {
        if (row.producerKind() != null && !FILTERED.contains(row.producerKind())) {
            return new Result.NotYet(Reason.CONSUMED, row.coordinate());
        }
        if (row.authored()) {
            return new Result.NotYet(Reason.AUTHORED, row.coordinate());
        }
        var terms = row.terms().stream().filter(term -> !term.lookupKey()).toList();
        if (terms.isEmpty()) {
            return new Result.Nothing();
        }
        for (var term : terms) {
            var reason = reasonFor(row, term);
            if (reason.isPresent()) {
                return new Result.NotYet(reason.get(), term.coordinate());
            }
        }
        var tableClass = ClassName.bestGuess(row.table().tableClassName());
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(tableClass, "table")
            .addParameter(ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class)), "args");
        var locals = new HashSet<String>();
        var counter = new int[1];
        var adds = CodeBlock.builder();
        for (var term : terms) {
            var local = local(term, locals);
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
                method.addStatement("$T $L = $L instanceof $T<?> $L ? $L.stream().map($L -> $T.val($L, $L).getValue()).toList() : null",
                    ParameterizedTypeName.get(LIST, type.box()), local, read, LIST, l, l, e, DSL, e, column);
                compare = CodeBlock.of("$L.in($L)", column, local);
                guard = term.present() ? CodeBlock.of("!$L.isEmpty()", local)
                    : CodeBlock.of("$L != null && !$L.isEmpty()", local, local);
            } else {
                method.addStatement("$T $L = $T.val($L, $L).getValue()", type.box(), local, DSL, read, column);
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
        method.addStatement("$T<$T> conditions = new $T<>()", LIST, CONDITION, ARRAY_LIST);
        method.addCode(adds.build());
        method.addStatement("return $T.and(conditions)", DSL);
        return new Result.Emitted(method.build());
    }

    private static Optional<Reason> reasonFor(ConditionQuery.Row row, ConditionQuery.Term term) {
        if (term.nodeId()) return Optional.of(Reason.NODE_ID);
        boolean ownTable = term.column().tableSqlName().equals(row.table().sqlName());
        if (term.steps().isEmpty() ? !ownTable : term.steps().stream()
                .anyMatch(step -> step.routine() || step.pairs().isEmpty() && step.method() == null)) {
            return Optional.of(Reason.REACHED);
        }
        if ("ENUM".equals(term.typeKind())) return Optional.of(Reason.ENUM);
        if (term.throughList()) return Optional.of(Reason.THROUGH_LIST);
        if (type(term.column().bindingType()).isEmpty()) return Optional.of(Reason.UNSPELLED_TYPE);
        return Optional.empty();
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
     * The value at the end of {@code names} off the argument map, through the input objects above it,
     * {@code null} wherever one is absent.
     */
    private static CodeBlock read(List<String> names, int[] counter) {
        if (names.size() == 1) {
            return CodeBlock.of("args.get($S)", names.getFirst());
        }
        var test = CodeBlock.builder();
        String previous = null;
        for (int i = 0; i < names.size() - 1; i++) {
            var map = "m" + ++counter[0];
            var source = previous == null ? CodeBlock.of("args.get($S)", names.get(i))
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
