package no.sikt.graphitron.mcp.rag;

import no.sikt.graphitron.mcp.StoreFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier: the corpus read and the descriptor composed from it, over a store a real capture
 * wrote. What needs a capture is precisely the claim the composer's own unit cases cannot make, that
 * the rows the census yields are the string the embedder is handed.
 *
 * <p>Both orderings the corpus is keyed by are asserted here, and they are load-bearing rather than
 * cosmetic: the descriptor folds column order into its text and the hash digests the descriptors in
 * table order, so a read returning either in no stated order would re-embed the whole catalog on calls
 * that changed nothing.
 */
class CatalogCorpusTest {

    @Test
    void theCorpusIsTheCensusWithItsTablesAndColumnsInTheirStatedOrder(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCatalog(tmp)) {
            var corpus = CatalogCorpus.read(fixture.reader(), fixture.graphName());

            assertThat(corpus).extracting(CorpusTable::id)
                .as("ordered by the pair the id is spelled from, which is the order the hash digests")
                .isSorted();
            assertThat(corpus).extracting(CorpusTable::id)
                .contains("public.film", "public.actor", "public.project_note");

            var film = table(corpus, "public.film");
            assertThat(film.columns()).extracting(CorpusTable.Column::name)
                .as("the table definition's order, which is what sql_column.ordinal states")
                .startsWith("film_id", "title", "description", "release_year", "language_id");

            // A table with no columns is not a shape the census writes, so every entry carries some.
            assertThat(corpus).allSatisfy(entry -> assertThat(entry.columns()).isNotEmpty());
        }
    }

    /**
     * The descriptor over real rows: the raw SQL token beside its normalized words, the database's own
     * comments at both grains, and the names-only degradation on a table whose DDL declares none. The
     * arms are the ones {@code CatalogDescriptorsTest} states as formatting; what this adds is that a
     * capture supplies them.
     */
    @Test
    void theDescriptorCarriesTheCensusCommentsAndDegradesWithoutThem(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCatalog(tmp)) {
            var corpus = CatalogCorpus.read(fixture.reader(), fixture.graphName());

            String film = CatalogDescriptors.descriptor(table(corpus, "public.film"));
            assertThat(film).startsWith("Table film (film)\nComment: One film in the rental catalogue.\n");
            assertThat(film).contains("film_id (film id): Surrogate key, stable across catalogue imports.");
            // A commentless column carries its names alone, with no dangling separator.
            assertThat(film).contains("release_year (release year),");
            assertThat(film).doesNotContain("release_year (release year):");
            // Columns reach the text in definition order, so the raw tokens read as the table does.
            assertThat(film.indexOf("film_id")).isLessThan(film.indexOf("title"));
            assertThat(film.indexOf("title")).isLessThan(film.indexOf("description"));

            String actor = CatalogDescriptors.descriptor(table(corpus, "public.actor"));
            assertThat(actor).as("a table the DDL leaves uncommented degrades to names only")
                .doesNotContain("Comment:");
            assertThat(actor).startsWith("Table actor (actor)\nColumns: actor_id (actor id)");
        }
    }

    /**
     * A bare name two schemas declare is two entries, each with its own id. The corpus is keyed by the
     * qualified pair, so a spelling {@code catalog.describe} would call ambiguous costs the index
     * nothing: both tables are discoverable and each hit re-selects the one it names.
     */
    @Test
    void aNameTwoSchemasDeclareIsTwoCorpusEntries(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofMultiSchemaCatalog(tmp)) {
            var corpus = CatalogCorpus.read(fixture.reader(), fixture.graphName());

            assertThat(corpus).extracting(CorpusTable::id)
                .contains("multischema_a.event", "multischema_b.event");
            assertThat(corpus).filteredOn(t -> t.name().equals("event")).hasSize(2);
        }
    }

    /**
     * One graph's corpus holds its own catalog sources and no other graph's. The {@code sql_} family is
     * source-keyed rather than graph-keyed, so this is the semi-join over the membership relation doing
     * its work: without it an agent would find a table its own schema cannot reach, in a store two
     * modules share.
     */
    @Test
    void aGraphsCorpusExcludesAnotherGraphsCatalog(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCatalog(tmp)
            .andGraph("sibling", StoreFixture.MULTISCHEMA_JOOQ_PACKAGE)) {
            var own = CatalogCorpus.read(fixture.reader(), fixture.graphName());
            assertThat(own).extracting(CorpusTable::id)
                .contains("public.film")
                .noneMatch(id -> id.startsWith("multischema_"));

            var sibling = CatalogCorpus.read(fixture.reader(), "sibling");
            assertThat(sibling).extracting(CorpusTable::id)
                .contains("multischema_a.event")
                .noneMatch(id -> id.startsWith("public."));
        }
    }

    /**
     * Before the first codegen the census is empty, and an empty corpus is an answer rather than a
     * failure: the index hashes it, embeds nothing, and reports ready. What a search over it returns is
     * no hits, which is the truth about a catalog nobody has generated yet.
     */
    @Test
    void aCaptureWithNoCatalogYieldsAnEmptyCorpus(@TempDir Path tmp) {
        try (var fixture = StoreFixture.withoutCatalog(tmp)) {
            assertThat(CatalogCorpus.read(fixture.reader(), fixture.graphName())).isEmpty();
        }
    }

    private static CorpusTable table(java.util.List<CorpusTable> corpus, String id) {
        return corpus.stream().filter(t -> t.id().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("no corpus entry for " + id));
    }
}
