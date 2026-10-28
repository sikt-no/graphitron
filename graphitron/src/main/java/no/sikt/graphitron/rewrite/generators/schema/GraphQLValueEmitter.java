package no.sikt.graphitron.rewrite.generators.schema;

import graphql.language.AstPrinter;
import graphql.language.Value;
import graphql.schema.InputValueWithState;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import java.util.List;
import java.util.Map;

/**
 * Translates GraphQL input values into {@link CodeBlock}s that reconstruct them at runtime.
 *
 * <p>{@link #emitDefault} is the entry point for input-field, field-argument and
 * directive-definition-argument defaults ({@link InputTypeGenerator},
 * {@link ObjectTypeGenerator}, {@link DirectiveDefinitionEmitter}). A default authored in SDL is
 * held in the literal state, so it is emitted as the authored AST and re-parsed at consumer
 * class-init via {@link graphql.parser.Parser#parseValue}; the real scalar's
 * {@code parseLiteral} then coerces it at runtime, exactly as for the same literal in a query.
 * This matters for custom scalars, which are wired as stand-in coercers at build time and so
 * cannot be coerced to a Java value here. The emitted schema is derived from the store, which
 * carries every default as SDL text, so the synthesised connection {@code first} argument takes
 * the literal path too; a programmatic-state default is emitted through {@link #emit}.
 *
 * <p>{@link #emit} handles Java values set programmatically: strings, booleans,
 * numbers, and lists and maps of those. Any other shape is a generator defect and throws, so it
 * fails the build rather than the consumer's schema validation.
 *
 * <p>Applied-directive argument values go through {@link AppliedDirectiveEmitter}, which
 * preserves AST shape via graphql-java's {@code ValuesResolver.valueToLiteral} so
 * federation-jvm can read them as {@link Value} nodes.
 */
public final class GraphQLValueEmitter {

    static final ClassName PARSER = ClassName.get("graphql.parser", "Parser");

    private GraphQLValueEmitter() {}

    /**
     * Emits the builder call that sets {@code state} as a default value:
     * {@code defaultValueLiteral(Parser.parseValue("<literal>"))} for a literal-state default,
     * {@code defaultValueProgrammatic(<value>)} for a programmatic one. {@code coordinate}
     * names the defaulted element in the failure message for any other state.
     */
    public static CodeBlock emitDefault(InputValueWithState state, String coordinate) {
        if (state.isLiteral()) {
            return CodeBlock.of("defaultValueLiteral($T.parseValue($S))",
                PARSER, AstPrinter.printAst((Value<?>) state.getValue()));
        }
        if (state.isInternal()) {
            return CodeBlock.of("defaultValueProgrammatic($L)", emit(state.getValue()));
        }
        throw new IllegalStateException(
            "Cannot emit default value for " + coordinate + ": unsupported state " + state);
    }

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
        throw new IllegalStateException(
            "Cannot emit value of type " + value.getClass().getName() + ": " + value);
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
