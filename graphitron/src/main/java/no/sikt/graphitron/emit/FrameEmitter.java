package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.Modifier;
import java.util.Objects;
import java.util.function.Function;

/**
 * Emits a producer's frame from one row of {@link FrameQuery}: the fetcher graphql-java calls,
 * doing what every producer does around its call. It reaches the jOOQ context, runs the producer's
 * body, hands the value back, and routes what the body throws: claimed into the producer's outcome
 * where it has one, surfaced or redacted otherwise. Under an object it batches: the parent's key is
 * read off the parent's row, and one mapped loader per path collects the keys and runs the body once
 * over all of them.
 *
 * <pre>{@code
 * public static CompletableFuture<DataFetcherResult<String>> titleUppercase(DataFetchingEnvironment env) {
 *     DataLoader<Record1<Integer>, String> loader = env.getDataLoaderRegistry().computeIfAbsent(
 *         String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly()) + "@Film",
 *         name -> DataLoaderFactory.newMappedDataLoader((Set<Record1<Integer>> keys, BatchLoaderEnvironment batch) -> {
 *             DataFetchingEnvironment first = (DataFetchingEnvironment) batch.getKeyContextsList().get(0);
 *             DSLContext dsl = graphitronContext(first).getDslContext(first);
 *             return CompletableFuture.completedFuture(FilmTitleUppercase.call(keys, first, dsl));
 *         }));
 *     CompletableFuture<String> loaded;
 *     try {
 *         loaded = loader.load(((Record) env.getSource()).into(Tables.FILM.FILM_ID), env);
 *     } catch (Exception thrown) {
 *         loaded = CompletableFuture.failedFuture(thrown);
 *     }
 *     return loaded
 *         .thenApply(value -> DataFetcherResult.<String>newResult().data(value).build())
 *         .exceptionally(thrown -> {
 *             return ErrorRouter.surfaceClientErrorOrRedact(thrown, env);
 *         });
 * }
 * }</pre>
 *
 * <p>The frame knows nothing of what the body does: the body is a method of the caller's, taking
 * {@code (env, dsl)} at a root and {@code (keys, env, dsl)} under an object, its keys the parent's
 * key as one typed record of the key's columns and its answer a map from each key to its value.
 */
public final class FrameEmitter {

    private FrameEmitter() {}

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RESULT = ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName LOADER = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName LOADER_FACTORY = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName BATCH_ENV = ClassName.get("org.dataloader", "BatchLoaderEnvironment");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName LIST = ClassName.get("java.util", "List");

    /**
     * The generated runtime the frame calls into: the error router, the outcome and its error
     * mappings, the constant naming an outcome type's mappings, and how a fetcher reaches its jOOQ
     * context from an environment.
     */
    public record Runtime(ClassName errorRouter, ClassName outcome, ClassName errorMappings,
                          Function<String, String> mappingsConstant,
                          Function<String, CodeBlock> dslContext) {
        public Runtime {
            Objects.requireNonNull(errorRouter, "errorRouter");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(errorMappings, "errorMappings");
            Objects.requireNonNull(mappingsConstant, "mappingsConstant");
            Objects.requireNonNull(dslContext, "dslContext");
        }
    }

    /**
     * Renders {@code row}'s frame.
     *
     * @param methodName the fetcher's generated name
     * @param valueType  what the body answers for one occurrence: its value at a root, the value per
     *                   key under an object
     * @param body       the method running the producer: {@code (env, dsl)} at a root,
     *                   {@code (keys, env, dsl)} under an object
     */
    public static MethodSpec render(FrameQuery.Row row, String methodName, TypeName valueType,
                                    ClassName bodyClass, String body, Runtime runtime) {
        return render(row, methodName, valueType, bodyClass, body, runtime, true);
    }

    /**
     * {@link #render}, where at a root {@code needsContext} says whether the body uses the jOOQ
     * context: a body that does not is handed none, so a field that touches no database takes no
     * connection.
     */
    public static MethodSpec render(FrameQuery.Row row, String methodName, TypeName valueType,
                                    ClassName bodyClass, String body, Runtime runtime, boolean needsContext) {
        return render(row, methodName, valueType, bodyClass, body, runtime, needsContext, false);
    }

    /**
     * {@link #render}, where at a root {@code bodyAnswersOutcome} says the body hands back the
     * outcome itself, its errors or its success, which the frame passes on as it is rather than
     * wrapping the value as the success.
     */
    public static MethodSpec render(FrameQuery.Row row, String methodName, TypeName valueType,
                                    ClassName bodyClass, String body, Runtime runtime, boolean needsContext,
                                    boolean bodyAnswersOutcome) {
        if (bodyAnswersOutcome && (!row.root() || row.outcome().isEmpty())) {
            throw new IllegalArgumentException(row.coordinate() + ": only a root frame with an outcome takes it"
                + " from its body");
        }
        return row.root()
            ? root(row, methodName, valueType, bodyClass, body, runtime, needsContext, bodyAnswersOutcome)
            : child(row, methodName, valueType, bodyClass, body, runtime);
    }

    private static MethodSpec root(FrameQuery.Row row, String methodName, TypeName valueType,
                                   ClassName bodyClass, String body, Runtime runtime, boolean needsContext,
                                   boolean bodyAnswersOutcome) {
        var declared = declared(row, valueType, runtime);
        var held = bodyAnswersOutcome ? declared : valueType;
        var method = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, declared))
            .addParameter(ENV, "env")
            .beginControlFlow("try");
        if (needsContext) {
            method.addStatement("$T dsl = $L", DSL_CONTEXT, runtime.dslContext().apply("env"))
                .addStatement("$T value = $T.$L(env, dsl)", held, bodyClass, body);
        } else {
            method.addStatement("$T value = $T.$L(env, null)", held, bodyClass, body);
        }
        var answer = bodyAnswersOutcome
            ? CodeBlock.of("$T.<$T>newResult().data(value).build()", RESULT, declared)
            : success(row, declared, CodeBlock.of("value"), runtime);
        return method
            .addStatement("return $L", answer)
            .nextControlFlow("catch ($T thrown)", Exception.class)
            .addCode(failure(row, declared, runtime))
            .endControlFlow()
            .build();
    }

    private static MethodSpec child(FrameQuery.Row row, String methodName, TypeName valueType,
                                    ClassName bodyClass, String body, Runtime runtime) {
        var parent = row.parent().orElseThrow();
        if (parent.key().isEmpty()) {
            throw new IllegalArgumentException(row.coordinate() + "'s parent, a row of "
                + parent.sqlName() + ", has no primary key to batch by");
        }
        var constants = CodeBlock.of("$T.$L", ClassName.bestGuess(parent.constantsClassName()),
            parent.javaFieldName());
        TypeName[] keyTypes = parent.key().stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.bindingType())).toArray(TypeName[]::new);
        var keyType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Record" + keyTypes.length), keyTypes);
        var keyColumns = CodeBlock.join(parent.key().stream()
            .map(c -> CodeBlock.of("$L.$L", constants, c.javaName())).toList(), ", ");
        var declared = declared(row, valueType, runtime);
        var resultType = ParameterizedTypeName.get(RESULT, declared);
        var loader = CodeBlock.builder()
            .add("$T<$T, $T> loader = env.getDataLoaderRegistry().computeIfAbsent(\n",
                LOADER, keyType, valueType)
            .indent()
            .add("$T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly()) + $S,\n",
                String.class, "/", "@" + row.declaringType())
            .add("name -> $T.newMappedDataLoader(($T<$T> keys, $T batch) -> {\n",
                LOADER_FACTORY, SET, keyType, BATCH_ENV)
            .indent()
            .addStatement("$T first = ($T) batch.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("$T dsl = $L", DSL_CONTEXT, runtime.dslContext().apply("first"))
            // The body may throw what the call throws, checked or not; the loader's function may
            // not, so a throw becomes the batch's failed future, which the error routing below reads.
            .beginControlFlow("try")
            .addStatement("return $T.completedFuture($T.$L(keys, first, dsl))", FUTURE, bodyClass, body)
            .nextControlFlow("catch ($T thrown)", Exception.class)
            .addStatement("return $T.failedFuture(thrown)", FUTURE)
            .endControlFlow()
            .unindent()
            .add("}));\n")
            .unindent()
            .build();
        var method = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(FUTURE, resultType))
            .addParameter(ENV, "env")
            .addCode(loader)
            // A throw reading the key fails the same future a throw in the body does, so both are
            // routed by the one handler below.
            .addStatement("$T<$T> loaded", FUTURE, valueType)
            .beginControlFlow("try");
        if (row.accessor().isPresent()) {
            // The key is the record the parent's member hands back; a parent handing back none has
            // no key, and its field is null.
            var accessor = row.accessor().get();
            var recordClass = ClassName.bestGuess(accessor.recordClassName());
            method.addStatement("$T keyRecord = (($T) env.getSource()).$L()", recordClass,
                    ClassName.bestGuess(accessor.parentClassName()), accessor.methodName())
                .beginControlFlow("if (keyRecord == null)")
                .addStatement("return $T.completedFuture(null)", FUTURE)
                .endControlFlow()
                .addStatement("loaded = loader.load(keyRecord.into($L), env)", keyColumns);
        } else {
            method.addStatement("loaded = loader.load((($T) env.getSource()).into($L), env)", RECORD, keyColumns);
        }
        return method
            .nextControlFlow("catch ($T thrown)", Exception.class)
            .addStatement("loaded = $T.failedFuture(thrown)", FUTURE)
            .endControlFlow()
            .addCode(CodeBlock.builder()
                .add("return loaded\n")
                .indent()
                .add(".thenApply(value -> $L)\n", success(row, declared, CodeBlock.of("value"), runtime))
                .add(".exceptionally(thrown -> {\n")
                .indent()
                .add(failure(row, declared, runtime))
                .unindent()
                .add("});\n")
                .unindent()
                .build())
            .build();
    }

    /** What the fetcher hands back: the value, or the value as its outcome's success. */
    private static TypeName declared(FrameQuery.Row row, TypeName valueType, Runtime runtime) {
        return row.outcome().isPresent()
            ? ParameterizedTypeName.get(runtime.outcome(), valueType)
            : valueType;
    }

    /** {@code DataFetcherResult.<T>newResult().data(value).build()}, wrapped in its outcome if any. */
    private static CodeBlock success(FrameQuery.Row row, TypeName declared, CodeBlock value,
                                     Runtime runtime) {
        var data = row.outcome().isPresent()
            ? CodeBlock.of("new $T.Success<>($L)", runtime.outcome(), value)
            : value;
        return CodeBlock.of("$T.<$T>newResult().data($L).build()", RESULT, declared, data);
    }

    /**
     * What a throw becomes, as the statements returning it: claimed into the outcome's error list
     * where its mappings claim it, otherwise surfaced to the client or redacted.
     */
    private static CodeBlock failure(FrameQuery.Row row, TypeName declared, Runtime runtime) {
        var b = CodeBlock.builder();
        if (row.outcome().isPresent()) {
            var constant = runtime.mappingsConstant().apply(row.outcome().get().typeName());
            b.addStatement("$T claimed = $T.claim(thrown, $T.$L, env)", Throwable.class,
                runtime.errorRouter(), runtime.errorMappings(), constant);
            b.beginControlFlow("if (claimed != null)");
            b.addStatement("return $T.<$T>newResult().data(new $T.ErrorList<>($T.of(claimed))).build()",
                RESULT, declared, runtime.outcome(), LIST);
            b.endControlFlow();
        }
        return b.addStatement("return $T.surfaceClientErrorOrRedact(thrown, env)", runtime.errorRouter())
            .build();
    }
}
