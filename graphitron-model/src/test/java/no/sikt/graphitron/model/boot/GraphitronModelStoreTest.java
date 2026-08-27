package no.sikt.graphitron.model.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep's call site, as opposed to the sweep itself: that {@link GraphitronModelStore#openAt}
 * runs it once per home per JVM, that it runs in the arms that fall back to an in-memory store, and
 * that the recency marker is written strictly after a successful open.
 *
 * <p>These belong beside the class rather than in a module that consumes it, being assertions about
 * what opening the store does rather than about what capture writes. The policy the sweep applies
 * is {@link StoreReaperTest}'s subject.
 *
 * <p>Every case here has to address the stamped directory the store keeps its file in, whose name is
 * the store's own private knowledge. {@link GraphitronModelStore#location()} is how: a store reports
 * where it landed after the fact, which is exactly the affordance a test needs and the one a second
 * opener must not have.
 */
class GraphitronModelStoreTest {

    /**
     * The guard is what stops a reactor build sweeping once per module. Its absence would not be
     * unsafe, every deletion race being caught, but the count and byte total would be split across
     * two reports, and the report is the feature's whole user surface on an ordinary build.
     */
    @Test
    @DisplayName("a second openAt of the same home in one JVM reaps nothing")
    void aSecondOpenOfTheSameHomeReapsNothing(@TempDir Path home) throws IOException {
        surplusStoreDirectory(home, "a", Instant.now().minusSeconds(300));
        surplusStoreDirectory(home, "b", Instant.now().minusSeconds(600));
        surplusStoreDirectory(home, "c", Instant.now().minusSeconds(900));

        try (var first = GraphitronModelStore.openAt(home)) {
            assertThat(first.reaped().directories())
                .as("the live stamp plus the two most recently used others are kept, so one goes")
                .isEqualTo(1);
        }
        try (var second = GraphitronModelStore.openAt(home)) {
            assertThat(second.reaped())
                .as("the second sweep of a home this JVM has already swept does not run")
                .isEqualTo(StoreReaper.Reaped.none());
        }
    }

    /**
     * A home whose live stamp cannot be used is precisely a home whose older stamps nobody is
     * looking at, so the fallback arms sweep too. The live segment is spared there by name rather
     * than by the lock probe, this run holding no lock on it.
     */
    @Test
    @DisplayName("an openAt that falls back to memory still reaps, and still spares the live segment")
    void aFallbackToMemoryStillReaps(@TempDir Path tmp) throws IOException {
        String liveSegment = liveSegment(tmp.resolve("probe"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path live = Files.createDirectories(home.resolve(liveSegment));
        // A file at the stamped path that H2 cannot open at all: the arm that falls back and leaves
        // the file strictly alone, which a hand-moved or hand-damaged store is the real cause of.
        Files.write(live.resolve("store.mv.db"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        // Three surplus stamps, so the retention has one to give up: the live directory occupies a
        // retained slot whether or not this run could open it.
        surplusStoreDirectory(home, "a", Instant.now().minusSeconds(300));
        surplusStoreDirectory(home, "b", Instant.now().minusSeconds(600));
        surplusStoreDirectory(home, "c", Instant.now().minusSeconds(900));

        try (var store = GraphitronModelStore.openAt(home)) {
            assertThat(store.location()).as("the damaged file was not this run's to use").isEmpty();
            assertThat(store.reaped().directories())
                .as("the surplus stamp past the retention went anyway").isEqualTo(1);
        }
        assertThat(home.resolve("c")).as("the oldest surplus stamp is the one released")
            .doesNotExist();
        assertThat(live.resolve("store.mv.db"))
            .as("the live stamp is spared by name, damaged or not")
            .exists();
    }

    @Test
    @DisplayName("a successful open leaves a marker; an open that falls back leaves none")
    void theMarkerIsWrittenAfterASuccessfulOpen(@TempDir Path tmp) throws IOException {
        Path good = Files.createDirectories(tmp.resolve("good"));
        try (var store = GraphitronModelStore.openAt(good)) {
            Path directory = store.location().orElseThrow();
            assertThat(directory.resolve("store.last-used"))
                .as("recency is a fact the store records, not one inferred from H2's file times")
                .exists();
            assertThat(Files.readString(directory.resolve("store.last-used")))
                .as("the text is for a person reading the cache directory")
                .isNotBlank();
        }

        String liveSegment = liveSegment(tmp.resolve("probe"));
        Path bad = Files.createDirectories(tmp.resolve("bad"));
        Path live = Files.createDirectories(bad.resolve(liveSegment));
        Files.write(live.resolve("store.mv.db"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        try (var store = GraphitronModelStore.openAt(bad)) {
            assertThat(store.location()).isEmpty();
        }
        assertThat(live.resolve("store.last-used"))
            .as("the marker is written strictly after a successful open, which candidacy rests on")
            .doesNotExist();
    }

    /**
     * The stamp segment this JVM resolves, learned the only way anything outside the store can learn
     * it: open one and ask where it landed. Every case that has to build a home the store will refuse
     * needs the name first.
     */
    private static String liveSegment(Path scratchHome) {
        try (var store = GraphitronModelStore.openAt(scratchHome)) {
            return store.location().orElseThrow().getFileName().toString();
        }
    }

    /** A stamped directory the sweep will recognise, older than the live one and not it. */
    private static void surplusStoreDirectory(Path home, String segment, Instant lastUsed)
        throws IOException {
        Path directory = Files.createDirectories(home.resolve(segment));
        Files.write(directory.resolve("store.mv.db"), new byte[64]);
        Path marker = directory.resolve("store.last-used");
        Files.writeString(marker, lastUsed.toString());
        Files.setLastModifiedTime(marker, FileTime.from(lastUsed));
    }
}
