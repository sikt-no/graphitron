package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Emits the decode of a generated scalar's value at a call, from one of
 * {@link GeneratedScalarQuery#decodes}: the wire string read off the fetch, decoded once into a
 * local of the kind's record by the generated decoder, which checks it against the schema the
 * service serves, and every requirement the argument's {@code @assertType}s state checked on the
 * record right after. A parameter an argMapping hands a component of the value then reads that
 * component off the local. A wrong value or a failed requirement throws the generated
 * {@code GraphitronClientException}, its message naming the argument, before the call runs.
 *
 * <pre>{@code
 * String decodedInput_MottakerstiWire = env.getArgument("input") instanceof Map<?, ?> map1
 *     ? (String) map1.get("mottakersti") : null;
 * GeneratedScalars.SchemaPath decodedInput_Mottakersti = GeneratedScalars.schemaPath(
 *     env.getGraphQLSchema(), "input.mottakersti", decodedInput_MottakerstiWire);
 * GeneratedScalars.require(decodedInput_Mottakersti.endType() == null
 *     || "Soker".equals(decodedInput_Mottakersti.endType()),
 *     "input.mottakersti: the path's endType `" + decodedInput_Mottakersti.endType() + "` is not `Soker`");
 * String pMottakerSlutt = decodedInput_Mottakersti.endType();
 * }</pre>
 *
 * <p>Its own sink, shared by the service fills ({@link FillEmitter}), the routine write
 * ({@link RoutineWriteEmitter}) and a child service's fetcher, which runs the same decode before it
 * loads, so a wrong value fails the field it was given on. A null value decodes to a record of
 * nulls, and a requirement on a null component holds.
 */
public final class GeneratedScalarDecodeEmitter {

    private GeneratedScalarDecodeEmitter() {}

    private static final ClassName STRING = ClassName.get("java.lang", "String");

    /** The local {@code decode}'s record is held in, named after the argument path. */
    public static String local(GeneratedScalarQuery.Decode decode) {
        return "decoded" + decode.path().stream()
            .map(name -> name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1))
            .collect(Collectors.joining("_"));
    }

    /**
     * The statements decoding {@code decode}'s value into {@code local} and checking its
     * requirements, each a statement without its semicolon, reading the fetch as {@code env}.
     * {@code scalars} is the generated {@code GeneratedScalars} class.
     */
    public static List<CodeBlock> statements(GeneratedScalarQuery.Decode decode, String local, ClassName scalars) {
        var argument = String.join(".", decode.path());
        var wire = local + "Wire";
        var out = new ArrayList<CodeBlock>();
        out.add(FillEmitter.argument(STRING, wire, decode.path()));
        out.add(CodeBlock.of("$T $L = $T.$L(env.getGraphQLSchema(), $S, $L)",
            GeneratedScalarEmitter.recordClass(scalars, decode.kind()), local, scalars,
            GeneratedScalarEmitter.decoder(decode.kind()), argument, wire));
        for (var requirement : decode.requirements()) {
            var component = CodeBlock.of("$L.$L()", local, requirement.component());
            out.add(CodeBlock.of("$T.$L($L == null || $L, $L)", scalars, GeneratedScalarEmitter.REQUIRE, component,
                predicate(requirement.condition(), component, scalars),
                message(argument, decode.kind(), requirement, component)));
        }
        return out;
    }

    /**
     * The decodes of every component landing among {@code decodes}, once per argument, as one block
     * of statements: what a child service's fetcher runs before it loads, the values themselves being
     * decoded again where the batch's call hands them over.
     */
    public static CodeBlock checks(List<GeneratedScalarQuery.Decode> decodes, ClassName scalars) {
        var block = CodeBlock.builder();
        var seen = new java.util.HashSet<String>();
        for (var decode : decodes) {
            if (seen.add(decode.coordinate())) {
                statements(decode, local(decode), scalars).forEach(block::addStatement);
            }
        }
        return block.build();
    }

    /** The component {@code name} of the record held in {@code local}. */
    public static CodeBlock component(String local, String name) {
        return CodeBlock.of("$L.$L()", local, name);
    }

    /**
     * The condition as a Java boolean over the component's type name, {@code component}: {@code is}
     * compares names, {@code implements} asks the served schema, and the combinators are
     * {@code ||}, {@code &&} and {@code !}. An empty {@code anyOf} holds for nothing and an empty
     * {@code allOf} for everything.
     */
    static CodeBlock predicate(GeneratedScalarQuery.Condition condition, CodeBlock component, ClassName scalars) {
        return switch (condition) {
            case GeneratedScalarQuery.Condition.Is is -> CodeBlock.of("$S.equals($L)", is.typeName(), component);
            case GeneratedScalarQuery.Condition.Implements implementing -> CodeBlock.of("$T.$L(env.getGraphQLSchema(), $L, $S)",
                scalars, GeneratedScalarEmitter.IMPLEMENTS_INTERFACE, component, implementing.interfaceName());
            case GeneratedScalarQuery.Condition.AnyOf any -> any.conditions().isEmpty() ? CodeBlock.of("false")
                : CodeBlock.of("($L)", CodeBlock.join(any.conditions().stream()
                    .map(c -> predicate(c, component, scalars)).toList(), " || "));
            case GeneratedScalarQuery.Condition.AllOf all -> all.conditions().isEmpty() ? CodeBlock.of("true")
                : CodeBlock.of("($L)", CodeBlock.join(all.conditions().stream()
                    .map(c -> predicate(c, component, scalars)).toList(), " && "));
            case GeneratedScalarQuery.Condition.Not not -> CodeBlock.of("!$L", parenthesised(predicate(not.condition(),
                component, scalars), not.condition()));
        };
    }

    /** An operand of {@code !}, parenthesised unless it already is or is a single call. */
    private static CodeBlock parenthesised(CodeBlock operand, GeneratedScalarQuery.Condition condition) {
        return condition instanceof GeneratedScalarQuery.Condition.AnyOf
            || condition instanceof GeneratedScalarQuery.Condition.AllOf
            ? operand : CodeBlock.of("($L)", operand);
    }

    /**
     * The refusal's message: the argument, then the component and the type it names, then what the
     * requirement asks, as {@code input.mottakersti: the path's endType `Saksbehandler` is not `Soker`}.
     */
    private static CodeBlock message(String argument, String kind, GeneratedScalarQuery.Requirement requirement,
                                     CodeBlock component) {
        var value = "SCHEMA_PATH".equals(kind) ? "path" : "type name";
        return CodeBlock.of("$S + $L + $S",
            argument + ": the " + value + "'s " + requirement.component() + " `", component,
            "` " + asked(requirement.condition()));
    }

    /** What a failed requirement says the type is not: {@code is not `Soker`}, {@code does not implement `Node`}. */
    private static String asked(GeneratedScalarQuery.Condition condition) {
        return switch (condition) {
            case GeneratedScalarQuery.Condition.Is is -> "is not `" + is.typeName() + "`";
            case GeneratedScalarQuery.Condition.Implements implementing ->
                "does not implement `" + implementing.interfaceName() + "`";
            default -> "does not meet " + written(condition);
        };
    }

    /** The condition as written in the schema, for a message about a combinator. */
    private static String written(GeneratedScalarQuery.Condition condition) {
        return switch (condition) {
            case GeneratedScalarQuery.Condition.Is is -> "{is: \"" + is.typeName() + "\"}";
            case GeneratedScalarQuery.Condition.Implements implementing ->
                "{implements: \"" + implementing.interfaceName() + "\"}";
            case GeneratedScalarQuery.Condition.AnyOf any -> "{anyOf: [" + any.conditions().stream()
                .map(GeneratedScalarDecodeEmitter::written).collect(Collectors.joining(", ")) + "]}";
            case GeneratedScalarQuery.Condition.AllOf all -> "{allOf: [" + all.conditions().stream()
                .map(GeneratedScalarDecodeEmitter::written).collect(Collectors.joining(", ")) + "]}";
            case GeneratedScalarQuery.Condition.Not not -> "{not: " + written(not.condition()) + "}";
        };
    }
}
