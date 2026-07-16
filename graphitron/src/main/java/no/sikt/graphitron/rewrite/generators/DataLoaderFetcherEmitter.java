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
 * by a DataLoader. Replaces the three handcrafted DataFetcher builders in
 * {@code TypeFetcherGenerator} ({@code buildServiceDataFetcher},
 * {@code buildSplitQueryDataFetcher}, {@code buildRecordBasedDataFetcher}) with one call to
 * {@link #build}. The five concrete fetcher shapes share the same outer dance:
 *
 * <ol>
 *   <li>resolve the path-scoped DataLoader name via
 *       {@code env.getExecutionStepInfo().getPath()}</li>
 *   <li>register / look up the typed {@code DataLoader<K, V>} on the registry, picking the
 *       factory ({@code newDataLoader} vs {@code newMappedDataLoader}) by
 *       {@link LoaderRegistration#container()}</li>
 *   <li>extract the per-fetch key (or keys list) — caller-supplied as {@code keyExtraction}
 *       because the extraction shape depends on the parent record carrier (jOOQ table-row
 *       accessor vs backing-class accessor vs lifter call) and on per-field null-handling
 *       (single-cardinality split fields short-circuit on null FK; list-cardinality and
 *       record-parent fields don't)</li>
 *   <li>dispatch onto the loader per {@link LoaderRegistration#dispatch()}:
 *       {@link LoaderRegistration.Dispatch#LOAD_ONE} emits {@code return loader.load(key, env)}
 *       (the keyExtraction emitted a {@code key} local);
 *       {@link LoaderRegistration.Dispatch#LOAD_MANY} emits
 *       {@code return loader.loadMany(keys, ...)} (the keyExtraction emitted a {@code keys}
 *       local)</li>
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
     * Builds the DataFetcher MethodSpec. The fetcher's name is the GraphQL field name; its
     * declared return type is {@code CompletableFuture<DataFetcherResult<resultValueType>>}
     * (constructed by the caller via the same {@code asyncResultType} helper that wraps
     * sync fetcher returns).
     *
     * @param fieldName             the GraphQL field name; becomes the fetcher method's name.
     * @param keyType               the per-key element type, derived from
     *                              {@code SourceKey}.
     * @param loaderValueType       the per-key value type {@code V} in
     *                              {@code DataLoader<K, V>}. For SQL-side rows methods this
     *                              is typically {@code Record} (single-cardinality / loadMany
     *                              contract) or {@code List<Record>} (list cardinality); for
     *                              service-side it follows the developer-declared return.
     * @param outerReturnType       the fetcher's declared return type — already wrapped as
     *                              {@code CompletableFuture<DataFetcherResult<P>>} by the
     *                              caller (the per-class async-result helper).
     * @param registration          the field's {@link LoaderRegistration}; chooses
     *                              {@code newDataLoader} vs {@code newMappedDataLoader} via
     *                              {@link LoaderRegistration#container()} and
     *                              {@code load} vs {@code loadMany} via
     *                              {@link LoaderRegistration#dispatch()}.
     * @param batchLoaderLambda     the BatchLoader lambda CodeBlock, caller builds via
     *                              {@link RowsMethodCall#batchLoaderLambda}.
     * @param keyExtraction         pre-built CodeBlock declaring the {@code key} or {@code keys}
     *                              local that the dispatch consumes. Per-field-shape variation
     *                              (jOOQ table-row vs backing-class accessor vs lifter call)
     *                              lives in the caller. The block may also short-circuit before
     *                              reaching the dispatch line — single-cardinality split fields
     *                              with a nullable FK emit
     *                              {@code if (key_value == null) return ...; key = ...;} so the
     *                              outer fetcher returns a completed null without touching the
     *                              loader registry.
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
            CodeBlock batchLoaderLambda,
            CodeBlock keyExtraction,
            CodeBlock asyncWrapTail,
            CodeBlock syncCatchBody) {
        return build(fieldName, keyType, loaderValueType, outerReturnType, registration,
            batchLoaderLambda, CodeBlock.of(""), keyExtraction, asyncWrapTail, syncCatchBody);
    }

    /**
 * Pre-registration-prelude overload. {@code preRegistrationPrelude} is emitted before
     * the path-name and {@code computeIfAbsent} loader registration, so a fetcher that is an
     * immediate child of a flipped {@code Outcome} payload can narrow {@code env.getSource()} to
     * {@code Outcome.Success} and {@code return CompletableFuture.completedFuture(null)} on the
     * {@code ErrorList} arm <em>before</em> touching the loader registry. Placing the early return
     * ahead of registration (rather than after, relying on idempotent {@code computeIfAbsent}) keeps
     * the generated error-arm code honest: it neither registers nor dispatches the loader. The
     * caller pairs this with a {@code success.value()} source binding on {@code keyExtraction} so
     * the key reads come off the unwrapped backing object. An empty prelude is the non-outcome path.
     */
    public static MethodSpec build(
            String fieldName,
            TypeName keyType,
            TypeName loaderValueType,
            TypeName outerReturnType,
            LoaderRegistration registration,
            CodeBlock batchLoaderLambda,
            CodeBlock preRegistrationPrelude,
            CodeBlock keyExtraction,
            CodeBlock asyncWrapTail,
            CodeBlock syncCatchBody) {

        TypeName loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, loaderValueType);
        String factoryMethod = registration.container() == LoaderRegistration.Container.MAPPED_SET
            ? "newMappedDataLoader"
            : "newDataLoader";

        // The key extraction runs synchronously, before dispatch and before the async
        // .exceptionally tail exists, so a throw out of it (e.g. a jOOQ into(...)/accessor failure)
        // used to escape DataFetcher.get() unrouted — leaking a raw, record-dumping message past
        // ErrorRouter's redaction. Wrap the extraction + dispatch + async tail in a
        // try/catch(Throwable) whose arm routes through the SAME disposition the .exceptionally tail
        // uses (threaded as syncCatchBody), lifted into a completed future. The
        // preRegistrationPrelude (Outcome narrowing) stays outside the guard: its early return
        // is deliberate control flow, not a failure path, and precedes loader registration by
        // design. keyExtraction's own early returns (single-cardinality null-FK short-circuit) stay
        // legal inside the try.
        var b = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(outerReturnType)
            .addParameter(ENV, "env")
            .addCode(preRegistrationPrelude)
            .addCode(buildDataLoaderName())
            .addCode(
                "$T loader = env.getDataLoaderRegistry()\n" +
                "    .computeIfAbsent(name, k -> $T.$L($L));\n",
                loaderType, DATA_LOADER_FACTORY, factoryMethod, batchLoaderLambda)
            .beginControlFlow("try")
            .addCode(keyExtraction)
            .addCode(dispatchCall(registration.dispatch()))
            .addCode(CodeBlock.builder().add("    ").add(asyncWrapTail).add(";\n").build())
            .nextControlFlow("catch ($T e)", ClassName.get(Throwable.class))
            .addCode(syncCatchBody)
            .endControlFlow();

        return b.build();
    }

    /**
     * Emits the path-scoped DataLoader name local: path keys joined by slash.
     * {@code ResultPath.getKeysOnly()} returns named segments only (list indices stripped),
     * so {@code /films/0/actors} and {@code /films/1/actors} share a key list and route to the
     * same DataLoader; aliased uses of the same field get distinct path segments and distinct
     * loaders.
     */
    private static CodeBlock buildDataLoaderName() {
        return CodeBlock.builder()
            .addStatement("$T name = $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
                String.class, String.class, "/")
            .build();
    }

    /**
     * Per-{@link LoaderRegistration.Dispatch} dispatch line.
     *
     * <p>{@link LoaderRegistration.Dispatch#LOAD_MANY} duplicates the {@code env} across
     * {@code keys.size()} key contexts because {@code DataLoader.loadMany}'s context-list
     * overload requires the contexts list to match the keys list arity. The BatchLoader only
     * reads {@code keyContextsList.get(0)} (see {@link RowsMethodCall#batchLoaderLambda}), so
     * the duplication is structural padding.
     */
    private static CodeBlock dispatchCall(LoaderRegistration.Dispatch dispatch) {
        return switch (dispatch) {
            case LOAD_ONE  -> CodeBlock.of("return loader.load(key, env)\n");
            case LOAD_MANY -> CodeBlock.of(
                "return loader.loadMany(keys, $T.nCopies(keys.size(), env))\n",
                ClassName.get("java.util", "Collections"));
        };
    }
}
