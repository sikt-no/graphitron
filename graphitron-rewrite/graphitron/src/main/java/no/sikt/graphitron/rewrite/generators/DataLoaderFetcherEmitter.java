package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.ENV;

/**
 * Single entry point for emitting a DataFetcher's {@link MethodSpec} when the field is backed
 * by a DataLoader. R38 Phase 2 replaces the three handcrafted DataFetcher builders in
 * {@code TypeFetcherGenerator} ({@code buildServiceDataFetcher},
 * {@code buildSplitQueryDataFetcher}, {@code buildRecordBasedDataFetcher}) with one call to
 * {@link #build}. The five concrete fetcher shapes share the same outer dance:
 *
 * <ol>
 *   <li>resolve the path-scoped DataLoader name via {@code GraphitronContext.getTenantId} +
 *       {@code env.getExecutionStepInfo().getPath()}</li>
 *   <li>register / look up the typed {@code DataLoader<K, V>} on the registry, picking the
 *       factory ({@code newDataLoader} vs {@code newMappedDataLoader}) by
 *       {@link LoaderRegistration#container()}</li>
 *   <li>extract the per-fetch key (or keys list) — caller-supplied as {@code keyExtraction}
 *       because the extraction shape depends on the parent record carrier (jOOQ table-row
 *       accessor vs backing-class accessor vs lifter call) and on per-field null-handling
 *       (single-cardinality split fields short-circuit on null FK; list-cardinality and
 *       record-parent fields don't)</li>
 *   <li>dispatch onto the loader: {@link Invocation#LOAD_ONE} emits
 *       {@code return loader.load(key, env)} (the keyExtraction emitted a {@code key} local);
 *       {@link Invocation#LOAD_MANY} emits {@code return loader.loadMany(keys, ...)} (the
 *       keyExtraction emitted a {@code keys} local)</li>
 *   <li>chain the async tail — caller-supplied as {@code asyncWrapTail}, since the
 *       error-channel routing concerns vary by field shape</li>
 * </ol>
 *
 * <p>Three axes of variation collapse onto explicit parameters; the shared outer is emitted
 * once. {@link RowsMethodCall#batchLoaderLambda} is the matching factory for the BatchLoader
 * lambda the caller would otherwise inline at this site.
 */
public final class DataLoaderFetcherEmitter {

    private static final ClassName DATA_LOADER          = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName DATA_LOADER_FACTORY  = ClassName.get("org.dataloader", "DataLoaderFactory");

    private DataLoaderFetcherEmitter() {}

    /**
     * Per-call dispatch axis: {@link #LOAD_ONE} reads a single {@code key} local emitted by
     * {@code keyExtraction} and emits {@code loader.load(key, env)}; {@link #LOAD_MANY} reads
     * a {@code keys} list local and emits {@code loader.loadMany(keys, contexts)} with one
     * env per key (the BatchLoader only ever inspects {@code keyContexts.get(0)}, so duplicating
     * is the cheapest way to wire the env through).
     *
     * <p>Today only {@code BatchKey.AccessorKeyedMany}'s rows-method ({@code loadMany} contract:
     * one record per element-PK key) lands on {@link #LOAD_MANY}; every other classification
     * path takes {@link #LOAD_ONE}. Phase 3 re-grounds this projection against {@code SourceKey}
     * directly; for now Phase 1's emitter takes the choice as an explicit input so the
     * BatchKey-to-SourceKey projection question (which {@code Cardinality.MANY} cases
     * dispatch loadMany vs which dispatch load with a list-valued V) is decided by the caller.
     */
    public enum Invocation {
        /** {@code loader.load(key, env)}; keyExtraction must emit a {@code key} local. */
        LOAD_ONE,
        /** {@code loader.loadMany(keys, contexts)}; keyExtraction must emit a {@code keys} local. */
        LOAD_MANY
    }

    /**
     * Builds the DataFetcher MethodSpec. The fetcher's name is the GraphQL field name; its
     * declared return type is {@code CompletableFuture<DataFetcherResult<resultValueType>>}
     * (constructed by the caller via the same {@code asyncResultType} helper that wraps
     * sync fetcher returns).
     *
     * @param fieldName             the GraphQL field name; becomes the fetcher method's name.
     * @param keyType               the per-key element type (today
     *                              {@code BatchKey.keyElementType()}; post-Phase-3 derived
     *                              from {@code SourceKey}).
     * @param loaderValueType       the per-key value type {@code V} in
     *                              {@code DataLoader<K, V>}. For SQL-side rows methods this
     *                              is typically {@code Record} (single-cardinality / loadMany
     *                              contract) or {@code List<Record>} (list cardinality); for
     *                              service-side it follows the developer-declared return.
     * @param outerReturnType       the fetcher's declared return type — already wrapped as
     *                              {@code CompletableFuture<DataFetcherResult<P>>} by the
     *                              caller (the per-class async-result helper).
     * @param registration          the field's {@link LoaderRegistration}; chooses
     *                              {@code newDataLoader} vs {@code newMappedDataLoader}.
     * @param invocation            {@link Invocation#LOAD_ONE} or {@link Invocation#LOAD_MANY};
     *                              picks the loader-dispatch shape.
     * @param graphitronContextCall the call expression for the per-class
     *                              {@code graphitronContext(env)} helper. Used in the path-scoped
     *                              DataLoader name construction (tenantId prefix + path keys).
     * @param batchLoaderLambda     the BatchLoader lambda CodeBlock — caller builds via
     *                              {@link RowsMethodCall#batchLoaderLambda}.
     * @param keyExtraction         pre-built CodeBlock declaring the {@code key} or {@code keys}
     *                              local that the dispatch consumes. Per-field-shape variation
     *                              (jOOQ table-row vs backing-class accessor vs lifter call,
     *                              null-FK short-circuit) lives in the caller.
     * @param asyncWrapTail         pre-built CodeBlock chained after {@code loader.load(...)} /
     *                              {@code loader.loadMany(...)}; lifts the per-key value into a
     *                              {@code DataFetcherResult<P>} via {@code .thenApply(...)} and
     *                              routes any escaped throw via {@code .exceptionally(...)}.
     */
    public static MethodSpec build(
            String fieldName,
            TypeName keyType,
            TypeName loaderValueType,
            TypeName outerReturnType,
            LoaderRegistration registration,
            Invocation invocation,
            CodeBlock graphitronContextCall,
            CodeBlock batchLoaderLambda,
            CodeBlock keyExtraction,
            CodeBlock asyncWrapTail) {

        TypeName loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, loaderValueType);
        String factoryMethod = registration.container() == LoaderRegistration.Container.MAPPED_SET
            ? "newMappedDataLoader"
            : "newDataLoader";

        var b = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(outerReturnType)
            .addParameter(ENV, "env")
            .addCode(buildDataLoaderName(graphitronContextCall))
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.$L($L));\n",
                loaderType, DATA_LOADER_FACTORY, factoryMethod, batchLoaderLambda)
            .addCode(keyExtraction)
            .addCode(dispatchCall(invocation))
            .addCode(CodeBlock.builder().add("    ").add(asyncWrapTail).add(";\n").build());

        return b.build();
    }

    /**
     * Emits the path-scoped DataLoader name local: tenant prefix + slash + path keys joined by
     * slash. {@code ResultPath.getKeysOnly()} returns named segments only (list indices stripped),
     * so {@code /films/0/actors} and {@code /films/1/actors} share a key list and route to the
     * same DataLoader; aliased uses of the same field get distinct path segments and distinct
     * loaders.
     */
    private static CodeBlock buildDataLoaderName(CodeBlock graphitronContextCall) {
        return CodeBlock.builder()
            .addStatement("$T name = $L.getTenantId(env) + $S + $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
                String.class, graphitronContextCall, "/", String.class, "/")
            .build();
    }

    /**
     * Per-{@link Invocation} dispatch line.
     *
     * <p>{@link Invocation#LOAD_MANY} duplicates the {@code env} across {@code keys.size()} key
     * contexts because {@code DataLoader.loadMany}'s context-list overload requires the contexts
     * list to match the keys list arity. The BatchLoader only reads
     * {@code keyContextsList.get(0)} (see {@link RowsMethodCall#batchLoaderLambda}), so the
     * duplication is structural padding.
     */
    private static CodeBlock dispatchCall(Invocation invocation) {
        return switch (invocation) {
            case LOAD_ONE  -> CodeBlock.of("return loader.load(key, env)\n");
            case LOAD_MANY -> CodeBlock.of(
                "return loader.loadMany(keys, $T.nCopies(keys.size(), env))\n",
                ClassName.get("java.util", "Collections"));
        };
    }
}
