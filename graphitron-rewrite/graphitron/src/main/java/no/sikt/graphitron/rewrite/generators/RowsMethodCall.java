package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.ENV;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;

/**
 * Single source of truth for the {@code (keys, batchEnv) -> rowsXxx(keys, dfe)} BatchLoader
 * lambda emitted inside DataLoader registration. R38 Phase 2 replaces the three handcrafted
 * inline lambda blocks (in {@code TypeFetcherGenerator}'s {@code buildServiceDataFetcher},
 * {@code buildSplitQueryDataFetcher}, {@code buildRecordBasedDataFetcher}) with one call to
 * {@link #batchLoaderLambda}. Today's three sites emit byte-identical bodies; the only
 * variation is the keys-container element type ({@code List<K>} for the positional sites,
 * {@code Set<K>} for mapped). This factory folds that single axis onto
 * {@link LoaderRegistration#container()} and pins the lambda body in one place.
 *
 * <p>Lambda body shape (unchanged from today):
 *
 * <pre>{@code
 * (List<K> keys, BatchLoaderEnvironment batchEnv) -> {
 *     DataFetchingEnvironment dfe = (DataFetchingEnvironment) batchEnv.getKeyContextsList().get(0);
 *     return CompletableFuture.completedFuture(rowsMethodName(keys, dfe));
 * }
 * }</pre>
 *
 * <p>The keys parameter is explicitly typed because target-typed inference otherwise picks
 * {@code List<Object>} / {@code Set<Object>} — the call {@code rowsMethodName(keys, dfe)}
 * then can't narrow to the typed key element. Typing one lambda parameter requires typing
 * both per Java lambda syntax rules; both get typed here.
 */
public final class RowsMethodCall {

    private static final ClassName SET                = ClassName.get("java.util", "Set");
    private static final ClassName COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName BATCH_LOADER_ENV   = ClassName.get("org.dataloader", "BatchLoaderEnvironment");

    private RowsMethodCall() {}

    /**
     * Builds the BatchLoader lambda for a DataLoader registration.
     *
     * @param rowsMethodName the rows-method to delegate to. The lambda body emits
     *                       {@code rowsMethodName(keys, dfe)}; the method must already exist
     *                       on the enclosing fetcher class.
     * @param keyType        the per-key element type (today produced by
     *                       {@code BatchKey.keyElementType()}; post-Phase-3 by the new
     *                       source-key shape). Drives the lambda's typed
     *                       {@code List<K>}/{@code Set<K>} parameter.
     * @param registration   the field's {@link LoaderRegistration}; only its
     *                       {@link LoaderRegistration#container()} is consulted to pick
     *                       between {@code List} (positional) and {@code Set} (mapped).
     */
    public static CodeBlock batchLoaderLambda(
            String rowsMethodName,
            TypeName keyType,
            LoaderRegistration registration) {

        ClassName containerClass = registration.container() == LoaderRegistration.Container.MAPPED_SET
            ? SET
            : LIST;
        TypeName keysContainerType = ParameterizedTypeName.get(containerClass, keyType);

        return CodeBlock.builder()
            .add("($T keys, $T batchEnv) -> {\n", keysContainerType, BATCH_LOADER_ENV)
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("return $T.completedFuture($L(keys, dfe))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();
    }
}
