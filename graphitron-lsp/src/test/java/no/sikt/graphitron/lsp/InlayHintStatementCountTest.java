package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one inlay-hint request costs the store, counted rather than reasoned about: one statement for
 * the whole visible region, whatever it contains and however many overlays it renders.
 *
 * <p>This is an enforcer, not a benchmark: no timing, no fixture scale, nothing that could fail for
 * being slow. It exists because the shape it pins is invisible from any behavioural assertion. Every
 * inlay test passed while the region's overlays cost a statement per site, since resolving forty
 * omitted arguments in forty round trips renders exactly what resolving them in one does. The grain
 * matters here more than at most surfaces: an inlay request arrives per visible region and an editor
 * reissues it on every scroll, so a count that tracks the region is paid at the cadence of the
 * cursor rather than of a build.
 *
 * <p>The region is the unit of work, and it is the only grain this surface has: unlike a
 * recalculation, which spans the files a capture touched, an inlay request is one file's window. So
 * the whole assertion is "one", and {@link #theCountDoesNotTrackTheRegionsSize} is what says the one
 * is not an accident of a small fixture.
 */
class InlayHintStatementCountTest {

    private static final String SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    /**
     * Every arm with something to render in one region: a table-bound type whose {@code @field} sites
     * omit the name, a type a producer's return backs whose sites resolve against class members
     * instead, a type bound to a table but carrying no {@code @table} at all, and claims and
     * round-trip rules over all of them.
     */
    private static final String SDL = """
        type Query {
            films: [Film]
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
        }

        type FilmCard {
            title: String @field
        }

        type Film @table {
            title: String @field
            rating: String @field
            content: Content @reference
            language: Language @splitQuery @reference
        }

        type Content @table(name: "content") {
            content_id: ID
        }

        type Language @table(name: "language") {
            name: String @field
        }
        """.formatted(SERVICE);

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL.replace("@table\n", "@table(name: \"film\")\n"),
            StoreFixture.backingClasses());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aRegionSpanningEveryArmCostsOneStatement() {
        assertThat(statementsFor(SDL)).isEqualTo(1);
    }

    @Test
    void anOmittedFieldNameCostsOneStatement() {
        assertThat(statementsFor("""
            type Film @table(name: "film") {
                title: String @field
            }
            """)).isEqualTo(1);
    }

    @Test
    void anOmittedFieldNameOnAClassBackedTypeCostsOneStatement() {
        assertThat(statementsFor("""
            type FilmCard {
                title: String @field
            }
            """)).isEqualTo(1);
    }

    @Test
    void anOmittedReferencePathCostsOneStatement() {
        var source = """
            type Film @table(name: "film") {
                content: Content @reference
            }
            """;
        // Asserted to render before the count is taken, so the case cannot pass on an arm that
        // answered nothing: a renderer reading no relation at all would also cost one statement.
        assertThat(hints(store.handle(), source))
            .extracting(hint -> hint.getLabel().getLeft())
            .contains("path: [{key: \"content_film_id_fkey\"}]");
        assertThat(statementsFor(source)).isEqualTo(1);
    }

    @Test
    void theCountDoesNotTrackTheRegionsSize() {
        int ten = statementsFor(fields(10));
        int forty = statementsFor(fields(40));
        assertThat(ten).isEqualTo(1);
        assertThat(forty).isEqualTo(ten);
    }

    @Test
    void aRegionWithNoDeclarationInItCostsNoStatement() {
        // Nothing to ask is not one statement returning nothing. A window holding no declaration and no
        // directive an overlay reads asks the store nothing at all, which is what an author scrolled to
        // the top of a file sees. A scalar declaration is not this case: no claim can name one and no
        // class can back one, but the arm may annotate it, so the question is honestly asked.
        assertThat(statementsFor("""
            schema {
                query: Query
            }
            """)).isZero();
    }

    @Test
    void everyToggleOffCostsNoStatement() {
        var counted = new AtomicInteger();
        var file = file(SDL);
        assertThat(InlayHints.compute(InlayHintConfig.defaults(), file,
            Optional.of(counting(counted)), fullRange(file)))
            .isEmpty();
        assertThat(counted.get()).isZero();
    }

    // ===== Helpers =====

    /** A type with {@code count} fields whose {@code @field} omits the name the store can fill in. */
    private static String fields(int count) {
        var sb = new StringBuilder("type Film @table(name: \"film\") {\n");
        for (int i = 0; i < count; i++) {
            sb.append("    title").append(i).append(": String @field\n");
        }
        return sb.append("}\n").toString();
    }

    private static int statementsFor(String source) {
        var counted = new AtomicInteger();
        assertThat(hints(counting(counted), source)).isNotNull();
        return counted.get();
    }

    private static List<InlayHint> hints(StoreHandle handle, String source) {
        var file = file(source);
        return InlayHints.compute(all(), file, Optional.of(handle), fullRange(file));
    }

    private static InlayHintConfig all() {
        return new InlayHintConfig(true, true, true, true);
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

    private static Range fullRange(FileSnapshot file) {
        var end = file.tree().getRootNode().getEndPoint();
        return new Range(new Position(0, 0), new Position(end.row(), end.column()));
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
