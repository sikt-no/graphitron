package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ErrorFieldRead;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ClientMessage;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.DatabaseHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.Handler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the per-{@code @error}-type {@code <ErrorType>Fetchers} class, carrying the
 * {@code path} and {@code message} reads as named {@code public static} methods. The schema's
 * {@code registerFetchers} wiring references {@code <ErrorType>Fetchers::path} /
 * {@code ::message} in place of the inline lambdas previously emitted by
 * {@code GraphitronSchemaClassGenerator}.
 *
 * <p>An {@code @error} type declares the required {@code path: [String!]!} / {@code message: String!}
 * and may declare extra fields. The source object can be a {@code Throwable} (no
 * {@code getPath()}) or a {@code GraphQLError} (has {@code getPath()} / {@code getMessage()}).
 * {@code path} synthesises from the GraphQL execution context for non-{@code GraphQLError} sources
 * so the non-null contract holds regardless of handler kind. An extra field is read at runtime by
 * graphql-java's {@code PropertyDataFetcher} on its accessor base, registered by
 * {@code GraphitronSchemaClassGenerator} and not through this class, with one exception: a field
 * carrying {@code @nodeId} has to encode what that read yielded, and a bare
 * {@code PropertyDataFetcher} has nowhere to put the encode, so its read is reified here too.
 *
 * <p>{@code message} resolves in two arms, in this order:
 *
 * <ol>
 *   <li>The {@code Throwable} block, for every source that is a {@code Throwable}. It chooses
 *       the default message and hands it to {@code ErrorRouter.clientMessage}, which returns it
 *       unless the consumer's {@code FetcherErrorResponse} replaces it at runtime. Up to the
 *       last handler on this type that declares a {@code description:}, each handler emits its
 *       own {@code if (ErrorRouter.matchesSource(ByType.<TYPE>[i], thr, context)) return
 *       ErrorRouter.clientMessage(thr, ..., env);} against the type's own {@code Mapping[]}
 *       constant on {@code ErrorMappings}, so the default is chosen at build time from that
 *       handler's {@link ClientMessage} arm rather than by a runtime test on the mapping: an
 *       override arm passes the mapping's own {@code description()}, and a source-message arm
 *       passes {@code thr.getMessage()}. Reading the authored string back off the mapping rather
 *       than inlining it keeps one spelling of that string in the emitted output, and is what
 *       makes {@code Mapping.description()} a read accessor. The block always ends in
 *       {@code return ErrorRouter.clientMessage(thr, thr.getMessage(), env)}, so a type with no
 *       {@code description:} at all is the same block with no per-handler statements.</li>
 *   <li>The {@code GraphQLError} arm, {@code ge.getMessage()}, reached only by a source that is not
 *       a {@code Throwable}. This is the validation path:
 *       {@code ConstraintViolations.toGraphQLError} puts {@code GraphQLError} instances in the
 *       errors slot, and graphql-java's {@code GraphQLError} is an interface its implementations
 *       need not implement on a {@code Throwable}. Any other source reads {@code null}.</li>
 * </ol>
 *
 * <p>The {@code Throwable} block goes first whatever the handlers declare. A source can be both
 * shapes at once ({@code graphql.GraphQLError} is a plain interface, and the generated
 * {@code GraphitronClientException} extends {@code GraphqlErrorException}), so resolving the
 * {@code GraphQLError} arm first would drop an authored {@code description:} for exactly those
 * classes, and would keep them from the consumer's {@code matchedMessage} on some types and not
 * others. The order changes no default: a class that is both shapes has one {@code getMessage()}.
 *
 * <p>The walk is per-{@code @error}-type rather than channel-wide: the union {@code TypeResolver}
 * runs the same mappings, through the same {@code matchesSource}, in the same order, and this
 * method resolves the message against the type the resolver already selected. That keeps
 * {@code message} consistent with the {@code __typename} the client sees in the same selection
 * set. {@code matchesSource} re-checks a mapping against the source and against the exceptions
 * that wrapped it when dispatch claimed it, so a {@code DATABASE} {@code matches} text found only
 * in jOOQ's SQL text resolves here as it did in dispatch.
 */
public final class ErrorTypeFetcherClassGenerator {

    private static final ClassName ENV           = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphQLError");
    private static final ClassName THROWABLE     = ClassName.get(Throwable.class);
    private static final ClassName STRING_CN     = ClassName.get(String.class);
    private static final ClassName PROPERTY_FETCHER = ClassName.get("graphql.schema", "PropertyDataFetcher");

    private ErrorTypeFetcherClassGenerator() {}

    /**
     * Renders one {@code @error} type's fetchers class; membership is the type-unit relation's
     * {@code @error} fetchers row, and {@code errorMappings} and {@code errorRouter} are the refs
     * that row carries, the addresses the {@code message} body names besides its own. This method builds the fixed method
     * pair for the type the row names.
     */
    public static TypeSpec generateFor(GraphitronType.ErrorType et, ClassName errorMappings,
            ClassName errorRouter, List<ErrorFieldRead> fieldReads) {
        var cls = TypeSpec.classBuilder(et.name() + GeneratedUnits.FETCHERS_SUFFIX)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(pathMethod())
            .addMethod(messageMethod(et, errorMappings, errorRouter));
        for (var read : fieldReads) {
            if (read instanceof ErrorFieldRead.SourceAccessor accessor
                    && accessor.wire() instanceof CallSiteCompaction.NodeIdEncodeKeys encode) {
                cls.addMethod(encodedFieldMethod(accessor, encode));
            }
        }
        return cls.build();
    }

    /**
     * An extra field carrying {@code @nodeId}: read the accessor through graphql-java's own
     * property machinery, then encode. Reified here rather than registered as a bare
     * {@code PropertyDataFetcher} because there is no seam on that fetcher to apply the encode to,
     * and the read itself is graphql-java's either way.
     *
     * <p>{@code throws Exception} because {@code PropertyDataFetcher.get} declares it;
     * {@code DataFetcher.get} declares it too, so the method reference the registration emits is
     * still a {@code DataFetcher}. The null test is on the value rather than on the id: a source
     * that carries no key has an absent field, never an id encoding the absence.
     */
    private static MethodSpec encodedFieldMethod(ErrorFieldRead.SourceAccessor accessor,
            CallSiteCompaction.NodeIdEncodeKeys encode) {
        var keyType = CatalogRefs.columnType(encode.encodeMethod().paramSignature().get(0));
        return MethodSpec.methodBuilder(accessor.sdlFieldName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addException(Exception.class)
            .addStatement("$T key = ($T) $T.fetching($S).get(env)",
                keyType, keyType, PROPERTY_FETCHER, accessor.accessorBase())
            .addStatement("return key == null ? null : $T.$L(key)",
                encode.encodeMethod().encoderClass(), encode.encodeMethod().methodName())
            .build();
    }

    private static MethodSpec pathMethod() {
        return MethodSpec.methodBuilder("path")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("Object src = env.getSource()")
            .beginControlFlow("if (src instanceof $T ge)", GRAPHQL_ERROR)
            .addStatement("return ge.getPath() == null ? java.util.List.of() : "
                + "ge.getPath().stream().map($T::valueOf).toList()", STRING_CN)
            .endControlFlow()
            .addStatement("return env.getExecutionStepInfo().getPath().toList().stream()"
                + ".map($T::valueOf).toList()", STRING_CN)
            .build();
    }

    private static MethodSpec messageMethod(GraphitronType.ErrorType et, ClassName errorMappings,
            ClassName errorRouter) {
        var method = MethodSpec.methodBuilder("message")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("Object src = env.getSource()");

        method.beginControlFlow("if (src instanceof $T thr)", THROWABLE);
        int lastOverride = lastOverrideIndex(et);
        if (lastOverride >= 0) {
            var byType = errorMappings.nestedClass(ErrorMappingsClassGenerator.BY_TYPE_HOLDER);
            String constant = ErrorMappingsClassGenerator.byTypeConstantName(et.name());
            int index = 0;
            for (var handler : et.handlers()) {
                if (handler instanceof ValidationHandler) continue;
                if (index > lastOverride) break;
                // Every index up to the last override needs its own branch, including the
                // source-message ones: dispatch is first-match-wins, so skipping an earlier
                // source-message handler would let a later override fire in its place.
                switch (clientMessageOf(handler)) {
                    case ClientMessage.Static ignored -> method.addStatement(
                        "if ($T.matchesSource($T.$L[$L], thr, env.getGraphQlContext()))"
                            + " return $T.clientMessage(thr, $T.$L[$L].description(), env)",
                        errorRouter, byType, constant, index, errorRouter, byType, constant, index);
                    case ClientMessage.FromSource ignored -> method.addStatement(
                        "if ($T.matchesSource($T.$L[$L], thr, env.getGraphQlContext()))"
                            + " return $T.clientMessage(thr, thr.getMessage(), env)",
                        errorRouter, byType, constant, index, errorRouter);
                }
                index++;
            }
        }
        method.addStatement("return $T.clientMessage(thr, thr.getMessage(), env)", errorRouter)
            .endControlFlow();

        method.beginControlFlow("if (src instanceof $T ge)", GRAPHQL_ERROR)
            .addStatement("return ge.getMessage()")
            .endControlFlow();

        return method.addStatement("return null").build();
    }

    /**
     * The dispatch-table index of the last handler on this type declaring a
     * {@link ClientMessage.Static}, or {@code -1} when none does. Indices are the emitted
     * {@code Mapping[]}'s, not {@code handlers()}': the array mint skips
     * {@link ValidationHandler}, so a type mixing validation with a dispatch handler shifts them.
     * Handlers after the last override need no branch of their own, since first-match-wins makes
     * their resolution identical to the block's {@code thr.getMessage()} tail.
     */
    private static int lastOverrideIndex(GraphitronType.ErrorType et) {
        int index = 0;
        int last = -1;
        for (var handler : et.handlers()) {
            if (handler instanceof ValidationHandler) continue;
            if (clientMessageOf(handler) instanceof ClientMessage.Static) {
                last = index;
            }
            index++;
        }
        return last;
    }

    /**
     * The {@link ClientMessage} of a dispatch-capable handler. {@link ValidationHandler} carries
     * none (a {@code description:} alongside {@code handler: VALIDATION} is rejected at lift
     * time) and is skipped by every caller before this is reached.
     */
    private static ClientMessage clientMessageOf(Handler handler) {
        return switch (handler) {
            case ExceptionHandler eh -> eh.clientMessage();
            case DatabaseHandler dh -> dh.clientMessage();
            case ValidationHandler vh -> throw new IllegalStateException(
                "ValidationHandler carries no ClientMessage and must be skipped before this call;"
                    + " reached clientMessageOf with " + vh);
        };
    }
}
