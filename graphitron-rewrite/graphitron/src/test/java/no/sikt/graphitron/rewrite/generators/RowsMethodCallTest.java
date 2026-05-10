package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of {@link RowsMethodCall#batchLoaderLambda}: positional vs mapped
 * key-container axis (folded onto {@link LoaderRegistration#container()}) and the lambda
 * body invariant.
 */
@UnitTier
class RowsMethodCallTest {

    private static final TypeName KEY = ClassName.bestGuess("java.lang.Integer");

    @Test
    void positionalList_keysParameterIsListOfKey() {
        var reg = new LoaderRegistration("Language.films", true, LoaderRegistration.Container.POSITIONAL_LIST);
        CodeBlock lambda = RowsMethodCall.batchLoaderLambda("rowsFilms", KEY, reg);

        String src = lambda.toString();
        assertThat(src).contains("(java.util.List<java.lang.Integer> keys, org.dataloader.BatchLoaderEnvironment batchEnv)");
        assertThat(src).contains("graphql.schema.DataFetchingEnvironment dfe = (graphql.schema.DataFetchingEnvironment) batchEnv.getKeyContextsList().get(0);");
        assertThat(src).contains("return java.util.concurrent.CompletableFuture.completedFuture(rowsFilms(keys, dfe));");
    }

    @Test
    void mappedSet_keysParameterIsSetOfKey() {
        var reg = new LoaderRegistration("Query.filmsByActor", true, LoaderRegistration.Container.MAPPED_SET);
        CodeBlock lambda = RowsMethodCall.batchLoaderLambda("loadFilms", KEY, reg);

        String src = lambda.toString();
        assertThat(src).contains("(java.util.Set<java.lang.Integer> keys, org.dataloader.BatchLoaderEnvironment batchEnv)");
        assertThat(src).contains("return java.util.concurrent.CompletableFuture.completedFuture(loadFilms(keys, dfe));");
    }

    @Test
    void lambdaBody_isIdenticalAcrossContainerAxes_modKeysContainerType() {
        var positional = RowsMethodCall.batchLoaderLambda(
            "rowsX", KEY,
            new LoaderRegistration("X", true, LoaderRegistration.Container.POSITIONAL_LIST));
        var mapped = RowsMethodCall.batchLoaderLambda(
            "rowsX", KEY,
            new LoaderRegistration("X", true, LoaderRegistration.Container.MAPPED_SET));

        // Replacing the keys container reduces both lambdas to the same string — the body invariant.
        String normalize = positional.toString().replace("java.util.List<", "C<");
        String normalizeM = mapped.toString().replace("java.util.Set<", "C<");
        assertThat(normalize)
            .as("Outside the keys-container element type, the lambda body is byte-identical "
              + "across the two LoaderRegistration.Container arms")
            .isEqualTo(normalizeM);
    }
}
