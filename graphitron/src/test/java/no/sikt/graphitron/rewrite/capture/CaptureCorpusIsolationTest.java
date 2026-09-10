package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.EntryFamilyFixture;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate behind "each crawler is responsible for a corpus that exists independently". It is
 * testable as a differential: capture one registry twice, once with the jOOQ catalog and once
 * without, and the rows the SDL crawlers wrote about the SDL corpus have to be identical. If they are
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
 * synthesized {@code @key} into {@code graphql_type_directive} and its decode, and the bare arm did
 * not. The second is the entry fixture, which exists because most of the entry half holds no row
 * under the first: a differential over relations nobody wrote to agrees by being empty twice rather
 * than by agreeing, so that case holds every entry relation to being populated as well as to
 * agreeing.
 */
@PipelineTier
class CaptureCorpusIsolationTest {

    /** The family whose rows are about the SDL corpus, and must not vary with the catalog. */
    private static final List<String> SDL_FAMILIES = List.of("graphql_");

    private static final String FIXTURE = """
        directive @link(url: String!, import: [String]) repeatable on SCHEMA
        directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT

        extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

        type Query { pairing: Pairing, film: Film, actor: Actor }

        interface Node { id: ID! }

        type Pairing implements Node @table(name: "film_actor") {
          id: ID!
        }

        type Film implements Node @node {
          id: ID!
          title: String
        }

        type Actor implements Node @node @key(fields: "id") {
          id: ID!
          name: String
        }
        """;

    @Test
    @DisplayName("the SDL crawlers write the same rows whether or not the catalog is there")
    void theSdlFamiliesDoNotVaryWithTheCatalog(@TempDir Path tmp) {
        // One directory for both arms, so the fixture's path is one string and the source names the
        // walk transcribes are the same on either side. Two directories would make every row differ
        // on a column that is about where the test wrote a file.
        differential(
            () -> CapturedStore.ofCatalog(tmp, FIXTURE, catalog()),
            () -> CapturedStore.of(tmp, FIXTURE));
    }

    @Test
    @DisplayName("nor does the entry half, over a corpus that populates all of it")
    void theEntryHalfDoesNotVaryWithTheCatalog(@TempDir Path tmp) {
        var bare = differential(
            () -> EntryFamilyFixture.capture(tmp, catalog()),
            () -> EntryFamilyFixture.capture(tmp));

        var empty = EntryFamilyFixture.entryRelations().stream()
            .filter(relation -> bare.get(relation).isEmpty())
            .toList();
        assertThat(empty)
            .as("an entry relation this corpus writes no row into is compared empty against empty, "
                + "so widening the scope to it bought nothing; the fixture owes it an application")
            .isEmpty();
    }

    /**
     * A control on the differential: the catalog arm has to have captured a catalog, or the two
     * stores would agree because neither read one and the gate would pass for the wrong reason.
     */
    @Test
    @DisplayName("the catalog arm really captured a catalog")
    void theCatalogArmIsNotVacuous(@TempDir Path tmp) {
        try (var store = CapturedStore.ofCatalog(tmp, FIXTURE, catalog())) {
            assertThat(store.dsl().fetchCount(SQL_TABLE))
                .as("the fixture jOOQ package declares tables").isPositive();
            assertThat(store.dsl().fetchCount(SQL_NODE_METADATA))
                .as("and one of them publishes the node metadata the inferred shape needs")
                .isPositive();
        }
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(store.dsl().fetchCount(SQL_TABLE)).isZero();
        }
    }

    /**
     * Runs one corpus under both arms and holds every in-scope relation to agreeing, returning the
     * bare arm's rows so a caller can say something further about what it just compared.
     */
    private static Map<String, List<String>> differential(Supplier<CapturedStore> withCatalog,
                                                          Supplier<CapturedStore> withoutCatalog) {
        var catalogArm = rowsOf(withCatalog);
        var bareArm = rowsOf(withoutCatalog);

        assertThat(inScope())
            .as("the gate reads relations, so an empty scope would pass vacuously")
            .isNotEmpty();
        assertThat(catalogArm)
            .as("the two captures read one registry, so every SDL relation is compared")
            .containsOnlyKeys(bareArm.keySet().toArray(String[]::new));

        var differing = new ArrayList<String>();
        catalogArm.forEach((relation, rows) -> {
            if (!rows.equals(bareArm.get(relation))) {
                differing.add(relation);
            }
        });
        assertThat(differing)
            .as("a crawler whose rows about one corpus vary with another corpus's contents; move the "
                + "rule to a derivation over the captured facts of both rather than exempting it here")
            .isEmpty();
        return bareArm;
    }

    private static JooqCatalog catalog() {
        return new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE);
    }

    /** Captures the fixture under one arm and reads every in-scope relation's rows out. */
    private static Map<String, List<String>> rowsOf(Supplier<CapturedStore> arm) {
        try (var store = arm.get()) {
            var rows = new LinkedHashMap<String, List<String>>();
            for (Table<?> relation : inScope()) {
                rows.put(relation.getName().toLowerCase(Locale.ROOT), contentsOf(store.dsl(), relation));
            }
            return rows;
        }
    }

    /** Every declared relation the gate compares: the SDL families by prefix, the entry half by name. */
    private static List<Table<?>> inScope() {
        return Public.PUBLIC.getTables().stream()
            .filter(table -> {
                String name = table.getName().toLowerCase(Locale.ROOT);
                return SDL_FAMILIES.stream().anyMatch(name::startsWith)
                    || EntryFamilyFixture.entryRelations().contains(name);
            })
            .toList();
    }

    /**
     * A relation's rows as sorted rendered tuples, so the comparison is order-independent and a
     * failure names the rows rather than a count.
     *
     * <p>{@code touched_at} is left out, and it is provenance rather than an exemption. It records
     * when the reading ran, so two arms that ran at two instants disagree on it whatever either
     * corpus contains, and this gate asks whether a crawler's rows vary with the other corpus's
     * contents. {@code SupertypeSignatureGateTest} subtracts the same column from a payload for the
     * same reason, so the two gates now agree on what is a fact about a row and what is bookkeeping
     * about the reading that produced it.
     */
    private static List<String> contentsOf(DSLContext dsl, Table<?> relation) {
        var compared = Arrays.stream(relation.fields())
            .filter(field -> !"TOUCHED_AT".equalsIgnoreCase(field.getName()))
            .toList();
        return dsl.select(compared).from(relation).fetch().stream()
            .map(record -> record.intoList().toString())
            .sorted()
            .toList();
    }
}
