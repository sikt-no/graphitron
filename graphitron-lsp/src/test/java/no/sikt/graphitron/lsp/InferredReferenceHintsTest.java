package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inferred-directive arm's {@code @reference} pass, over a real capture. What it fills in is the
 * foreign key the generator discovers between the field's two endpoints where the author wrote a
 * {@code @reference} and no path, which the store answers from the catalog's own connectivity.
 *
 * <p>This was the last renderer reading a generator pass's projection, and moving it is what took the
 * schema snapshot out of the provider's signature. So the cases here run against the store alone, as
 * every other arm's do, and the pass answers in a session that has captured a schema and never built.
 *
 * <p>What the overlay renders is one element, because discovery is one hop: a path of several
 * elements is one an author states, and this pass fires only where nothing was stated. Half the cases
 * are silences, and each is a coordinate where the generator joins on no discovered key: two keys
 * connecting the endpoints, none connecting them, both endpoints one table, or a claim that means the
 * field is not fetched by joining at all. An overlay at any of them would name a join the generator
 * does not make.
 */
class InferredReferenceHintsTest {

    /**
     * One schema covering every shape the pass answers, each over a pair of tables the fixture
     * catalog connects the way the case needs: one foreign key from the parent's table, one on the
     * child's, two between the pair, none between the pair, and one table on both ends.
     */
    private static final String SDL = """
        type Query {
            customers: [Customer]
            films: [Film]
            categories: [Category]
        }

        type Customer @table(name: "customer") {
            address: Address @reference
            claimed: Address @reference @service(service: {className: "com.example.AddressService", method: "address"})
        }

        type Address @table(name: "address") {
            address_id: ID
        }

        type Film @table(name: "film") {
            content: Content @reference
            language: Language @reference
            actors: [Actor!]! @reference
            pinned: Language @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Content @table(name: "content") { content_id: ID }
        type Language @table(name: "language") { name: String }
        type Actor @table(name: "actor") { actor_id: ID }

        type Category @table(name: "category") {
            parent: Category @reference
        }
        """;

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aBareReferenceShowsTheKeyItsEndpointsAreConnectedBy() {
        var file = file("""
            type Customer @table(name: "customer") {
                address: Address @reference
            }
            """);
        assertThat(labels(file))
            .containsExactly("path: [{key: \"customer_address_id_fkey\"}]");
    }

    @Test
    void theKeyIsNamedWhicheverEndDeclaresIt() {
        // content declares the key against film, so the discovery faces the other way; what an
        // author writes into a {key:} element is the constraint's name either way.
        var file = file("""
            type Film @table(name: "film") {
                content: Content @reference
            }
            """);
        assertThat(labels(file))
            .containsExactly("path: [{key: \"content_film_id_fkey\"}]");
    }

    /**
     * The refusal, and the reason the overlay reads the discovery's arity rather than its first row.
     * {@code film} declares two foreign keys to {@code language}, so the generator rejects the
     * coordinate and joins on neither; naming one would tell the author a join was made that was not,
     * and naming both would put a path in the buffer that the grammar has no place for.
     */
    @Test
    void twoKeysBetweenTheEndpointsRenderNothing() {
        var file = file("""
            type Film @table(name: "film") {
                language: Language @reference
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    @Test
    void endpointsNoSingleKeyConnectsRenderNothing() {
        // film and actor meet at film_actor, and discovery never searches past one hop, so this is
        // a path the author has to write and the overlay has nothing to offer.
        var file = file("""
            type Film @table(name: "film") {
                actors: [Actor!]! @reference
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    @Test
    void aSelfReferencingFieldRendersNothing() {
        // Both endpoints are one table, so a key connecting them does not say which way the field
        // navigates; the generator asks for the key explicitly here rather than guessing.
        var file = file("""
            type Category @table(name: "category") {
                parent: Category @reference
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    @Test
    void anAuthoredPathLeavesNothingToFillIn() {
        var file = file("""
            type Film @table(name: "film") {
                pinned: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    /**
     * A claimed field is fetched from the service rather than by joining, so what connects the two
     * tables is beside the point at the coordinate. The silence is the discovery relation's, inherited
     * from the navigation it reads its arriving endpoint through, rather than a check in the renderer.
     */
    @Test
    void anAuthoredClaimSilencesTheOverlayOverItsOwnKey() {
        var file = file("""
            type Customer @table(name: "customer") {
                claimed: Address @reference @service(service: {className: "com.example.AddressService", method: "address"})
            }
            """);
        assertThat(labels(file)).isEmpty();
    }

    @Test
    void anExtensionSiteResolvesThroughTheBaseDeclarationsBinding() {
        // The departing table is the type's binding rather than the declaration's, so a field of an
        // extension discovers from the same endpoint a field of the base declaration does.
        var file = file("""
            extend type Customer {
                address: Address @reference
            }
            """);
        assertThat(labels(file)).contains("path: [{key: \"customer_address_id_fkey\"}]");
    }

    @Test
    void noOverlaysWithoutAStore() {
        var file = file("""
            type Customer @table(name: "customer") {
                address: Address @reference
            }
            """);
        assertThat(InlayHints.compute(config(), file, Optional.empty(), fullRange())).isEmpty();
    }

    // ===== Helpers =====

    private static List<String> labels(FileSnapshot file) {
        return InlayHints.compute(config(), file, Optional.of(store.handle()), fullRange())
            .stream().map(InferredReferenceHintsTest::labelOf).toList();
    }

    private static String labelOf(InlayHint hint) {
        var either = hint.getLabel();
        return either.isLeft() ? either.getLeft() : either.getRight().toString();
    }

    /** The inferred-directive toggle alone, so no other arm's label can be mistaken for an overlay. */
    private static InlayHintConfig config() {
        return new InlayHintConfig(true, false, false, false);
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    private static Range fullRange() {
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }
}
