package no.sikt.graphitron.mcp.rag.docs;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier (R385): the bundle write → read round-trip preserves the chunk count, ids, dimension, and
 * vector widths, and the opaque payload decodes back to the chunk's display fields (including a body
 * carrying the field/heading separators, which the Base64 encoding must not collide with).
 */
class DocsBundleTest {

    @Test
    void writeThenReadPreservesCountIdsDimensionAndVectorWidths() {
        var c1 = new DocChunk(List.of("Guide", "Pagination"),
            "docs/manual/explanation/batching-model.adoc", "keyset-seek", "Keyset body.");
        var c2 = new DocChunk(List.of("Reference"),
            "docs/manual/reference/index.adoc", "", "Reference body with a | pipe and a , comma.");
        var entries = List.of(
            new DocsBundle.Entry(c1.id(), c1.embedText(), DocsBundle.encodePayload(c1), new float[] {0.1f, 0.2f, 0.3f}),
            new DocsBundle.Entry(c2.id(), c2.embedText(), DocsBundle.encodePayload(c2), new float[] {0.4f, 0.5f, 0.6f}));

        var buf = new ByteArrayOutputStream();
        DocsBundle.write(buf, 3, entries);
        DocsBundle.Loaded loaded = DocsBundle.read(new ByteArrayInputStream(buf.toByteArray()));

        assertThat(loaded.dimension()).isEqualTo(3);
        assertThat(loaded.entries()).hasSize(2);
        assertThat(loaded.entries()).extracting(DocsBundle.Entry::id)
            .containsExactly(c1.id(), c2.id());
        assertThat(loaded.entries().get(0).vector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(loaded.entries().get(1).vector()).hasSize(3);
    }

    @Test
    void payloadDecodesBackToTheChunkDisplayFieldsEvenWithSeparatorsInTheBody() {
        var chunk = new DocChunk(List.of("Pagination", "Keyset seek"),
            "docs/manual/explanation/batching-model.adoc", "keyset-seek",
            "Body with | a pipe and , a comma and a\nnewline.");

        DocChunk decoded = DocsBundle.decodePayload(DocsBundle.encodePayload(chunk));

        assertThat(decoded.headingPath()).containsExactly("Pagination", "Keyset seek");
        assertThat(decoded.sourcePath()).isEqualTo("docs/manual/explanation/batching-model.adoc");
        assertThat(decoded.anchor()).isEqualTo("keyset-seek");
        assertThat(decoded.text()).isEqualTo("Body with | a pipe and , a comma and a\nnewline.");
    }

    @Test
    void readDimensionReadsOnlyTheHeader() {
        var buf = new ByteArrayOutputStream();
        DocsBundle.write(buf, 384, List.of());
        assertThat(DocsBundle.readDimension(new ByteArrayInputStream(buf.toByteArray()))).isEqualTo(384);
    }
}
