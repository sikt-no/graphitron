package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoaderConstruction}: the two container shapes pick their factory method, and both pass
 * the router's options for the environment the caller names.
 */
@UnitTier
class LoaderConstructionTest {

    private static final ClassName ROUTER = ClassName.get("com.example.schema", "ErrorRouter");
    private static final CodeBlock LAMBDA = CodeBlock.of("(keys, batchEnv) -> rows(keys)");

    @Test
    void positional_buildsAListKeyedLoaderWithTheRoutersOptions() {
        assertThat(LoaderConstruction.positional(LAMBDA, "env", ROUTER).toString()).isEqualTo(
            "org.dataloader.DataLoaderFactory.newDataLoader((keys, batchEnv) -> rows(keys),"
                + " com.example.schema.ErrorRouter.loaderOptions(env))");
    }

    @Test
    void mapped_buildsASetKeyedLoader_readingTheOptionsWithTheNamedEnvironment() {
        assertThat(LoaderConstruction.mapped(LAMBDA, "idEnv", ROUTER).toString()).isEqualTo(
            "org.dataloader.DataLoaderFactory.newMappedDataLoader((keys, batchEnv) -> rows(keys),"
                + " com.example.schema.ErrorRouter.loaderOptions(idEnv))");
    }
}
