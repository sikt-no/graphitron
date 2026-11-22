package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.render.LoaderConstruction;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Emits a producer's frame from one row of {@link FrameQuery}: the fetcher graphql-java calls,
 * doing what every producer does around its call. It reaches the jOOQ context, runs the producer's
 * body, hands the value back, and routes what the body throws: claimed into the producer's outcome
 * where it has one, surfaced or redacted otherwise. Under an object it batches: the parent's key is
 * read off the parent's row, and one mapped loader per path collects the keys and runs the body once
 * over all of them. A parent that is a producer's outcome is read through its value, its error arm
 * answering what nothing produced answers without loading.
 *
 * <pre>{@code
 * public static CompletableFuture<DataFetcherResult<String>> titleUppercase(DataFetchingEnvironment env) {
 *     DataLoader<Record1<Integer>, String> loader = env.getDataLoaderRegistry().computeIfAbsent(
 *         String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly()) + "@Film",
 *         name -> DataLoaderFactory.newMappedDataLoader((Set<Record1<Integer>> keys, BatchLoaderEnvironment batch) -> {
 *             DataFetchingEnvironment first = (DataFetchingEnvironment) batch.getKeyContextsList().get(0);
 *             DSLContext dsl = graphitronContext(first).getDslContext(first);
 *             return CompletableFuture.completedFuture(FilmTitleUppercase.call(keys, first, dsl));
 *         }, ErrorRouter.loaderOptions(env)));
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
    private static final ClassName BATCH_ENV = ClassName.get("org.dataloader", "BatchLoaderEnvironment");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");

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
     * The connection a frame runs its body on, as the store states it for the producer: the request's
     * own in a graph with no tenant column; else at a root one keyed by the tenant its slots name,
     * under an object the key its parent handed down, or at either the key with an empty tenant part.
     */
    public sealed interface Connection {

        /** The request's own connection, the one {@link Runtime#dslContext} reaches, in an untenanted graph. */
        record Request() implements Connection {}

        /**
         * A child's run on the whole key its producer chain handed down, read and acquired by the
         * generated {@code dslHandedDown}.
         */
        record Inherited(ClassName ownedConnections) implements Connection {
            public Inherited {
                Objects.requireNonNull(ownedConnections, "ownedConnections");
            }
        }

        /** A child's run on the key with an empty tenant part, its rows lying in no tenant scope. */
        record Unscoped(ClassName ownedConnections, ClassName connectionKey) implements Connection {
            public Unscoped {
                Objects.requireNonNull(ownedConnections, "ownedConnections");
                Objects.requireNonNull(connectionKey, "connectionKey");
            }
        }

        /**
         * A connection keyed by the tenant {@code reads} name, folded by the carrier into one tenant,
         * keyed, acquired by that key and handed down to the subtree.
         *
         * @param tenantType        the tenant column's Java type, boxed
         * @param ownedConnections the generated connection carrier
         * @param connectionKey     the generated key it is acquired and handed down by
         * @param reads             each value the tenant is read from, as an expression over {@code env}
         * @param noTenant          what a call whose values name no tenant gets
         */
        record Routed(TypeName tenantType, ClassName ownedConnections, ClassName connectionKey,
                      List<CodeBlock> reads, NoTenant noTenant) implements Connection {
            public Routed {
                Objects.requireNonNull(tenantType, "tenantType");
                Objects.requireNonNull(ownedConnections, "ownedConnections");
                Objects.requireNonNull(connectionKey, "connectionKey");
                reads = List.copyOf(reads);
                Objects.requireNonNull(noTenant, "noTenant");
            }
        }
    }

    /** What a routed call whose values name no tenant gets. */
    public sealed interface NoTenant {

        /** Never reached: a value is always there; the carrier's fold throws if one is not. */
        record Unreachable() implements NoTenant {}

        /** The producer's empty answer, {@code value}, returned without a connection. */
        record Empty(CodeBlock value) implements NoTenant {}

        /** A client error, {@code exception} with {@code message}, before the service runs. */
        record Refuse(ClassName exception, String message) implements NoTenant {}
    }

    /** The local the routed tenant is folded into, and the one its key is built under. */
    private static final String TENANT_LOCAL = "_tenant";
    private static final String KEY_LOCAL = "_key";

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
        return render(row, methodName, valueType, bodyClass, body, runtime, needsContext, bodyAnswersOutcome,
            new Connection.Request());
    }

    /**
     * {@link #render}, where the frame runs its body on {@code connection}. Only a root frame is routed,
     * handing its key down to the subtree, and only a child frame inherits that key.
     */
    public static MethodSpec render(FrameQuery.Row row, String methodName, TypeName valueType,
                                    ClassName bodyClass, String body, Runtime runtime, boolean needsContext,
                                    boolean bodyAnswersOutcome, Connection connection) {
        return render(row, methodName, valueType, bodyClass, body, runtime, needsContext, bodyAnswersOutcome,
            connection, CodeBlock.of(""));
    }

    /**
     * {@link #render}, where a child frame runs {@code beforeLoad} in its fetcher ahead of handing its
     * key to the loader: statements reading the fetch as {@code env}, such as the decode of the
     * arguments its call takes, so what they throw fails this field alone, routed as a throw in the
     * body is. A root frame's body runs in its fetcher already and takes none.
     */
    public static MethodSpec render(FrameQuery.Row row, String methodName, TypeName valueType,
                                    ClassName bodyClass, String body, Runtime runtime, boolean needsContext,
                                    boolean bodyAnswersOutcome, Connection connection, CodeBlock beforeLoad) {
        if (row.root() && !beforeLoad.isEmpty()) {
            throw new IllegalArgumentException(row.coordinate() + ": only a child frame runs statements before it loads");
        }
        if (row.root() && connection instanceof Connection.Inherited
                || !row.root() && connection instanceof Connection.Routed) {
            throw new IllegalArgumentException(row.coordinate() + ": a root frame is routed and a child's"
                + " inherits; " + connection + " does not fit");
        }
        if (bodyAnswersOutcome && (!row.root() || row.outcome().isEmpty())) {
            throw new IllegalArgumentException(row.coordinate() + ": only a root frame with an outcome takes it"
                + " from its body");
        }
        return row.root()
            ? root(row, methodName, valueType, bodyClass, body, runtime, needsContext, bodyAnswersOutcome, connection)
            : child(row, methodName, valueType, bodyClass, body, runtime, connection, beforeLoad);
    }

    private static MethodSpec root(FrameQuery.Row row, String methodName, TypeName valueType,
                                   ClassName bodyClass, String body, Runtime runtime, boolean needsContext,
                                   boolean bodyAnswersOutcome, Connection connection) {
        var declared = declared(row, valueType, runtime);
        var held = bodyAnswersOutcome ? declared : valueType;
        var method = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, declared))
            .addParameter(ENV, "env")
            .beginControlFlow("try");
        CodeBlock handDown = switch (connection) {
            case Connection.Request ignored -> {
                if (needsContext) {
                    method.addStatement("$T dsl = $L", DSL_CONTEXT, runtime.dslContext().apply("env"));
                }
                yield CodeBlock.of("");
            }
            case Connection.Inherited inherited -> throw new IllegalArgumentException(row.coordinate()
                + ": a root frame has no parent to inherit a key from");
            case Connection.Unscoped unscoped -> {
                if (needsContext) {
                    method.addStatement("$T dsl = $T.dslFor(env, $T.defaultSource())", DSL_CONTEXT,
                        unscoped.ownedConnections(), unscoped.connectionKey());
                }
                yield CodeBlock.of("");
            }
            case Connection.Routed routed -> {
                routedKey(method, row, declared, routed, runtime);
                if (needsContext) {
                    method.addStatement("$T dsl = $T.dslFor(env, $L)", DSL_CONTEXT, routed.ownedConnections(),
                        KEY_LOCAL);
                }
                yield no.sikt.graphitron.render.ConnectionKeyHandDown.ofKey(KEY_LOCAL).builderTail();
            }
        };
        method.addStatement("$T value = $T.$L(env, $L)", held, bodyClass, body, needsContext ? "dsl" : "null");
        var answer = bodyAnswersOutcome
            ? CodeBlock.of("$T.<$T>newResult().data(value)$L.build()", RESULT, declared, handDown)
            : success(row, declared, CodeBlock.of("value"), runtime, handDown);
        return method
            .addStatement("return $L", answer)
            .nextControlFlow("catch ($T thrown)", Exception.class)
            .addCode(failure(row, declared, runtime))
            .endControlFlow()
            .build();
    }

    private static MethodSpec child(FrameQuery.Row row, String methodName, TypeName valueType,
                                    ClassName bodyClass, String body, Runtime runtime, Connection connection,
                                    CodeBlock beforeLoad) {
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
        var batchLambda = CodeBlock.builder()
            .add("($T<$T> keys, $T batch) -> {\n", SET, keyType, BATCH_ENV)
            .indent()
            .addStatement("$T first = ($T) batch.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("$T dsl = $L", DSL_CONTEXT, childDsl(connection, runtime))
            // The body may throw what the call throws, checked or not; the loader's function may
            // not, so a throw becomes the batch's failed future, which the error routing below reads.
            .beginControlFlow("try")
            .addStatement("return $T.completedFuture($T.$L(keys, first, dsl))", FUTURE, bodyClass, body)
            .nextControlFlow("catch ($T thrown)", Exception.class)
            .addStatement("return $T.failedFuture(thrown)", FUTURE)
            .endControlFlow()
            .unindent()
            .add("}")
            .build();
        var loader = CodeBlock.builder()
            .add("$T<$T, $T> loader = env.getDataLoaderRegistry().computeIfAbsent(\n",
                LOADER, keyType, valueType)
            .indent()
            .add("$L,\n", loaderName(row, connection))
            // Built with the request's options, where a consumer's batch size limit takes effect.
            .add("name -> $L);\n", LoaderConstruction.mapped(
                batchLambda, "env", runtime.errorRouter()))
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
        var source = CodeBlock.of("env.getSource()");
        if (row.parentOutcome()) {
            // The parent is the outcome its producer handed back: the key is read off its value, and
            // its error arm, or a value of nothing, answers what nothing produced answers.
            source = CodeBlock.of("parent");
            method.addStatement("$T parent = env.getSource()", Object.class)
                .beginControlFlow("if (parent instanceof $T<?> outcome)", runtime.outcome())
                .beginControlFlow("if (!(outcome instanceof $T.Success<?> success) || success.value() == null)",
                    runtime.outcome())
                .addStatement("return $T.completedFuture($T.<$T>newResult().data($L).build())", FUTURE, RESULT,
                    declared, errorArmAnswer(row))
                .endControlFlow()
                .addStatement("parent = success.value()")
                .endControlFlow();
        }
        // Inside the try, so what it throws is routed by the handler below with a throw reading the key.
        method.addCode(beforeLoad);
        if (row.accessor().isPresent()) {
            // The key is the record the parent's member hands back; a parent handing back none has
            // no key, and its field is null.
            var accessor = row.accessor().get();
            var recordClass = ClassName.bestGuess(accessor.recordClassName());
            method.addStatement("$T keyRecord = (($T) $L).$L()", recordClass,
                    ClassName.bestGuess(accessor.parentClassName()), source, accessor.methodName())
                .beginControlFlow("if (keyRecord == null)")
                .addStatement("return $T.completedFuture(null)", FUTURE)
                .endControlFlow()
                .addStatement("loaded = loader.load(keyRecord.into($L), env)", keyColumns);
        } else {
            method.addStatement("loaded = loader.load((($T) $L).into($L), env)", RECORD, source, keyColumns);
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

    /**
     * What a child under an outcome answers where the outcome holds no value: nothing was produced,
     * so null, or the empty list for a non-null list, which null would bubble past to the outcome's
     * errors. A field catching into its own outcome answers null there, its outcome being no list.
     */
    private static CodeBlock errorArmAnswer(FrameQuery.Row row) {
        return row.nonNullList() && row.outcome().isEmpty()
            ? CodeBlock.of("$T.of()", LIST)
            : CodeBlock.of("null");
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
        return success(row, declared, value, runtime, CodeBlock.of(""));
    }

    /** {@link #success}, the result builder carrying {@code handDown} before it is built. */
    private static CodeBlock success(FrameQuery.Row row, TypeName declared, CodeBlock value,
                                     Runtime runtime, CodeBlock handDown) {
        var data = row.outcome().isPresent()
            ? CodeBlock.of("new $T.Success<>($L)", runtime.outcome(), value)
            : value;
        return CodeBlock.of("$T.<$T>newResult().data($L)$L.build()", RESULT, declared, data, handDown);
    }

    /**
     * The name a child frame's loader is registered under: the field's path, and for a child
     * inheriting its key the whole key handed down with it, so every batch runs on one key.
     */
    private static CodeBlock loaderName(FrameQuery.Row row, Connection connection) {
        return connection instanceof Connection.Inherited inherited
            ? CodeBlock.of("$T.handedDownLoaderName(env, $S)", inherited.ownedConnections(), row.declaringType())
            : CodeBlock.of("$T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly()) + $S",
                String.class, "/", "@" + row.declaringType());
    }

    /**
     * The jOOQ context a child frame's batch runs on, off its first environment: the request's own, the
     * key its parent handed down, or the key with an empty tenant part.
     */
    private static CodeBlock childDsl(Connection connection, Runtime runtime) {
        return switch (connection) {
            case Connection.Request ignored -> runtime.dslContext().apply("first");
            case Connection.Inherited inherited -> CodeBlock.of("$T.dslHandedDown(first)",
                inherited.ownedConnections());
            case Connection.Unscoped unscoped -> CodeBlock.of("$T.dslFor(first, $T.defaultSource())",
                unscoped.ownedConnections(), unscoped.connectionKey());
            case Connection.Routed routed -> throw new IllegalArgumentException(
                "a child frame is not routed by its own slots");
        };
    }

    /**
     * The routed tenant and its key: the reads folded into one tenant, what a call naming none gets,
     * and the key built over it.
     */
    private static void routedKey(MethodSpec.Builder method, FrameQuery.Row row, TypeName declared,
                                  Connection.Routed routed, Runtime runtime) {
        var reads = CodeBlock.join(routed.reads(), ", ");
        switch (routed.noTenant()) {
            case NoTenant.Unreachable ignored -> method.addStatement("$T $L = $T.divinedTenant($L)",
                routed.tenantType(), TENANT_LOCAL, routed.ownedConnections(), reads);
            case NoTenant.Empty empty -> {
                method.addStatement("$T $L = $T.namedTenant($L)", routed.tenantType(), TENANT_LOCAL,
                    routed.ownedConnections(), reads);
                method.beginControlFlow("if ($L == null)", TENANT_LOCAL)
                    .addStatement("return $L", success(row, declared, empty.value(), runtime))
                    .endControlFlow();
            }
            case NoTenant.Refuse refuse -> {
                method.addStatement("$T $L = $T.namedTenant($L)", routed.tenantType(), TENANT_LOCAL,
                    routed.ownedConnections(), reads);
                method.beginControlFlow("if ($L == null)", TENANT_LOCAL)
                    .addStatement("throw new $T($S)", refuse.exception(), refuse.message())
                    .endControlFlow();
            }
        }
        method.addStatement("$T $L = $T.forTenant($L)", routed.connectionKey(), KEY_LOCAL, routed.connectionKey(),
            TENANT_LOCAL);
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
