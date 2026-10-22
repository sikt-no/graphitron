package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.emit.ErrorChannelRelation;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ClientMessage;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.DatabaseHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.Handler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates the {@code ErrorMappings} class emitted at
 * {@code <outputPackage>.schema.ErrorMappings}, once per code-generation run. It carries the
 * {@code ErrorRouter.Mapping[]} dispatch tables at two grains, and the second grain is the point
 * of the class rather than an implementation detail, so both are named here.
 *
 * <ul>
 *   <li><b>Per {@code @error} type</b>, in the nested {@link #BY_TYPE_HOLDER} holder: one
 *       {@code Mapping[]} constant per {@code @error} type, keyed on SCREAMING_SNAKE of the SDL
 *       type name ({@code ByType.FILM_LOOKUP_INVALID}). This is the definition-keyed grain, and
 *       it is what {@code <ErrorType>Fetchers.message} walks to resolve a handler's authored
 *       {@code description:} for a matched source.</li>
 *   <li><b>Per outcome type</b>, as top-level constants named by {@link #channelConstantName}:
 *       the ordered concatenation of the per-type arrays of the outcome type's errors field
 *       members. This is the use-keyed grain the per-fetcher try/catch wrapper passes to
 *       {@code ErrorRouter.claim}. Every producer catching into one outcome type catches into the
 *       same errors field, so they share its constant.</li>
 * </ul>
 *
 * <p>The outcome types and their members are read from the store
 * ({@link ErrorChannelRelation}); the per-type grain is the model's own {@code errorTypes()}
 * fold, the population {@code FetchersPlanner} mints a fetchers class for, and every member a
 * channel names must be in it.
 *
 * <p>The two grains are kept in separate namespaces so an outcome type and an {@code @error} type
 * folding to one SCREAMING_SNAKE name cannot emit one field twice.
 */
public final class ErrorMappingsClassGenerator {

    public static final String CLASS_NAME = "ErrorMappings";

    /** The nested holder carrying the definition-keyed {@code Mapping[]} constants. */
    public static final String BY_TYPE_HOLDER = "ByType";

    /** The private varargs helper the channel constants concatenate through. */
    private static final String CONCAT_METHOD = "concat";

    private ErrorMappingsClassGenerator() {}

    /**
     * The emitted {@code ErrorMappings} class, resolved through the plan's naming vocabulary so
     * this family has one derivation of where the class lives rather than an open-coded
     * {@code outputPackage + ".schema"}.
     */
    public static ClassName mappingsClass(String outputPackage) {
        var unit = new GeneratedUnits(outputPackage).errorMappings();
        return ClassName.get(unit.packageName(), unit.simpleName());
    }

    /**
     * The {@link #BY_TYPE_HOLDER} constant name for one {@code @error} type: SCREAMING_SNAKE of
     * the SDL type name. One spelling for the mint here and for the read at
     * {@code ErrorTypeFetcherClassGenerator}.
     */
    public static String byTypeConstantName(String errorTypeName) {
        return GeneratedUnits.screamingSnake(errorTypeName);
    }

    /** The constant an outcome type's dispatch table is emitted under, and every catch arm names. */
    public static String channelConstantName(String outcomeTypeName) {
        return GeneratedUnits.mappingsConstant(outcomeTypeName);
    }

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage,
                                          ErrorChannelRelation channels) {
        var errorRouter = ErrorRouterClassGenerator.routerClass(outputPackage);
        var mapping = errorRouter.nestedClass(ErrorRouterClassGenerator.MAPPING_INTERFACE);
        var mappingArray = ArrayTypeName.of(mapping);

        var errorTypes = new TreeMap<>(schema.errorTypes());
        channels.membersByOutcome().forEach((outcome, members) -> members.forEach(member -> {
            if (!errorTypes.containsKey(member)) {
                throw new IllegalStateException("ErrorMappings: the store's errors field on '"
                    + outcome + "' holds '" + member + "', which the schema registers as no @error"
                    + " type; the store and the classified schema have drifted");
            }
        }));

        var builder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("{@code Mapping[]} dispatch tables at two grains. {@link $L} holds one\n"
                + "constant per {@code @error} type, which is where an authored\n"
                + "{@code description:} lives and what each {@code <ErrorType>Fetchers.message}\n"
                + "walks; the constants on this class hold one per outcome type, each the ordered\n"
                + "concatenation of the per-type arrays its errors field holds, and the per-fetcher\n"
                + "try/catch wrapper passes those to {@link $T#dispatch}. Every fetcher returning\n"
                + "one outcome type shares its constant.\n",
                BY_TYPE_HOLDER, errorRouter);

        // Private no-arg constructor — utility class.
        builder.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build());

        if (!errorTypes.isEmpty()) {
            builder.addType(buildByTypeHolder(errorTypes, errorRouter, mapping, mappingArray));
        }

        var byTypeHolder = ClassName.get("", BY_TYPE_HOLDER);
        channels.membersByOutcome().forEach((outcome, members) ->
            builder.addField(FieldSpec.builder(mappingArray, channelConstantName(outcome),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(buildChannelInitializer(members, byTypeHolder))
                .addJavadoc("Dispatch table for fetchers returning {@code $L}: its errors field's\n"
                    + "{@code @error} types' {@link $L} arrays, concatenated in match order.\n",
                    outcome, BY_TYPE_HOLDER)
                .build()));

        if (!channels.membersByOutcome().isEmpty()) {
            builder.addMethod(buildConcatMethod(mapping, mappingArray));
        }

        return List.of(builder.build());
    }

    /**
     * The nested holder: one {@code Mapping[]} constant per {@code @error} type, initialised from
     * that type's own {@code handlers()} in declaration order. Channel reach is not a
     * precondition, so an {@code @error} type no channel maps still gets a constant and its
     * fetchers class names something that exists.
     */
    private static TypeSpec buildByTypeHolder(Map<String, ErrorType> errorTypes,
                                              ClassName errorRouter, ClassName mapping,
                                              ArrayTypeName mappingArray) {
        var holder = TypeSpec.classBuilder(BY_TYPE_HOLDER)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One {@code Mapping[]} per {@code @error} type, keyed on the SDL type name.\n"
                + "This is the grain an authored {@code description:} is declared at, so it is the\n"
                + "grain {@code <ErrorType>Fetchers.message} resolves against; the enclosing class's\n"
                + "channel constants concatenate these. Nested rather than flat because a payload\n"
                + "class simple name and an {@code @error} type name can produce the same\n"
                + "SCREAMING_SNAKE identifier.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());

        for (var et : errorTypes.values()) {
            holder.addField(FieldSpec.builder(mappingArray, byTypeConstantName(et.name()),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(buildMappingArrayInitializer(et, errorRouter, mapping))
                .addJavadoc("Dispatch entries declared by {@code @error type $L}.\n", et.name())
                .build());
        }
        return holder.build();
    }

    /**
     * Produces a {@code new ErrorRouter.Mapping[] { ... }} initializer where each entry mirrors
     * one dispatch-capable {@link Handler} on {@code errorType}, in {@code handlers} array order.
     *
     * <p>Every dispatch-capable handler emits a {@code Mapping}: the matched throwable itself
     * goes into the errors list, so no per-mapping factory is needed. {@link ValidationHandler}
     * entries produce no {@code Mapping}: the wrapper's validator pre-step routes
     * {@code GraphQLError} sources directly into the errors slot, bypassing the dispatcher. A
     * type whose only handler is a {@link ValidationHandler} therefore gets an empty array.
     */
    private static CodeBlock buildMappingArrayInitializer(ErrorType errorType, ClassName errorRouter,
                                                          ClassName mapping) {
        var arr = CodeBlock.builder().add("new $T[] {\n", mapping);
        boolean first = true;
        for (var handler : errorType.handlers()) {
            if (handler instanceof ValidationHandler) continue;
            if (!first) arr.add(",\n");
            arr.add("    ").add(buildMappingEntry(handler, errorRouter));
            first = false;
        }
        arr.add("\n}");
        return arr.build();
    }

    /**
     * A channel constant's initializer: {@code concat(ByType.A, ByType.B)} over the outcome
     * type's {@code @error} members in match order. Always a concatenation, even for a
     * single-type channel, so no two constants alias one array instance.
     */
    private static CodeBlock buildChannelInitializer(List<String> members, ClassName byTypeHolder) {
        var call = CodeBlock.builder().add("$L(", CONCAT_METHOD);
        boolean first = true;
        for (var member : members) {
            if (!first) call.add(", ");
            call.add("$T.$L", byTypeHolder, byTypeConstantName(member));
            first = false;
        }
        return call.add(")").build();
    }

    /**
     * The varargs flattener the channel constants read through. Java array initializers do not
     * concatenate, so the two-grain composition needs a method; it is private because nothing
     * outside the emitted class composes these.
     */
    private static MethodSpec buildConcatMethod(ClassName mapping, ArrayTypeName mappingArray) {
        return MethodSpec.methodBuilder(CONCAT_METHOD)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .varargs(true)
            .returns(mappingArray)
            .addParameter(ArrayTypeName.of(mappingArray), "parts")
            .addJavadoc("Flattens the per-type arrays a channel maps into one dispatch table,\n"
                + "preserving declaration order. Copies rather than aliases, so a channel constant\n"
                + "and a {@link $L} constant never share an array instance.\n", BY_TYPE_HOLDER)
            .addStatement("int total = 0")
            .beginControlFlow("for ($T part : parts)", mappingArray)
            .addStatement("total += part.length")
            .endControlFlow()
            .addStatement("$T flattened = new $T[total]", mappingArray, mapping)
            .addStatement("int at = 0")
            .beginControlFlow("for ($T part : parts)", mappingArray)
            .addStatement("System.arraycopy(part, 0, flattened, at, part.length)")
            .addStatement("at += part.length")
            .endControlFlow()
            .addStatement("return flattened")
            .build();
    }

    private static CodeBlock buildMappingEntry(Handler handler, ClassName errorRouter) {
        var exceptionMapping = errorRouter.nestedClass(ErrorRouterClassGenerator.EXCEPTION_MAPPING);
        var databaseMapping = errorRouter.nestedClass(ErrorRouterClassGenerator.DATABASE_MAPPING);

        return switch (handler) {
            case ExceptionHandler eh -> {
                var excClass = bestGuessOrObject(eh.exceptionClassName());
                yield CodeBlock.of("new $T($T.class, $L, $L)",
                    exceptionMapping,
                    excClass,
                    literalOrNull(eh.matches().orElse(null)),
                    descriptionLiteral(eh.clientMessage()));
            }
            case DatabaseHandler dh -> {
                String sqlState = dh.discriminator() instanceof DatabaseHandler.Discriminator.SqlState s
                    ? s.sqlState() : null;
                String vendorCode = dh.discriminator() instanceof DatabaseHandler.Discriminator.VendorCode v
                    ? v.vendorCode() : null;
                yield CodeBlock.of("new $T($L, $L, $L, $L)",
                    databaseMapping,
                    literalOrNull(sqlState),
                    literalOrNull(vendorCode),
                    literalOrNull(dh.matches().orElse(null)),
                    descriptionLiteral(dh.clientMessage()));
            }
            case ValidationHandler vh -> throw new IllegalStateException(
                "ValidationHandler should be skipped by buildMappingArrayInitializer; reached "
                    + "buildMappingEntry with " + vh);
        };
    }

    /** Best-guess a {@link ClassName} for a binary class string. Falls back to {@link Object} when malformed. */
    private static ClassName bestGuessOrObject(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return ClassName.get(Object.class);
        try {
            return ClassName.bestGuess(binaryName);
        } catch (IllegalArgumentException e) {
            return ClassName.get(Object.class);
        }
    }

    /** The {@code description} constructor argument: the authored string, or bare {@code null}. */
    private static CodeBlock descriptionLiteral(ClientMessage clientMessage) {
        return switch (clientMessage) {
            case ClientMessage.Static s -> CodeBlock.of("$S", s.message());
            case ClientMessage.FromSource ignored -> CodeBlock.of("null");
        };
    }

    /** Renders a Java string literal or the bare {@code null} keyword. */
    private static CodeBlock literalOrNull(String value) {
        return value == null ? CodeBlock.of("null") : CodeBlock.of("$S", value);
    }
}
