package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.STORE_STAMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sweep's call site, as opposed to the sweep itself: that {@link GraphitronModelStore#openAt}
 * runs it once per home per JVM, that it runs in the arms that fall back to an in-memory store, and
 * that the recency marker is written strictly after a successful open. And the stamp a booted store
 * carries, which names the DDL it ran, and the file system it opens, which an interrupted read
 * leaves answering.
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
     * looking at, so the sweep runs whether or not the open goes on to succeed: it happens before
     * the open, which is what makes it independent of the outcome. The live segment is spared by
     * name rather than by the lock probe, this run holding no lock on it.
     *
     * <p>Asserted on the filesystem rather than on the store's own report, because a failing open
     * hands back no store to report through. That is the stronger reading anyway, the effect being
     * what a person finds in their cache home.
     */
    @Test
    @DisplayName("an openAt that fails still reaps, and still spares the live segment")
    void anOpenThatFailsStillReaps(@TempDir Path tmp) throws IOException {
        String liveSegment = liveSegment(tmp.resolve("probe"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path live = Files.createDirectories(home.resolve(liveSegment));
        // A file at the stamped path that H2 cannot open at all: the arm that refuses and leaves
        // the file strictly alone, which a hand-moved or hand-damaged store is the real cause of.
        Files.write(live.resolve("store.mv.db"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        // Three surplus stamps, so the retention has one to give up: the live directory occupies a
        // retained slot whether or not this run could open it.
        surplusStoreDirectory(home, "a", Instant.now().minusSeconds(300));
        surplusStoreDirectory(home, "b", Instant.now().minusSeconds(600));
        surplusStoreDirectory(home, "c", Instant.now().minusSeconds(900));

        assertThatThrownBy(() -> GraphitronModelStore.openAt(home))
            .as("the damaged file was not this run's to use, and it is told to say so")
            .isInstanceOf(StoreUnavailableException.class);
        assertThat(home.resolve("c"))
            .as("the oldest surplus stamp went anyway, the sweep running ahead of the open")
            .doesNotExist();
        assertThat(live.resolve("store.mv.db"))
            .as("the live stamp is spared by name, damaged or not")
            .exists();
    }

    @Test
    @DisplayName("a successful open leaves a marker; an open that fails leaves none")
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

        assertThatThrownBy(() -> GraphitronModelStore.openAt(bad))
            .isInstanceOf(StoreUnavailableException.class);
        assertThat(live.resolve("store.last-used"))
            .as("the marker is written strictly after a successful open, which candidacy rests on")
            .doesNotExist();
    }

    /**
     * The store's stamp names the DDL it was built from. Equality against the resource rather than
     * against a recorded constant, because the whole job of the stamp is to make a persisted file
     * unreadable the moment the schema moves: a stamp computed from anything but the DDL this store
     * ran would let an older file survive an edit and answer with relations that no longer mean what
     * they say.
     */
    @Test
    @DisplayName("the store stamp names the DDL the store was built from")
    void theStampNamesTheSchema() throws Exception {
        byte[] ddl;
        try (var in = GraphitronModelStore.class.getResourceAsStream(GraphitronModelStore.DDL_RESOURCE)) {
            ddl = in.readAllBytes();
        }
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ddl));

        try (var store = GraphitronModelStore.open()) {
            var stamps = store.dsl().selectFrom(STORE_STAMP).fetch();
            assertThat(stamps).as("at most one row, and a booted store has it").hasSize(1);
            assertThat(stamps.getFirst().getDdlHash()).isEqualTo(expected);
        }
    }

    /**
     * An interrupt that reaches a thread inside the store's file I/O closes a plain
     * {@code FileChannel} for every user of the store, which then fails until it is reopened. The
     * store is reopened cold and read on a thread whose interrupt flag is set: the read answers, and
     * so does the next one. The directives are what is read because a fresh open leaves them on
     * disk, where a smaller relation is already in H2's cache and never reaches the file; they are
     * also the relation a live session was found failing on.
     */
    @Test
    @DisplayName("a read interrupted inside the store's file leaves the store answering")
    void anInterruptedReadLeavesTheStoreAnswering(@TempDir Path tmp) {
        Path home = tmp.resolve("home");
        Path sources = tmp.resolve("sources");
        CapturedStore.writeSource(sources, "type Query { films: [Film!]! }\ntype Film { title: String }\n");
        List<String> captured;
        try (var store = GraphitronModelStore.openAt(home)) {
            CapturedStore.capture(store.dsl(), CapturedStore.graph(sources), CapturedStore.corpusOf(sources), null);
            captured = directiveNames(store);
        }

        try (var store = GraphitronModelStore.openAt(home)) {
            List<String> interrupted;
            Thread.currentThread().interrupt();
            try {
                interrupted = directiveNames(store);
            } finally {
                Thread.interrupted();
            }
            assertThat(interrupted).as("the interrupted read").isEqualTo(captured);
            assertThat(directiveNames(store)).as("the read after it").isEqualTo(captured);
        }
    }

    /** The rows themselves rather than a count, which H2 answers without reading the file. */
    private static List<String> directiveNames(GraphitronModelStore store) {
        return store.dsl().select(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME).from(GRAPHQL_DIRECTIVE)
            .orderBy(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME).fetch(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME);
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

    // What a compaction says about itself, in each of the ways it can end.

    private static final Path COMPACTED_HOME = Path.of("/store");
    private static final String REFUSED = "50000 General error: AssertionError";

    @Test
    @DisplayName("a compaction reports its cost")
    void aCompactionReportsItsCost() {
        assertThat(new GraphitronModelStore.Compaction(12, 4 * 1024 * 1024, 1024 * 1024, null)
                .report(COMPACTED_HOME))
            .hasValue("Compacted the fact store under /store in 12 ms: 4 MB -> 1 MB");
    }

    @Test
    @DisplayName("a refused compaction names the refusal")
    void aRefusalIsNamed() {
        assertThat(new GraphitronModelStore.Compaction(12, 4 * 1024 * 1024, 5 * 1024 * 1024, REFUSED)
                .report(COMPACTED_HOME))
            .hasValue("Could not compact the fact store under /store: " + REFUSED);
    }

    @Test
    @DisplayName("a compaction whose sizes could not be read reports nothing, unless it was refused")
    void unreadableSizesReportOnlyARefusal() {
        assertThat(new GraphitronModelStore.Compaction(12, -1, -1, null).report(COMPACTED_HOME))
            .isEmpty();
        assertThat(new GraphitronModelStore.Compaction(12, -1, -1, REFUSED).report(COMPACTED_HOME))
            .hasValue("Could not compact the fact store under /store: " + REFUSED);
    }
}
