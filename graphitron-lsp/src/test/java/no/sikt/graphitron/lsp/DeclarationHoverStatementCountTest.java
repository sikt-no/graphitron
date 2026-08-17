package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one declaration hover costs the store, counted rather than reasoned about. The
 * classification block for a field declaration is one statement, and stays one statement however
 * many claims stand at the coordinate.
 *
 * <p>This is an enforcer, not a benchmark: no timing, no fixture scale, nothing that could fail for
 * being slow. It exists because the shape it pins is invisible from any behavioural assertion. Every
 * hover test passed while the block cost a statement per claim on top of three, since a fan-out into
 * separate round trips returns exactly the same text as one statement does. A future reader adding a
 * fact to the block will reach for another query, which is the natural move and the one this test
 * refuses.
 *
 * <p>The type block has no case here on purpose. It still reads a statement per claim, and pinning
 * that number would read as sanctioning it; the class gains a case when the type block is recomposed
 * the way the field block was.
 *
 * <p>Every hover runs under an unavailable snapshot, so the description overlay never fires and the
 * count is the classification block's alone.
 */
class DeclarationHoverStatementCountTest {

    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    /**
     * One coordinate per shape the count must hold for: a plain column match, a coordinate two
     * directives both claim, a chain of repeated applications, and a field claimed by nothing that
     * still carries a join path and a round-trip rule.
     */
    private static final String SDL = """
        type Query {
            films: [Film] @service(service: {className: "%s", method: "makeFilmRecord"})
        }

        type Film @table(name: "film") {
            title: String
            rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            filmId: ID @nodeId(typeName: "Film") @service(service: {className: "com.example.FilmService", method: "id"})
            language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") {
            name: String
        }
        """.formatted(FIXTURE_SERVICE);

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL, StoreFixture.backingClasses());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aColumnMatchedFieldCostsOneStatement() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(statementsForHoverAt(file, 1, "    titl".length())).isEqualTo(1);
    }

    @Test
    void aCoordinateTwoDirectivesClaimStillCostsOneStatement() {
        // The count used to grow with the claims, which made a conflicted coordinate the most
        // expensive thing an author could hover, for facts every relation keys on the one coordinate.
        var file = file("""
            type Film @table(name: "film") {
                filmId: ID @nodeId(typeName: "Film") @service(service: {className: "com.example.FilmService", method: "id"})
            }
            """);
        var hover = hover(file, 1, "    filmI".length());
        assertThat(hover).isPresent();
        assertThat(hover.get()).contains("NODE_ID").contains("SERVICE");
        assertThat(statementsForHoverAt(file, 1, "    filmI".length())).isEqualTo(1);
    }

    @Test
    void aFieldNoClaimReachesCostsOneStatement() {
        // The claim-independent facts are read beside the claims rather than under them, so a
        // coordinate with no claim at all pays the same one statement and still answers.
        var file = file("""
            type Film @table(name: "film") {
                language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(statementsForHoverAt(file, 1, "    langua".length())).isEqualTo(1);
    }

    @Test
    void aDeclarationTheStoreKnowsNothingAboutCostsOneStatement() {
        // Absence is an answer, and it is the same statement: nothing here may fall back to probing
        // relation by relation to find out that none of them holds a row.
        var file = file("""
            type Unknown {
                mystery: String
            }
            """);
        var counted = new AtomicInteger();
        assertThat(hover(counting(counted), file, 1, "    myster".length())).isEmpty();
        assertThat(counted.get()).isEqualTo(1);
    }

    // ===== Helpers =====

    private static int statementsForHoverAt(FileSnapshot file, int line, int column) {
        var counted = new AtomicInteger();
        assertThat(hover(counting(counted), file, line, column)).isPresent();
        return counted.get();
    }

    /** The fixture's own store, seen through a handle that counts the statements it executes. */
    private static StoreHandle counting(AtomicInteger counted) {
        var configuration = store.handle().dsl().configuration()
            .derive(new DefaultExecuteListenerProvider(new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    counted.incrementAndGet();
                }
            }));
        return new StoreHandle(DSL.using(configuration), StoreFixture.GRAPH);
    }

    private static Optional<String> hover(FileSnapshot file, int line, int column) {
        return hover(store.handle(), file, line, column);
    }

    private static Optional<String> hover(StoreHandle handle, FileSnapshot file, int line, int column) {
        return DeclarationHovers.compute(
                file, Optional.of(handle), LspSchemaSnapshot.unavailable(), new Point(line, column))
            .map(h -> h.getContents().getRight().getValue());
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
