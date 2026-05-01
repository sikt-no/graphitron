package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.Handler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.SqlStateHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.VendorCodeHandler;
import no.sikt.graphitron.rewrite.model.WithErrorChannel;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the {@code ErrorMappings} class emitted at
 * {@code <outputPackage>.schema.ErrorMappings}, once per code-generation run.
 *
 * <p>One {@code public static final ErrorRouter.Mapping[]} constant per distinct
 * fetcher channel — the catch-arm wrapper that lands with the per-fetcher try/catch
 * (R12 §3) references the constant directly. Walks every {@link WithErrorChannel}
 * field with a resolved {@link ErrorChannel} and groups by
 * {@link ErrorChannel#mappingsConstantName}; identical mapping shapes share a constant.
 *
 * <p>Two channels with the same {@code mappingsConstantName} but different mapping
 * lists currently produce a hard error; the §3 hash-suffix dedup
 * ({@code FILM_PAYLOAD_A1B2C3D4}) is a follow-up addition. No production fixture
 * exercises the collision today.
 *
 * <p>Spec: {@code error-handling-parity.md} §3, "Drop the custom ExecutionStrategy.
 * Wrap try/catch at the fetcher" — {@code ErrorMappings} subsection.
 */
public final class ErrorMappingsClassGenerator {

    public static final String CLASS_NAME = "ErrorMappings";

    private ErrorMappingsClassGenerator() {}

    @DependsOnClassifierCheck(
        key = "error-channel.mappings-constant",
        reliesOn = "groups channels by ErrorChannel.mappingsConstantName, treating identical "
            + "names as guaranteed-shared shapes; the mismatch path throws because the classifier "
            + "would have hash-suffixed colliding shapes if the dedup logic were live.")
    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        var schemaPackage = outputPackage.isEmpty() ? "" : outputPackage + ".schema";
        var errorRouter = ClassName.get(schemaPackage, ErrorRouterClassGenerator.CLASS_NAME);
        var mapping = errorRouter.nestedClass(ErrorRouterClassGenerator.MAPPING_INTERFACE);
        var mappingArray = ArrayTypeName.of(mapping);

        // Group every classified channel by its mappingsConstantName, preserving first-seen order.
        var byConstant = new LinkedHashMap<String, ErrorChannel>();
        for (var field : schema.fields().values()) {
            if (!(field instanceof WithErrorChannel withChannel)) continue;
            withChannel.errorChannel().ifPresent(channel -> {
                var prior = byConstant.putIfAbsent(channel.mappingsConstantName(), channel);
                if (prior != null && !sameHandlerShape(prior, channel)) {
                    throw new IllegalStateException(
                        "ErrorMappings: two channels share the constant '"
                            + channel.mappingsConstantName()
                            + "' but declare different handler lists. The §3 hash-suffix"
                            + " dedup (e.g. " + channel.mappingsConstantName() + "_A1B2C3D4)"
                            + " is a follow-up addition; if you have hit this in a real"
                            + " schema, the fix is to differentiate the channels at"
                            + " classify time.");
                }
            });
        }

        var builder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Channel-flattened {@code Mapping[]} dispatch tables, one constant per\n"
                + "distinct fetcher channel. The per-fetcher try/catch wrapper passes the\n"
                + "constant directly to {@link $T#dispatch}; identical channel shapes\n"
                + "(same payload class, same handler list) dedup to a single constant so a\n"
                + "schema with N fetchers mapping K {@code @error} types produces K mapping\n"
                + "instances total instead of K·N.\n", errorRouter);

        // Private no-arg constructor — utility class.
        builder.addMethod(no.sikt.graphitron.javapoet.MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build());

        for (var entry : byConstant.entrySet()) {
            String constantName = entry.getKey();
            var channel = entry.getValue();
            CodeBlock initializer = buildMappingArrayInitializer(channel, errorRouter);
            builder.addField(FieldSpec.builder(mappingArray, constantName,
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(initializer)
                .addJavadoc("Dispatch table for fetchers returning {@code $L}.\n",
                    channel.payloadClass().simpleName())
                .build());
        }

        return List.of(builder.build());
    }

    /**
     * Produces a {@code new ErrorRouter.Mapping[] { ... }} initializer where each entry mirrors
     * one {@link Handler} on the channel's flattened handler list (source order:
     * {@code @error} type declaration order, then {@code handlers} array order within each type).
     *
     * <p>Handlers carried on an {@link ErrorType} whose {@code classFqn} is empty are skipped
     * silently: the {@code (List<String>, String) -> GraphitronError} factory needs a backing
     * class. The fallthrough produces an empty {@code Mapping[]} for channels whose every
     * mapped {@code @error} type lacks a backing class; the dispatch arm then routes through
     * the unmatched/redact arm, which is the safe disposition until R12's class-resolution
     * lift lands.
     */
    private static CodeBlock buildMappingArrayInitializer(ErrorChannel channel, ClassName errorRouter) {
        var arr = CodeBlock.builder().add("new $T[] {\n", errorRouter.nestedClass(ErrorRouterClassGenerator.MAPPING_INTERFACE));
        boolean first = true;
        for (var errType : channel.mappedErrorTypes()) {
            if (errType.classFqn().isEmpty()) continue;
            var errClass = ClassName.bestGuess(errType.classFqn().get());
            for (var handler : errType.handlers()) {
                if (!first) arr.add(",\n");
                arr.add("    ").add(buildMappingEntry(handler, errClass, errorRouter));
                first = false;
            }
        }
        arr.add("\n}");
        return arr.build();
    }

    private static CodeBlock buildMappingEntry(Handler handler, ClassName errClass, ClassName errorRouter) {
        var exceptionMapping = errorRouter.nestedClass(ErrorRouterClassGenerator.EXCEPTION_MAPPING);
        var sqlStateMapping = errorRouter.nestedClass(ErrorRouterClassGenerator.SQL_STATE_MAPPING);
        var vendorCodeMapping = errorRouter.nestedClass(ErrorRouterClassGenerator.VENDOR_CODE_MAPPING);
        var validationMapping = errorRouter.nestedClass(ErrorRouterClassGenerator.VALIDATION_MAPPING);

        return switch (handler) {
            case ExceptionHandler eh -> {
                var excClass = bestGuessOrObject(eh.exceptionClassName());
                yield CodeBlock.of("new $T($T.class, $L, $L, $T::new)",
                    exceptionMapping,
                    excClass,
                    literalOrNull(eh.matches().orElse(null)),
                    literalOrNull(eh.description().orElse(null)),
                    errClass);
            }
            case SqlStateHandler sh ->
                CodeBlock.of("new $T($S, $L, $L, $T::new)",
                    sqlStateMapping,
                    sh.sqlState(),
                    literalOrNull(sh.matches().orElse(null)),
                    literalOrNull(sh.description().orElse(null)),
                    errClass);
            case VendorCodeHandler vh ->
                CodeBlock.of("new $T($S, $L, $L, $T::new)",
                    vendorCodeMapping,
                    vh.vendorCode(),
                    literalOrNull(vh.matches().orElse(null)),
                    literalOrNull(vh.description().orElse(null)),
                    errClass);
            case ValidationHandler vh ->
                CodeBlock.of("new $T($L, $T::new)",
                    validationMapping,
                    literalOrNull(vh.description().orElse(null)),
                    errClass);
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

    /** Renders a Java string literal or the bare {@code null} keyword. */
    private static CodeBlock literalOrNull(String value) {
        return value == null ? CodeBlock.of("null") : CodeBlock.of("$S", value);
    }

    /**
     * Two channels collide on the same {@code mappingsConstantName} only legitimately when their
     * flattened handler list contents (per-handler discriminator + matches + description) match.
     * Order matters because §3 dispatch is source-order-first-match.
     */
    private static boolean sameHandlerShape(ErrorChannel a, ErrorChannel b) {
        var aHandlers = flattenHandlers(a);
        var bHandlers = flattenHandlers(b);
        return aHandlers.equals(bHandlers);
    }

    private static List<HandlerKey> flattenHandlers(ErrorChannel channel) {
        var keys = new java.util.ArrayList<HandlerKey>();
        for (var et : channel.mappedErrorTypes()) {
            for (var h : et.handlers()) {
                keys.add(HandlerKey.of(et.classFqn().orElse(""), h));
            }
        }
        return keys;
    }

    private record HandlerKey(String variant, String backingClass, String discriminator,
                              String matches, String description) {
        static HandlerKey of(String backingClass, Handler h) {
            return switch (h) {
                case ExceptionHandler eh -> new HandlerKey("E", backingClass,
                    eh.exceptionClassName(), eh.matches().orElse(null), eh.description().orElse(null));
                case SqlStateHandler sh -> new HandlerKey("S", backingClass,
                    sh.sqlState(), sh.matches().orElse(null), sh.description().orElse(null));
                case VendorCodeHandler vh -> new HandlerKey("V", backingClass,
                    vh.vendorCode(), vh.matches().orElse(null), vh.description().orElse(null));
                case ValidationHandler vh -> new HandlerKey("L", backingClass,
                    "", null, vh.description().orElse(null));
            };
        }
    }
}
