package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.EntryFamilyFixture;
import no.sikt.graphitron.model.test.FactGraphs;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate behind "each crawler is responsible for a corpus that exists independently". It is
 * testable as a differential: read one registry twice, once with the jOOQ catalog and once
 * without, and the rows the SDL crawlers wrote about the SDL corpus have to be identical. The two
 * readings are two fact documents pulling in one shape, one stating no catalog, each a graph of the
 * population; their rows are compared with what names the graph and where it was written left out. If they are
 * not, some crawler read another corpus and its output is no longer a transcription of the one it
 * answers for.
 *
 * <p>No foreign key could have enforced this, which is why it is a test. A key constrains references,
 * and the schema already refuses to model SDL-to-jOOQ resolution as one; a cross-corpus read inside a
 * crawler adds no reference, it changes which rows exist.
 *
 * <p>Two things are in scope and they are in scope for the same reason. {@code graphql_} is the
 * transcription of the documents. Beside it is the as-written half of {@code graphitron_}, the
 * relations whose rows are a function of one document and nothing else, which
 * {@link EntryFamilyFixture#entryRelations()} names and a coverage gate in the model module holds
 * to being populated. Those rows restate a directive application in graphitron's vocabulary, so
 * "does not vary with the catalog" is a property they have by construction and one worth holding
 * them to. The catalog's own families are deliberately out of scope, being exactly the rows whose
 * presence the two arms differ by.
 *
 * <p>Neither half arrives as a list, and that is the point of both spellings. A prefix is the whole
 * of the {@code graphql_} family and a {@code _entry} suffix is the whole of the as-written half, so
 * both are read off the generated model and a relation added to either is in scope without being
 * named here. What a name cannot do is hold a new decode relation to carrying the suffix in the
 * first place, and that is {@code EntryNamingGuardTest}'s, over the decode's own source.
 *
 * <p>The resolved half of {@code graphitron_} is out of scope, and that is what the family is rather
 * than a concession: a stage joining an entry against the catalog varies with the catalog by
 * construction, and that is the stage doing its job. What holds it in place is the ownership rule,
 * which puts a conclusion drawn about the schema in a relation whose owner declares the catalog
 * among its reads.
 *
 * <p>The fixtures have to be able to fail, and the two cases below fail differently. The first is
 * federation-linked and carries an inferred-node shape, a {@code @table} type implementing
 * {@code Node} over a table whose generated class publishes node metadata, which is the exact pair
 * the retired capture-time expansion read across: before that move the catalog arm wrote a
 * synthesized {@code @key} into {@code graphql_directive_application} and its decode, and the bare arm did
 * not. The second is the entry fixture, which exists because most of the entry half holds no row
 * under the first: a differential over relations nobody wrote to agrees by being empty twice rather
 * than by agreeing, so that case holds every entry relation to being populated as well as to
 * agreeing.
 */
class CaptureCorpusIsolationTest {

    /** The family whose rows are about the SDL corpus, and must not vary with the catalog. */
    private static final List<String> SDL_FAMILIES = List.of("graphql_");

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    @DisplayName("the SDL crawlers write the same rows whether or not the catalog is there")
    void theSdlFamiliesDoNotVaryWithTheCatalog() {
        differential("defects/catalog-isolation", "defects/catalog-isolation-bare");
    }

    @Test
    @DisplayName("nor does the entry half, over a corpus that populates all of it")
    void theEntryHalfDoesNotVaryWithTheCatalog() {
        var bare = differential("defects/entry-family", "defects/entry-family-bare");

        var empty = EntryFamilyFixture.entryRelations().stream()
            .filter(relation -> bare.get(relation).isEmpty())
            .toList();
        assertThat(empty)
            .as("an entry relation this corpus writes no row into is compared empty against empty, "
                + "so widening the scope to it bought nothing; the shape owes it an application")
            .isEmpty();
    }

    /**
     * A control on the differential: the catalog arm has to have read a catalog, or the two graphs
     * would agree because neither read one and the gate would pass for the wrong reason. Read
     * through each graph's sources, the catalog rows being shared by every graph that read it.
     */
    @Test
    @DisplayName("the catalog arm really read a catalog")
    void theCatalogArmIsNotVacuous() {
        FACTS.read("defects/catalog-isolation", graph -> {
            assertThat(catalogRows(graph.handle(), SQL_TABLE))
                .as("the fixture jOOQ package declares tables").isPositive();
            assertThat(catalogRows(graph.handle(), SQL_NODE_METADATA))
                .as("and one of them publishes the node metadata the inferred shape needs")
                .isPositive();
            return null;
        });
        FACTS.read("defects/catalog-isolation-bare", graph -> {
            assertThat(catalogRows(graph.handle(), SQL_TABLE)).isZero();
            return null;
        });
    }

    /** How many of {@code relation}'s rows come from a source the graph read. */
    private static int catalogRows(StoreHandle handle, Table<?> relation) {
        var sourceName = relation.field("SOURCE_NAME", String.class);
        return handle.dsl().fetchCount(handle.dsl().selectOne().from(relation)
            .join(STORE_GRAPH_SOURCE).on(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(sourceName))
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(handle.graphName())));
    }

    /**
     * Reads one shape under both arms and holds every in-scope relation to agreeing, returning the
     * bare arm's rows so a caller can say something further about what it just compared.
     */
    private static Map<String, List<String>> differential(String withCatalog, String withoutCatalog) {
        var catalogArm = rowsOf(withCatalog);
        var bareArm = rowsOf(withoutCatalog);

        assertThat(inScope())
            .as("the gate reads relations, so an empty scope would pass vacuously")
            .isNotEmpty();
        assertThat(catalogArm)
            .as("the two readings read one registry, so every SDL relation is compared")
            .containsOnlyKeys(bareArm.keySet().toArray(String[]::new));

        var differing = new ArrayList<String>();
        catalogArm.forEach((relation, rows) -> {
            var bare = bareArm.get(relation);
            if (!rows.equals(bare)) {
                differing.add(relation + ": with the catalog only " + firstMissing(rows, bare)
                    + ", without it only " + firstMissing(bare, rows));
            }
        });
        assertThat(differing)
            .as("a crawler whose rows about one corpus vary with another corpus's contents; move the "
                + "rule to a derivation over the captured facts of both rather than exempting it here")
            .isEmpty();
        return bareArm;
    }

    /** Up to two of {@code rows} that {@code other} lacks, which is what a failure has to show. */
    private static List<String> firstMissing(List<String> rows, List<String> other) {
        return rows.stream().filter(row -> !other.contains(row)).limit(2).toList();
    }

    /** Every in-scope relation's rows in {@code path}'s graph. */
    private static Map<String, List<String>> rowsOf(String path) {
        return FACTS.read(path, graph -> {
            var handle = graph.handle();
            String baseDir = handle.dsl().select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
                .where(STORE_GRAPH.GRAPH_NAME.eq(handle.graphName()))
                .fetchSingle(STORE_GRAPH.BASE_DIR);
            var rows = new LinkedHashMap<String, List<String>>();
            for (Table<?> relation : inScope()) {
                rows.put(relation.getName().toLowerCase(Locale.ROOT),
                    contentsOf(handle, baseDir, relation));
            }
            return rows;
        });
    }

    /**
     * Every declared relation the gate compares: the SDL families by prefix, the entry half by name,
     * each keyed by graph so the graph's own rows can be told from the population's.
     */
    private static List<Table<?>> inScope() {
        return Public.PUBLIC.getTables().stream()
            .filter(table -> {
                String name = table.getName().toLowerCase(Locale.ROOT);
                return SDL_FAMILIES.stream().anyMatch(name::startsWith)
                    || EntryFamilyFixture.entryRelations().contains(name);
            })
            .filter(table -> table.field("GRAPH_NAME") != null)
            .toList();
    }

    /**
     * A relation's rows in one graph as sorted rendered tuples, so the comparison is
     * order-independent and a failure names the rows rather than a count.
     *
     * <p>Three things are left out, all of them about the reading rather than the corpus: the graph's
     * name, wherever a row carries it, the directory its files were written to, which every source
     * name begins with, and {@code touched_at}, which records when the reading ran. {@code SupertypeSignatureGateTest}
     * subtracts the same column from a payload for the same reason.
     */
    private static List<String> contentsOf(StoreHandle handle, String baseDir, Table<?> relation) {
        var compared = Arrays.stream(relation.fields())
            .filter(field -> !"TOUCHED_AT".equalsIgnoreCase(field.getName()))
            .filter(field -> !"GRAPH_NAME".equalsIgnoreCase(field.getName()))
            .toList();
        return handle.dsl().select(compared).from(relation)
            .where(relation.field("GRAPH_NAME", String.class).eq(handle.graphName()))
            .fetch().stream()
            .map(record -> record.intoList().toString().replace(baseDir, "<dir>")
                .replace(handle.graphName(), "<graph>"))
            .sorted()
            .toList();
    }
}
