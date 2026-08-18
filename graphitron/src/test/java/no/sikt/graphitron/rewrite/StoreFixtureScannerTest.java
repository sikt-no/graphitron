package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store-fixture guard's own recogniser, over sources written here rather than over the tree.
 *
 * <p>A guard whose passing state is the only state ever observed is a guard nobody knows is wired
 * up, and the tree is green by construction: every real site is either a harness or a declared
 * exemption. So both directions are pinned against synthetic sources, the firing direction and the
 * sparing one, and the same for the staleness check that keeps the declarations honest.
 */
@UnitTier
class StoreFixtureScannerTest {

    private static final Path FILE = Path.of("SomeTest.java");

    @Test
    @DisplayName("a test that opens a store of its own is found, at the line that opened it")
    void aSourceStandingAStoreUpIsFound() {
        String source = """
            class SomeTest {
                @Test
                void reads() {
                    try (var store = GraphitronModelStore.open()) {
                        assertThat(store.dsl()).isNotNull();
                    }
                }
            }
            """;

        assertThat(StoreFixtureScanner.scanSource(FILE, source))
            .singleElement()
            .satisfies(finding -> assertThat(finding.line())
                .as("the site, so an author reading the failure can go straight to it")
                .isEqualTo(4));
    }

    @Test
    @DisplayName("a test taking its store from a harness never spells the type, so it is spared")
    void aSourceTakingItsStoreFromAHarnessIsNotFound() {
        String source = """
            class SomeTest {
                @Test
                void reads() {
                    try (var store = FactStores.inMemory()) {
                        assertThat(store.dsl()).isNotNull();
                    }
                    try (var captured = CapturedStore.of(tmp, SDL)) {
                        assertThat(captured.dsl()).isNotNull();
                    }
                }
            }
            """;

        assertThat(StoreFixtureScanner.scanSource(FILE, source))
            .as("var plus a named factory is the whole point: the caller has no reason to name the type")
            .isEmpty();
    }

    @Test
    @DisplayName("naming the type in prose or in a string is not opening one")
    void aMentionOutsideCodeIsNotFound() {
        String source = """
            /** Wraps a {@link GraphitronModelStore} so a caller does not have to open one. */
            class SomeTest {
                // GraphitronModelStore is what this delegates to.
                private static final List<String> EXPECTED = List.of("GraphitronModelStore");
            }
            """;

        assertThat(StoreFixtureScanner.scanSource(FILE, source))
            .as("a dependency-set assertion listing the type, and javadoc explaining what a harness "
                + "wraps, have opened nothing; failing on those reads as a spelling rule")
            .isEmpty();
    }

    @Test
    @DisplayName("a longer name merely containing the token is a different type")
    void aNameContainingTheTokenIsNotFound() {
        String source = """
            class SomeTest {
                private GraphitronModelStoreFixture fixture;
                private MyGraphitronModelStore mine;
            }
            """;

        assertThat(StoreFixtureScanner.scanSource(FILE, source))
            .as("the match is whole-identifier, so a successor type embedding the old word is safe")
            .isEmpty();
    }

    @Test
    @DisplayName("an entry whose file is gone, and one that adopted a harness, are both stale")
    void aDeclarationThatStoppedDescribingSomethingIsReported(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("module/src/test/java"));
        Files.writeString(root.resolve("module/src/test/java/Adopted.java"), """
            class Adopted {
                void reads() {
                    try (var store = FactStores.inMemory()) {}
                }
            }
            """);
        Files.writeString(root.resolve("module/src/test/java/StillOpens.java"), """
            class StillOpens {
                void reads() {
                    try (var store = GraphitronModelStore.open()) {}
                }
            }
            """);

        assertThat(StoreFixtureScanner.stale(root, List.of(
                "module/src/test/java/Adopted.java",
                "module/src/test/java/Deleted.java",
                "module/src/test/java/StillOpens.java")))
            .as("an exemption is spent the moment its class stops needing it, and the build says so "
                + "rather than leaving a permission nobody rereads")
            .extracting(StoreFixtureScanner.Stale::path)
            .containsExactly("module/src/test/java/Adopted.java", "module/src/test/java/Deleted.java");
    }
}
