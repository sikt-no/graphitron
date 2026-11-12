package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

/**
 * The construction of a DataLoader, in its one spelling:
 * {@code DataLoaderFactory.<factory>(<batch lambda>, ErrorRouter.loaderOptions(<env>))}.
 *
 * <p>Every emitter that registers a loader builds it here, so every loader is built with the
 * request's {@code DataLoaderOptions}, which is where a consumer's {@code BatchSizeLimit} takes
 * effect. The expression belongs inside the registry's {@code computeIfAbsent} lambda, so the
 * options are looked up once per loader per request, with the environment of the first field
 * that needed the loader. The two entry points are the two container shapes a batch lambda
 * comes in; they take the generated router they name, as {@link ErrorDispatchFragments} does.
 */
public final class LoaderConstruction {

    private LoaderConstruction() {}

    private static final ClassName DATA_LOADER_FACTORY = ClassName.get("org.dataloader", "DataLoaderFactory");

    /** The router's static that answers the request's {@code DataLoaderOptions}. */
    public static final String LOADER_OPTIONS_METHOD = "loaderOptions";

    /**
     * A positional loader: the batch lambda takes a {@code List} of keys and answers a list of
     * values in key order.
     *
     * @param batchLambda the {@code (keys, batchEnv) -> ...} lambda
     * @param envVar      the in-scope {@code DataFetchingEnvironment} local the options are read with
     * @param errorRouter the generated {@code ErrorRouter}
     */
    public static CodeBlock positional(CodeBlock batchLambda, String envVar, ClassName errorRouter) {
        return construction("newDataLoader", batchLambda, envVar, errorRouter);
    }

    /**
     * A mapped loader: the batch lambda takes a {@code Set} of keys and answers a map from key to
     * value.
     *
     * @param batchLambda the {@code (keys, batchEnv) -> ...} lambda
     * @param envVar      the in-scope {@code DataFetchingEnvironment} local the options are read with
     * @param errorRouter the generated {@code ErrorRouter}
     */
    public static CodeBlock mapped(CodeBlock batchLambda, String envVar, ClassName errorRouter) {
        return construction("newMappedDataLoader", batchLambda, envVar, errorRouter);
    }

    private static CodeBlock construction(String factoryMethod, CodeBlock batchLambda, String envVar,
                                          ClassName errorRouter) {
        return CodeBlock.of("$T.$L($L, $T.$L($L))", DATA_LOADER_FACTORY, factoryMethod, batchLambda,
            errorRouter, LOADER_OPTIONS_METHOD, envVar);
    }
}
