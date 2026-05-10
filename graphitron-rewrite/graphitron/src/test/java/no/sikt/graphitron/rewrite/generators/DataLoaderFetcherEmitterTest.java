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
 * vs mapped), the key-extraction paste-through, the load-vs-loadMany dispatch, and the
 * async-wrap tail paste-through.
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

    private static final CodeBlock CTX_CALL = CodeBlock.of("graphitronContext(env)");
    private static final CodeBlock LAMBDA   = CodeBlock.of("(keys, batchEnv) -> { /* lambda */ }");
    private static final CodeBlock KEY_EXTRACTION = CodeBlock.builder()
        .addStatement("$T key = ((org.jooq.Record) env.getSource()).get($S)", Integer.class, "language_id")
        .build();
    private static final CodeBlock KEYS_EXTRACTION = CodeBlock.builder()
        .addStatement("$T<Integer> keys = java.util.List.of()", LIST)
        .build();
    private static final CodeBlock TRIVIAL_TAIL = CodeBlock.of(".thenApply(java.util.function.Function.identity())");

    @Test
    void positionalList_loadOne_emitsNewDataLoaderAndLoadKeyEnv() {
        var reg = new LoaderRegistration("Language.films", true, LoaderRegistration.Container.POSITIONAL_LIST);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg,
            DataLoaderFetcherEmitter.Invocation.LOAD_ONE,
            CTX_CALL, LAMBDA, KEY_EXTRACTION, TRIVIAL_TAIL);

        String src = spec.toString();
        assertThat(src).contains("public static java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>> films(");
        assertThat(src).contains("graphql.schema.DataFetchingEnvironment env");
        assertThat(src).contains("name = graphitronContext(env).getTenantId(env) + \"/\" + java.lang.String.join(\"/\", env.getExecutionStepInfo().getPath().getKeysOnly())");
        assertThat(src).contains("org.dataloader.DataLoaderFactory.newDataLoader(");
        assertThat(src).contains("return loader.load(key, env)");
    }

    @Test
    void mappedSet_loadOne_emitsNewMappedDataLoader() {
        var reg = new LoaderRegistration("Query.filmsByActor", true, LoaderRegistration.Container.MAPPED_SET);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "filmsByActor",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg,
            DataLoaderFetcherEmitter.Invocation.LOAD_ONE,
            CTX_CALL, LAMBDA, KEY_EXTRACTION, TRIVIAL_TAIL);

        String src = spec.toString();
        assertThat(src).contains("org.dataloader.DataLoaderFactory.newMappedDataLoader(");
        assertThat(src).contains("return loader.load(key, env)");
    }

    @Test
    void loadMany_emitsLoaderLoadManyWithDuplicatedContexts() {
        var reg = new LoaderRegistration("Payload.films", true, LoaderRegistration.Container.POSITIONAL_LIST);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, RECORD, ASYNC_RESULT,
            reg,
            DataLoaderFetcherEmitter.Invocation.LOAD_MANY,
            CTX_CALL, LAMBDA, KEYS_EXTRACTION, TRIVIAL_TAIL);

        String src = spec.toString();
        assertThat(src).contains("return loader.loadMany(keys, java.util.Collections.nCopies(keys.size(), env))");
    }

    @Test
    void loaderTypeReflectsKeyAndValueGenericArgs() {
        var reg = new LoaderRegistration("Language.films", true, LoaderRegistration.Container.POSITIONAL_LIST);

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg, DataLoaderFetcherEmitter.Invocation.LOAD_ONE,
            CTX_CALL, LAMBDA, KEY_EXTRACTION, TRIVIAL_TAIL);

        // DataLoader<Integer, List<Record>> — first generic = key, second = loader's per-key V.
        assertThat(spec.toString())
            .contains("org.dataloader.DataLoader<java.lang.Integer, java.util.List<org.jooq.Record>> loader");
    }

    @Test
    void asyncWrapTail_isPastedAfterDispatchCall() {
        var reg = new LoaderRegistration("Language.films", true, LoaderRegistration.Container.POSITIONAL_LIST);
        CodeBlock customTail = CodeBlock.of(".thenApply(payload -> payload).exceptionally(t -> null)");

        MethodSpec spec = DataLoaderFetcherEmitter.build(
            "films",
            KEY, LIST_OF_RECORD, ASYNC_RESULT,
            reg, DataLoaderFetcherEmitter.Invocation.LOAD_ONE,
            CTX_CALL, LAMBDA, KEY_EXTRACTION, customTail);

        String src = spec.toString();
        assertThat(src).contains("return loader.load(key, env)");
        assertThat(src).contains(".thenApply(payload -> payload).exceptionally(t -> null);");
    }
}
