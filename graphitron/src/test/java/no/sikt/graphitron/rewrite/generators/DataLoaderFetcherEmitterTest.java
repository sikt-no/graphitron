package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of {@link DataLoaderFetcherEmitter#build} — the unified DataFetcher
 * outer dance. Pins the path-scoped name construction, the loader-factory choice (positional
 * vs mapped), the key-extraction paste-through, the load-vs-loadMany dispatch (read off
 * {@link LoaderRegistration#dispatch()}), and the async-wrap tail paste-through.
 */
@UnitTier
class DataLoaderFetcherEmitterTest {

    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName LIST   = ClassName.get("java.util", "List");
    private static final ClassName CF     = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName DFR    = ClassName.get("graphql.execution", "DataFetcherResult");

    private static final TypeName KEY              = ClassName.bestGuess("java.lang.Integer");
    private static final TypeName LIST_OF_RECORD   = ParameterizedTypeName.get(LIST, RECORD);
    private static final TypeName ASYNC_RESULT     = ParameterizedTypeName.get(
        CF, ParameterizedTypeName.get(DFR, LIST_OF_RECORD));

    private static final CodeBlock LAMBDA   = CodeBlock.of("(keys, batchEnv) -> { /* lambda */ }");
    private static final CodeBlock KEY_EXTRACTION = CodeBlock.builder()
        .addStatement("$T key = ((org.jooq.Record) env.getSource()).get($S)", Integer.class, "language_id")
        .build();
    private static final CodeBlock KEYS_EXTRACTION = CodeBlock.builder()
        .addStatement("$T<Integer> keys = java.util.List.of()", LIST)
        .build();
    private static final CodeBlock TRIVIAL_TAIL = CodeBlock.of(".thenApply(java.util.function.Function.identity())");
    private static final CodeBlock CATCH_BODY = CodeBlock.of("return $T.completedFuture(null);\n", CF);

    @Test
    void positionalList_loadOne_emitsNewDataLoaderAndLoadKeyEnv() {
        var reg = new LoaderRegistration(
            true, LoaderRegistration.Container.POSITIONAL_LIST, LoaderRegistration.Dispatch.LOAD_ONE);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg,
            LAMBDA, KEY_EXTRACTION, TRIVIAL_TAIL, CATCH_BODY);

        String src = spec.toString();
        assertThat(src).contains("public static java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>> films(");
        assertThat(src).contains("graphql.schema.DataFetchingEnvironment env");
        assertThat(src).contains("name = java.lang.String.join(\"/\", env.getExecutionStepInfo().getPath().getKeysOnly())");
        assertThat(src).contains("org.dataloader.DataLoaderFactory.newDataLoader(");
        assertThat(src).contains("return loader.load(key, env)");
    }

    @Test
    void mappedSet_loadOne_emitsNewMappedDataLoader() {
        var reg = new LoaderRegistration(
            true, LoaderRegistration.Container.MAPPED_SET, LoaderRegistration.Dispatch.LOAD_ONE);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "filmsByActor",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg,
            LAMBDA, KEY_EXTRACTION, TRIVIAL_TAIL, CATCH_BODY);

        String src = spec.toString();
        assertThat(src).contains("org.dataloader.DataLoaderFactory.newMappedDataLoader(");
        assertThat(src).contains("return loader.load(key, env)");
    }

    @Test
    void loadMany_emitsLoaderLoadManyWithDuplicatedContexts() {
        var reg = new LoaderRegistration(
            false, LoaderRegistration.Container.MAPPED_SET, LoaderRegistration.Dispatch.LOAD_MANY);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, RECORD, ASYNC_RESULT,
            reg,
            LAMBDA, KEYS_EXTRACTION, TRIVIAL_TAIL, CATCH_BODY);

        String src = spec.toString();
        assertThat(src).contains("return loader.loadMany(keys, java.util.Collections.nCopies(keys.size(), env))");
    }

    @Test
    void loaderTypeReflectsKeyAndValueGenericArgs() {
        var reg = new LoaderRegistration(
            true, LoaderRegistration.Container.POSITIONAL_LIST, LoaderRegistration.Dispatch.LOAD_ONE);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg,
            LAMBDA, KEY_EXTRACTION, TRIVIAL_TAIL, CATCH_BODY);

        // DataLoader<Integer, List<Record>> — first generic = key, second = loader's per-key V.
        assertThat(spec.toString())
            .contains("org.dataloader.DataLoader<java.lang.Integer, java.util.List<org.jooq.Record>> loader");
    }

    @Test
    void asyncWrapTail_isPastedAfterDispatchCall() {
        var reg = new LoaderRegistration(
            true, LoaderRegistration.Container.POSITIONAL_LIST, LoaderRegistration.Dispatch.LOAD_ONE);
        CodeBlock customTail = CodeBlock.of(".thenApply(payload -> payload).exceptionally(t -> null)");

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg,
            LAMBDA, KEY_EXTRACTION, customTail, CATCH_BODY);

        String src = spec.toString();
        assertThat(src).contains("return loader.load(key, env)");
        assertThat(src).contains(".thenApply(payload -> payload).exceptionally(t -> null);");
    }

    @Test
    void keyExtractionAndDispatch_areWrappedInTryCatchRoutingSyncCatchBody() {
        // A synchronous throw out of the key extraction (before dispatch) must be
        // caught and routed, not escape DataFetcher.get() unredacted. The guard wraps the
        // extraction + dispatch + async tail; the caller-supplied catch body is the arm.
        var reg = new LoaderRegistration(
            true, LoaderRegistration.Container.POSITIONAL_LIST, LoaderRegistration.Dispatch.LOAD_ONE);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg,
            LAMBDA, KEY_EXTRACTION, TRIVIAL_TAIL, CATCH_BODY);

        String src = spec.toString();
        // Loader registration is outside the guard; the key extraction is inside it.
        int registration = src.indexOf("computeIfAbsent");
        int tryStart = src.indexOf("try {");
        int extraction = src.indexOf("key = ((org.jooq.Record) env.getSource())");
        int catchArm = src.indexOf("catch (java.lang.Throwable e)");
        assertThat(tryStart).isGreaterThan(registration);
        assertThat(extraction).isGreaterThan(tryStart);
        assertThat(catchArm).isGreaterThan(extraction);
        assertThat(src).contains("return java.util.concurrent.CompletableFuture.completedFuture(null);");
    }
}
