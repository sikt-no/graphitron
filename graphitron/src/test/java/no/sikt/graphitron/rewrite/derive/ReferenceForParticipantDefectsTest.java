package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.derive.ReferenceForParticipantDefects;

/**
 * The whole-schema half of the {@code @referenceFor} {@code type:} check: a participant name that
 * matches nothing at <em>any</em> consumer of the input surface.
 *
 * <p>Per use site the classifier treats a non-matching name as inert, and it has to: an input type
 * may be consumed by two queries whose participant sets differ, and rejecting per use site would key
 * validity two grains finer than the fact while offering no remedy but forking the input type. That
 * inertness is what would swallow a typo, so this family exists to close it, and the closing has to
 * happen at the one altitude that sees every consumer at once.
 *
 * <p>Sibling of {@code NodeIdParticipantRoutePipelineTest}, where the per-use-site half stands: an
 * application naming a participant of one consumer and not another classifies clean at both.
 */
@PipelineTier
class ReferenceForParticipantDefectsTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    private static JooqCatalog jooq;

    @BeforeAll
    static void scanTheCatalog() {
        var ctx = TestConfiguration.testContext();
        jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }

    @TempDir
    Path tmp;

    /** The decode target and two participants of a multi-table union, the shape every arm extends. */
    private static final String STOCK =
        """
        interface Node { id: ID! }
        type Language implements Node @table(name: "language") @node { id: ID! @nodeId }
        type Film @table(name: "film") { title: String }
        type Inventory @table(name: "inventory") { inventoryId: ID! @field(name: "inventory_id") }
        union Stock = Film | Inventory
        """;

    @Test
    void aNameMatchingNoParticipantAnywhereIsRejectedNamingEveryConsumer() {
        var violations = detect(STOCK + """
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @referenceFor(type: "Fiml", path: [{key: "film_language_id_fkey"}])
            }
            type Query { stock(filter: StockFilter): [Stock!]! }
            """);

        assertThat(messages(violations)).singleElement().satisfies(m -> {
            assertThat(m).startsWith("Field 'StockFilter.languageId': ");
            assertThat(m).contains("@referenceFor names participant 'Fiml'");
            assertThat(m).contains("Query.stock (Film, Inventory)");
        });
    }

    @Test
    void aNameThatMatchesAtOneConsumerIsSilentEvenWhereItMatchesNoneAtAnother() {
        // StockFilter is consumed by two queries with different participant sets. The Inventory
        // application applies at one and is inert at the other, which is the reuse this family must
        // not punish; only a name matching nowhere is a typo.
        var violations = detect(STOCK + """
            type FilmTranslation @table(name: "film_translation") { filmId: ID! @field(name: "film_id") }
            union DubbedStock = Film | FilmTranslation
            input StockFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
                    @referenceFor(type: "Inventory", path: [{key: "inventory_film_id_fkey"}, {key: "film_language_id_fkey"}])
            }
            type Query {
                stock(filter: StockFilter): [Stock!]!
                dubbedStock(filter: StockFilter): [DubbedStock!]!
            }
            """);

        assertThat(messages(violations)).isEmpty();
    }

    @Test
    void anInputTypeConsumedOnlyBySingleTableQueriesIsPointedAtReference() {
        // There is no participant set anywhere, so an empty list of valid names would say nothing.
        // The author has not mistyped a participant; they have reached for the wrong directive.
        var violations = detect("""
            interface Node { id: ID! }
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId }
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @referenceFor(type: "Film", path: [{key: "film_language_id_fkey"}])
            }
            type Query { films(filter: FilmFilter): [Film!]! }
            """);

        assertThat(messages(violations)).singleElement().satisfies(m -> {
            assertThat(m).contains("every query consuming this input type returns a single table");
            assertThat(m).contains("Query.films");
            assertThat(m).contains("use @reference");
        });
    }

    @Test
    void anOutputFieldApplicationNeverEntersThisPopulation() {
        // An output field's participant set is its own named type, so the check is local and belongs
        // where the routes resolve. The exclusion is structural rather than a kind test: an
        // occurrence path's leaf is always an input object, so an object field matches no occurrence.
        var violations = detect("""
            interface Node { id: ID! }
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { title: String }
            type Inventory @table(name: "inventory") { inventoryId: ID! @field(name: "inventory_id") }
            union Stock = Film | Inventory
            type Language2 @table(name: "language") {
                stock: Stock @referenceFor(type: "Nonexistent", path: [{key: "film_language_id_fkey"}])
            }
            type Query { languages: [Language!]! }
            """);

        assertThat(messages(violations)).isEmpty();
    }

    @Test
    void theArgumentCoordinateChecksAgainstItsOwnFieldsParticipants() {
        var violations = detect(STOCK + """
            type Query {
                stock(
                    languageId: ID @nodeId(typeName: "Language")
                        @referenceFor(type: "Fiml", path: [{key: "film_language_id_fkey"}])
                ): [Stock!]!
            }
            """);

        assertThat(messages(violations)).singleElement().satisfies(m -> {
            assertThat(m).startsWith("Field 'Query.stock': argument 'languageId': ");
            assertThat(m).contains("@referenceFor names 'Fiml'");
            assertThat(m).contains("Valid participant names: Film, Inventory.");
        });
    }

    private List<ValidationError> detect(String sdl) {
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl, jooq)) {
            return ReferenceForParticipantDefects.detect(store.dsl(), GRAPH).violations();
        }
    }

    /** The violations' messages, the surface an author actually meets. */
    private static List<String> messages(List<ValidationError> violations) {
        return violations.stream().map(ValidationError::message).toList();
    }
}
