package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.CodeBlock;

import java.util.List;
import java.util.Map;

/**
 * Translates a coerced GraphQL input value (as returned by graphql-java's
 * {@code getValue()} on an argument / input field / applied-directive argument) into a
 * {@link CodeBlock} that reconstructs the value at runtime.
 *
 * <p>Supports the coerced Java shapes graphql-java produces:
 * <ul>
 *   <li>{@code null}</li>
 *   <li>{@link String}, {@link Boolean}, {@link Integer}, {@link Long}, {@link Double},
 *       {@link Float}</li>
 *   <li>{@link List} of any supported element</li>
 *   <li>{@link Map} of {@code String} keys to any supported element (input-object literals)</li>
 * </ul>
 *
 * <p>Enum values show up as plain strings (graphql-java coerces them to their string name when
 * the argument type is declared as an enum, and we emit the same string unchanged; when passed
 * to {@code valueProgrammatic} on a target whose type is an enum, graphql-java re-coerces).
 *
 * <p>Used from {@link InputTypeGenerator} and {@link ObjectTypeGenerator} for
 * {@code .defaultValueProgrammatic(...)} emission on input fields and field arguments.
 * Applied-directive argument values do not pass through here; they go through
 * {@link AppliedDirectiveEmitter}, which preserves AST shape via graphql-java's
 * {@code ValuesResolver.valueToLiteral} so federation-jvm can read them as
 * {@link graphql.language.Value} nodes.
 */
public final class GraphQLValueEmitter {

    private GraphQLValueEmitter() {}

    public static CodeBlock emit(Object value) {
        if (value == null)                 return CodeBlock.of("null");
        if (value instanceof String s)     return CodeBlock.of("$S", s);
        if (value instanceof Boolean b)    return CodeBlock.of("$L", b);
        if (value instanceof Integer i)    return CodeBlock.of("$L", i);
        if (value instanceof Long l)       return CodeBlock.of("$LL", l);
        if (value instanceof Double d)     return CodeBlock.of("$LD", d);
        if (value instanceof Float f)      return CodeBlock.of("$LF", f);
        if (value instanceof List<?> list) return emitList(list);
        if (value instanceof Map<?, ?> m)  return emitMap(m);
        // Fall back: quote the toString. Keeps emission total; downstream graphql-java coercion
        // decides whether the value is acceptable.
        return CodeBlock.of("$S", value.toString());
    }

    private static CodeBlock emitList(List<?> list) {
        if (list.isEmpty()) return CodeBlock.of("java.util.List.of()");
        var b = CodeBlock.builder().add("java.util.List.of(");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) b.add(", ");
            b.add(emit(list.get(i)));
        }
        return b.add(")").build();
    }

    private static CodeBlock emitMap(Map<?, ?> map) {
        if (map.isEmpty()) return CodeBlock.of("java.util.Map.of()");
        var b = CodeBlock.builder().add("java.util.Map.of(");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) b.add(", ");
            first = false;
            b.add("$S, ", entry.getKey().toString());
            b.add(emit(entry.getValue()));
        }
        return b.add(")").build();
    }
}
