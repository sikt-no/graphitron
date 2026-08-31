package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
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
 * <p>The relation set is enumerated off the generated model by family prefix rather than listed, so
 * the next cross-corpus read fails this without being named here. One family is in scope,
 * {@code graphql_}, the transcription of the documents, and that is the whole of what a crawler
 * answers for. The catalog's own families are deliberately out of scope, being exactly the rows whose
 * presence the two arms differ by.
 *
 * <p>{@code graphitron_} is deliberately out of scope too, and the reason is what the family is
 * rather than a concession. Its rows are a decode of what the transcription captured, produced by a
 * gatherer that reads no corpus and declares its reads on the sdl and catalog gatherers both, so
 * "does not vary with the catalog" is not a property it has or should have: a decode resolving a
 * directive's documented default against a primary key varies with the catalog by construction, and
 * that is the family doing its job. What still holds it in place is the ownership rule, which puts a
 * conclusion drawn about the schema in {@code intent_} rather than here. The historic defect this
 * gate was built for is unaffected: a synthesized {@code @key} appearing in the catalog arm was a row
 * in {@code graphql_type_directive}, and a transcription of a directive nobody wrote fails this gate
 * on the relation where it is actually wrong.
 *
 * <p>The fixture has to be able to fail. It is federation-linked and carries an inferred-node shape,
 * a {@code @table} type implementing {@code Node} over a table whose generated class publishes node
 * metadata, which is the exact pair the retired capture-time expansion read across. Before the move
 * the catalog arm wrote a synthesized {@code @key} into {@code graphql_type_directive} and its
 * decode, and the bare arm did not.
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
        var withCatalog = rowsOf(() -> CapturedStore.ofCatalog(tmp, FIXTURE,
            new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE)));
        var withoutCatalog = rowsOf(() -> CapturedStore.of(tmp, FIXTURE));

        assertThat(inScope())
            .as("the gate reads relations, so an empty scope would pass vacuously")
            .isNotEmpty();
        assertThat(withCatalog)
            .as("the two captures read one registry, so every SDL relation is compared")
            .containsOnlyKeys(withoutCatalog.keySet().toArray(String[]::new));

        var differing = new ArrayList<String>();
        withCatalog.forEach((relation, rows) -> {
            if (!rows.equals(withoutCatalog.get(relation))) {
                differing.add(relation);
            }
        });
        assertThat(differing)
            .as("a crawler whose rows about one corpus vary with another corpus's contents; move the "
                + "rule to a derivation over the captured facts of both rather than exempting it here")
            .isEmpty();
    }

    /**
     * A control on the differential: the catalog arm has to have captured a catalog, or the two
     * stores would agree because neither read one and the gate would pass for the wrong reason.
     */
    @Test
    @DisplayName("the catalog arm really captured a catalog")
    void theCatalogArmIsNotVacuous(@TempDir Path tmp) {
        try (var store = CapturedStore.ofCatalog(tmp, FIXTURE,
                new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE))) {
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

    /** Every declared relation whose name starts with one of the SDL families' prefixes. */
    private static List<Table<?>> inScope() {
        return Public.PUBLIC.getTables().stream()
            .filter(table -> {
                String name = table.getName().toLowerCase(Locale.ROOT);
                return SDL_FAMILIES.stream().anyMatch(name::startsWith);
            })
            .toList();
    }

    /**
     * A relation's rows as sorted rendered tuples, so the comparison is order-independent and a
     * failure names the rows rather than a count.
     */
    private static List<String> contentsOf(DSLContext dsl, Table<?> relation) {
        return dsl.select(relation.fields()).from(relation).fetch().stream()
            .map(record -> record.intoList().toString())
            .sorted()
            .toList();
    }
}
