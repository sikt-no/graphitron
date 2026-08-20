package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ClientMessage;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.Handler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.SqlStateHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.VendorCodeHandler;
import no.sikt.graphitron.rewrite.model.WithErrorChannel;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *   <li><b>Per fetcher channel</b>, as top-level constants keyed on
 *       {@link ErrorChannel#mappingsConstantName}: the ordered concatenation of the per-type
 *       arrays the channel maps. This is the use-keyed grain the per-fetcher try/catch wrapper
 *       passes to {@code ErrorRouter.dispatch}. Channels with identical declarations share one
 *       constant, so a schema with N fetchers mapping K {@code @error} types produces K mapping
 *       instances rather than K·N.</li>
 * </ul>
 *
 * <p>The two grains are kept in separate namespaces on purpose. Channel constant names come from
 * three sources (an SDL outcome type name, a payload class simple name, a wrapper SDL type name),
 * and a payload class simple name can collide with an {@code @error} type name: a
 * {@code com.example.NotAllowed} payload alongside an {@code @error type NotAllowed} would mint
 * {@code NOT_ALLOWED} twice, and a duplicate field is invalid generated Java rather than a
 * diagnostic. Nesting the per-type constants makes that collision-free by construction, so the
 * mint needs no clash check and no invented suffix.
 *
 * <p>Membership for the per-type grain is the model's own {@code errorTypes()} fold, unioned with
 * every type any channel maps. That fold alone is the population
 * {@code TypeUnitCommands.fetchersRows} mints a fetchers class for, so it is the population whose
 * {@code message} body names a {@code ByType} constant; the channel side is unioned in so the
 * concatenation is total no matter which population is the wider, rather than resting on an
 * argument that the two agree. Array content is determined by the {@code @error} type's name (the
 * {@code ErrorIndex} fixed point resolves every union member through a name-keyed map), so a type
 * reached from both populations contributes one constant; a name carrying two different handler
 * lists is a classifier bug and fails loudly here.
 *
 * <p>Channel constant names arrive already resolved: the classifier-side dedup pass
 * ({@code MappingsConstantNameDedup}) has applied any hash suffix before an emitter sees the
 * schema, so a shared constant name here guarantees a shared handler shape and a mismatch is an
 * internal classifier bug rather than an author-facing error.
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

    /** The nested {@code ErrorMappings.ByType} holder of the definition-keyed constants. */
    public static ClassName byTypeHolder(String outputPackage) {
        return mappingsClass(outputPackage).nestedClass(BY_TYPE_HOLDER);
    }

    /**
     * The {@link #BY_TYPE_HOLDER} constant name for one {@code @error} type: SCREAMING_SNAKE of
     * the SDL type name. One spelling for the mint here and for the read at
     * {@code ErrorTypeFetcherClassGenerator}.
     */
    public static String byTypeConstantName(String errorTypeName) {
        if (errorTypeName == null || errorTypeName.isEmpty()) return errorTypeName;
        var sb = new StringBuilder(errorTypeName.length() + 4);
        for (int i = 0; i < errorTypeName.length(); i++) {
            char c = errorTypeName.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(errorTypeName.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        var errorRouter = ErrorRouterClassGenerator.routerClass(outputPackage);
        var mapping = errorRouter.nestedClass(ErrorRouterClassGenerator.MAPPING_INTERFACE);
        var mappingArray = ArrayTypeName.of(mapping);

        var errorTypes = perTypeMembership(schema);

        // Group every classified channel by its already-resolved mappingsConstantName, preserving
        // first-seen order. A mismatch under one name would mean the dedup pass missed a
        // WithErrorChannel variant; that is surfaced as an internal sanity check, not an
        // author-facing error.
        var byConstant = new LinkedHashMap<String, ErrorChannel>();
        for (var field : schema.fields().values()) {
            if (!(field instanceof WithErrorChannel withChannel)) continue;
            withChannel.errorChannel().ifPresent(channel -> {
                var prior = byConstant.putIfAbsent(channel.mappingsConstantName(), channel);
                if (prior != null && !sameHandlerShape(prior, channel)) {
                    throw new IllegalStateException(
                        "ErrorMappings: two channels share the constant '"
                            + channel.mappingsConstantName()
                            + "' but declare different handler lists. The hash-suffix dedup"
                            + " pass should have rewritten one of them; this is an internal"
                            + " classifier bug. Add the missing WithErrorChannel variant to"
                            + " MappingsConstantNameDedup.withResolvedChannel.");
                }
            });
        }

        var builder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("{@code Mapping[]} dispatch tables at two grains. {@link $L} holds one\n"
                + "constant per {@code @error} type, which is where an authored\n"
                + "{@code description:} lives and what each {@code <ErrorType>Fetchers.message}\n"
                + "walks; the constants on this class hold one per distinct fetcher channel, each\n"
                + "the ordered concatenation of the per-type arrays it maps, and the per-fetcher\n"
                + "try/catch wrapper passes those to {@link $T#dispatch}. Channels with identical\n"
                + "declarations share a constant, so a schema with N fetchers mapping K\n"
                + "{@code @error} types produces K mapping instances total instead of K·N.\n",
                BY_TYPE_HOLDER, errorRouter);

        // Private no-arg constructor — utility class.
        builder.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build());

        if (!errorTypes.isEmpty()) {
            builder.addType(buildByTypeHolder(errorTypes, errorRouter, mapping, mappingArray));
        }

        var byTypeHolder = ClassName.get("", BY_TYPE_HOLDER);
        for (var entry : byConstant.entrySet()) {
            builder.addField(FieldSpec.builder(mappingArray, entry.getKey(),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(buildChannelInitializer(entry.getValue(), byTypeHolder))
                .addJavadoc("Dispatch table for fetchers returning {@code $L}: the mapped\n"
                    + "{@code @error} types' {@link $L} arrays, concatenated in declaration order.\n",
                    channelLabel(entry.getValue()), BY_TYPE_HOLDER)
                .build());
        }

        if (!byConstant.isEmpty()) {
            builder.addMethod(buildConcatMethod(mapping, mappingArray));
        }

        return List.of(builder.build());
    }

    /**
     * The per-type grain's membership: every {@code ErrorType} the schema registers, unioned with
     * every type any channel maps, ordered by type name so the emitted field list is stable
     * regardless of which population a type came from. Two rows under one name must agree; they
     * do by construction (an {@code @error} type name determines its handler list through the
     * {@code ErrorIndex} fixed point), and a disagreement is a classifier bug.
     */
    private static Map<String, ErrorType> perTypeMembership(GraphitronSchema schema) {
        var byName = new TreeMap<String, ErrorType>();
        schema.errorTypes().values().forEach(et -> admit(byName, et));
        for (var field : schema.fields().values()) {
            if (!(field instanceof WithErrorChannel withChannel)) continue;
            withChannel.errorChannel()
                .ifPresent(channel -> channel.mappedErrorTypes().forEach(et -> admit(byName, et)));
        }
        return byName;
    }

    private static void admit(Map<String, ErrorType> byName, ErrorType et) {
        var prior = byName.putIfAbsent(et.name(), et);
        if (prior != null && !prior.handlers().equals(et.handlers())) {
            throw new IllegalStateException(
                "ErrorMappings: the @error type '" + et.name() + "' reaches the per-type mint with"
                    + " two different handler lists. An @error type name determines its handler"
                    + " list through the ErrorIndex fixed point, so this is an internal classifier"
                    + " bug: the schema registry and a channel's mappedErrorTypes have diverged.");
        }
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
     * A channel constant's initializer: {@code concat(ByType.A, ByType.B)} over the channel's
     * {@code mappedErrorTypes()} in declaration order. Always a concatenation, even for a
     * single-type channel, so no two constants alias one array instance.
     */
    private static CodeBlock buildChannelInitializer(ErrorChannel channel, ClassName byTypeHolder) {
        var call = CodeBlock.builder().add("$L(", CONCAT_METHOD);
        boolean first = true;
        for (var errType : channel.mappedErrorTypes()) {
            if (!first) call.add(", ");
            call.add("$T.$L", byTypeHolder, byTypeConstantName(errType.name()));
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
        var sqlStateMapping = errorRouter.nestedClass(ErrorRouterClassGenerator.SQL_STATE_MAPPING);
        var vendorCodeMapping = errorRouter.nestedClass(ErrorRouterClassGenerator.VENDOR_CODE_MAPPING);

        return switch (handler) {
            case ExceptionHandler eh -> {
                var excClass = bestGuessOrObject(eh.exceptionClassName());
                yield CodeBlock.of("new $T($T.class, $L, $L)",
                    exceptionMapping,
                    excClass,
                    literalOrNull(eh.matches().orElse(null)),
                    descriptionLiteral(eh.clientMessage()));
            }
            case SqlStateHandler sh ->
                CodeBlock.of("new $T($S, $L, $L)",
                    sqlStateMapping,
                    sh.sqlState(),
                    literalOrNull(sh.matches().orElse(null)),
                    descriptionLiteral(sh.clientMessage()));
            case VendorCodeHandler vh ->
                CodeBlock.of("new $T($S, $L, $L)",
                    vendorCodeMapping,
                    vh.vendorCode(),
                    literalOrNull(vh.matches().orElse(null)),
                    descriptionLiteral(vh.clientMessage()));
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

    /**
     * Human-readable label for the channel used in the per-constant Javadoc.
     * {@link ErrorChannel.PayloadClass} carries a payload-class simple name; the
     * {@link ErrorChannel.LocalContext} arm has no payload class so we fall back to the
     * constant name itself.
     */
    private static String channelLabel(ErrorChannel channel) {
        return switch (channel) {
            case ErrorChannel.Mapped m -> m.mappingsConstantName();
            case ErrorChannel.PayloadClass p -> p.payloadClass().simpleName();
            case ErrorChannel.LocalContext lc -> lc.mappingsConstantName();
        };
    }

    /**
     * Two channels collide on the same {@code mappingsConstantName} only legitimately when their
     * flattened handler list contents (per-handler variant + discriminator + matches) match.
     * Order matters because mapping dispatch is source-order-first-match. The authored
     * {@code description:} is deliberately absent from the fingerprint: both lines also carry the
     * {@code @error} type name, and a type name determines its whole handler list, so two
     * channels differing only in a description are not constructible.
     */
    private static boolean sameHandlerShape(ErrorChannel a, ErrorChannel b) {
        return flattenHandlers(a).equals(flattenHandlers(b));
    }

    private static List<HandlerKey> flattenHandlers(ErrorChannel channel) {
        var keys = new ArrayList<HandlerKey>();
        for (var et : channel.mappedErrorTypes()) {
            for (var h : et.handlers()) {
                keys.add(HandlerKey.of(et.name(), h));
            }
        }
        return keys;
    }

    private record HandlerKey(String variant, String errorTypeName, String discriminator,
                              String matches) {
        static HandlerKey of(String errorTypeName, Handler h) {
            return switch (h) {
                case ExceptionHandler eh -> new HandlerKey("E", errorTypeName,
                    eh.exceptionClassName(), eh.matches().orElse(null));
                case SqlStateHandler sh -> new HandlerKey("S", errorTypeName,
                    sh.sqlState(), sh.matches().orElse(null));
                case VendorCodeHandler vh -> new HandlerKey("V", errorTypeName,
                    vh.vendorCode(), vh.matches().orElse(null));
                case ValidationHandler ignored -> new HandlerKey("L", errorTypeName, "", null);
            };
        }
    }
}
