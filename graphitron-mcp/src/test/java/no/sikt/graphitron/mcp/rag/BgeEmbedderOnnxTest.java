package no.sikt.graphitron.mcp.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Infrastructure-tier: actually loads {@code bge-small-en-v1.5-q} through the ONNX Runtime
 * JNI binding and embeds real text. This is the native-binding backstop, the analogue of the
 * sakila-example compile that runs on every CI build: it is the only check that the native binding
 * produces <em>meaningful</em> output, so it runs in CI's default {@code mvn verify -Plocal-db}.
 *
 * <p>The semantic assertion is written to fail for the right reason, not to flake on a model-version
 * bump or cross-arch ONNX numerics: the related / unrelated sentence pair is chosen so its
 * separation is large for any sane embedding, and the assertion is a strict inequality with a
 * comfortable margin, not a hand-tuned epsilon.
 *
 * <p>Carries a plain {@code @Tag("slow")} (not a tier meta-annotation) purely so a developer's fast
 * inner loop can exclude it with {@code -DexcludedGroups=slow}, mirroring how
 * {@code -DexcludedGroups=execution} skips Postgres locally. The tag is a local-loop convenience,
 * not a CI skip: CI runs everything.
 */
@Tag("slow")
class BgeEmbedderOnnxTest {

    @Test
    void loadsTheRealModelAndEmbedsAtTheExpectedDimensionWithMeaningfulSimilarity() {
        var embedder = new BgeEmbedder();

        assertThat(embedder.dimension()).isEqualTo(384);

        var embeddings = embedder.embedDocuments(List.of(
            "The cat slept on the warm windowsill in the afternoon sun.",   // 0
            "A kitten napped by the sunny window all afternoon.",            // 1, related to 0
            "Quarterly corporate tax filings are due before the fiscal year ends." // 2, unrelated
        ));

        assertThat(embeddings).hasSize(3);
        assertThat(embeddings.get(0).vector()).hasSize(384);

        double related = cosine(embeddings.get(0).vector(), embeddings.get(1).vector());
        double unrelated = cosine(embeddings.get(0).vector(), embeddings.get(2).vector());

        // A comfortable margin: the two cat sentences are far closer than the cat / tax pair for any
        // sane sentence embedding, so a strict inequality with a generous gap cannot flake on numerics.
        assertThat(related).isGreaterThan(unrelated + 0.1);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
