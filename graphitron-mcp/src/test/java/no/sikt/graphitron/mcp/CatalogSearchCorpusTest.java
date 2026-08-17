package no.sikt.graphitron.mcp;

import no.sikt.graphitron.mcp.rag.AsyncWarm;
import no.sikt.graphitron.mcp.rag.CatalogSearchIndex;
import no.sikt.graphitron.mcp.rag.CorpusTable;
import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.mcp.rag.FakeEmbedder;
import no.sikt.graphitron.mcp.rag.RagConfig;
import no.sikt.graphitron.mcp.rag.WarmState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier: what {@code catalog.search} embeds, over a store a real capture wrote. The composer's
 * formatting and the index's invalidation are pinned without a store elsewhere; what needs a capture
 * is the claim between them, that the rows the census yields are the strings the embedder is handed.
 *
 * <p>That claim is where the risk moved when the corpus stopped being byte-identical. The descriptor
 * folds column order into its text and the hash digests the descriptors in table order, so both
 * orderings are part of the corpus identity, and a census read returning them in no stated order
 * would re-embed the whole catalog on calls that changed nothing.
 */
class CatalogSearchCorpusTest {

    private static final String SDL = """
        type Film @table(name: "film") {
          film_id: Int
        }
        type Query {
          film: Film
        }
        """;

    /**
     * The corpus is the census: every table the {@code catalog.tables} count reports, ordered by the
     * pair its id is spelled from, with each table's columns in the order {@code sql_column.ordinal}
     * states and the database's own comments where the DDL declares them.
     */
    @Test
    void theCorpusIsTheCensusWithItsTablesAndColumnsInTheirStatedOrder(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(tmp, "catalog-search-corpus", SDL)) {
            var corpus = CatalogQueries.searchCorpus(build.reader(), build.graphName);

            // Against the page the catalog.tables tool reads off the same relation, drawn whole so the
            // comparison cannot be against a partial page.
            var census = CatalogQueries.tables(
                build.handle(), Optional.empty(), Optional.empty(), Optional.empty(), 500);
            assertThat(census.entries()).hasSize(census.total());
            assertThat(corpus).extracting(CorpusTable::id)
                .as("the corpus is the census, table for table and in the order the hash digests")
                .isEqualTo(census.entries().stream().map(t -> t.schema() + "." + t.name()).toList());

            var film = corpus.stream().filter(t -> t.id().equals("public.film")).findFirst().orElseThrow();
            assertThat(film.comment()).isEqualTo("One film in the rental catalogue.");
            assertThat(film.columns()).extracting(CorpusTable.Column::name)
                .as("the table definition's order, not a reflective field walk's")
                .startsWith("film_id", "title", "description", "release_year", "language_id");
            assertThat(film.columns().getFirst().comment())
                .isEqualTo("Surrogate key, stable across catalogue imports.");

            // A table whose DDL declares no comment carries none, rather than an empty one.
            var actor = corpus.stream().filter(t -> t.id().equals("public.actor")).findFirst().orElseThrow();
            assertThat(actor.comment()).isNull();
        }
    }

    /**
     * The strings the embedder receives are the census composed, one per table in corpus order. This is
     * the whole chain the wire cannot show: {@code catalog.search} returns ids and scores, so a corpus
     * embedded from the wrong text would rank badly rather than answer wrongly.
     */
    @Test
    void theEmbeddedTextIsTheCensusRowComposedWithItsComments(@TempDir Path tmp) throws Exception {
        var embedder = new SpyEmbedder();
        var embedderWarm = new AsyncWarm<Embedder>("embedder", () -> embedder);
        embedderWarm.start();

        try (var build = StoreBackedBuild.run(tmp, "catalog-search-embedded", SDL);
             var index = new CatalogSearchIndex(
                 () -> CatalogQueries.searchCorpus(build.reader(), build.graphName),
                 embedderWarm, RagConfig.temporary())) {
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);

            var corpus = CatalogQueries.searchCorpus(build.reader(), build.graphName);
            assertThat(embedder.lastTexts).hasSameSizeAs(corpus);

            String film = embedder.lastTexts.stream()
                .filter(text -> text.startsWith("Table film (film)"))
                .findFirst().orElseThrow();
            assertThat(film).contains("\nComment: One film in the rental catalogue.\n");
            // Raw token beside the normalized words, and the column's own comment after it.
            assertThat(film).contains("film_id (film id): Surrogate key, stable across catalogue imports.");
            // The columns arrive in definition order, so film_id precedes title precedes description.
            assertThat(film.indexOf("film_id")).isLessThan(film.indexOf("title"));
            assertThat(film.indexOf("title")).isLessThan(film.indexOf("description"));
        }
    }

    /**
     * A bare table name two schemas declare is two corpus entries, each with its own id. The corpus is
     * keyed by the qualified pair, so a name {@code catalog.describe} would call ambiguous costs the
     * index nothing: both tables are discoverable and each hit re-selects the one it names.
     */
    @Test
    void aNameTwoSchemasDeclareIsTwoCorpusEntries(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(
            tmp, "catalog-search-multischema", SDL, StoreBackedBuild.MULTISCHEMA_JOOQ_PACKAGE)) {
            var corpus = CatalogQueries.searchCorpus(build.reader(), build.graphName);

            assertThat(corpus).extracting(CorpusTable::id)
                .contains("multischema_a.event", "multischema_b.event");
            assertThat(corpus).filteredOn(t -> t.name().equals("event"))
                .allSatisfy(table -> assertThat(table.columns()).isNotEmpty());
        }
    }

    /** Records the texts it was handed; the vectors are {@link FakeEmbedder}'s, ONNX-free. */
    private static final class SpyEmbedder implements Embedder {

        private static final int DIMENSION = 8;

        volatile List<String> lastTexts = List.of();

        @Override
        public Query embedQuery(String text) {
            return new Query(text, FakeEmbedder.oneHot(text, DIMENSION));
        }

        @Override
        public List<Embedding> embedDocuments(List<String> texts) {
            lastTexts = List.copyOf(texts);
            return texts.stream().map(t -> new Embedding(t, FakeEmbedder.oneHot(t, DIMENSION))).toList();
        }

        @Override
        public int dimension() {
            return DIMENSION;
        }
    }
}
