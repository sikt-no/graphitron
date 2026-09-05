package no.sikt.graphitron.model.sources;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The comparison, pinned from both sides. Every case here is about what {@code trusts} answers, and
 * the ones that matter are the ones where the answer has to be "no": a wrong "no" costs a pass, and
 * a wrong "yes" is a reader answering from rows that describe content nobody holds any more.
 *
 * <p>Instants are constructed rather than sampled wherever that works. A case about a floor takes
 * one a minute away and stays deterministic; a case about a mark cannot, needing a read that sits
 * between the watcher starting and the file moving, so {@link #between()} reads the clock inside a
 * gap wide enough to see and says why. The two cases that are <em>about</em> a tie read the instant
 * the observation itself holds and compare against exactly it.
 */
class ObservationTest {

    /**
     * One store for the class, not one per case. Nothing here writes to it: the only read is the
     * roster {@link Observation#register} checks a corpus against, and every case builds its own
     * observation over the same handle. A store per case would be eleven boots for one query.
     */
    @RegisterExtension
    static final FactStores.ClassStore STORE = FactStores.perClass();

    private static final String CORPUS = "java-source";

    private static LocalDateTime longBefore() {
        return LocalDateTime.now().minusMinutes(1);
    }

    private static LocalDateTime longAfter() {
        return LocalDateTime.now().plusMinutes(1);
    }

    /**
     * An instant strictly between the event before this call and the event after it.
     *
     * <p>The sleeps are the point rather than a wart. A case about a floor can construct an instant
     * a minute away and stay deterministic, but a case about a <em>mark</em> cannot: it needs a
     * read that sits after the watcher started and before the file moved, and those two events are
     * microseconds apart in a test. Reading the clock inside a gap wide enough to see is the only
     * way to get one without a seam for injecting time, and a case that reached for a minute-away
     * instant here would pass on the floor and never exercise the mark at all.
     */
    private static LocalDateTime between() throws InterruptedException {
        Thread.sleep(5);
        var at = LocalDateTime.now();
        Thread.sleep(5);
        return at;
    }

    private static Observation over(Path root) {
        var observation = new Observation(STORE.handle().dsl());
        observation.register(CORPUS, List.of(root), Path::toString);
        return observation;
    }

    private static String file(Path root, String name) {
        return root.resolve(name).toAbsolutePath().normalize().toString();
    }

    @Test
    @DisplayName("a cold session trusts nothing a previous session read")
    void aColdSessionTrustsNothing(@TempDir Path root) {
        // The read a previous session recorded, which this session's floor outranks by construction.
        var previousSession = longBefore();
        {
            var observation = over(root);
            observation.observing(CORPUS);

            assertThat(observation.trusts(CORPUS, file(root, "Widgets.java"), previousSession))
                .as("a read taken before this process began watching says nothing about now")
                .isFalse();
        }
    }

    @Test
    @DisplayName("a registered corpus nothing is watching yet trusts nothing, however recent")
    void registrationAloneEstablishesNoFloor(@TempDir Path root) {
        {
            var observation = over(root);
            String widgets = file(root, "Widgets.java");

            assertThat(observation.trusts(CORPUS, widgets, longAfter()))
                .as("declaring a corpus is not watching it, so a read under no watcher"
                    + " establishes nothing")
                .isFalse();

            observation.observing(CORPUS);
            assertThat(observation.trusts(CORPUS, widgets, longAfter()))
                .as("and trust begins exactly where watching does")
                .isTrue();
        }
    }

    @Test
    @DisplayName("an instance read under the watcher and unmarked is trusted")
    void anUnmarkedInstanceReadAboveTheFloorIsTrusted(@TempDir Path root) {
        {
            var observation = over(root);
            observation.observing(CORPUS);

            assertThat(observation.trusts(CORPUS, file(root, "Widgets.java"), longAfter())).isTrue();
        }
    }

    @Test
    @DisplayName("a mark after the read distrusts, and a mark before it does not")
    void marksAreNotSticky(@TempDir Path root) throws InterruptedException {
        {
            var observation = over(root);
            observation.observing(CORPUS);
            Path widgets = root.resolve("Widgets.java");
            var readBeforeTheMove = between();
            assertThat(observation.trusts(CORPUS, widgets.toString(), readBeforeTheMove))
                .as("under a watcher and unmoved, which is the state a mark has to change")
                .isTrue();

            observation.mark(widgets);

            assertThat(observation.trusts(CORPUS, widgets.toString(), readBeforeTheMove))
                .as("the file moved after the store read it, so the rows describe older content")
                .isFalse();
            assertThat(observation.trusts(CORPUS, widgets.toString(), between()))
                .as("a read taken after the move describes the moved content, so the mark is spent;"
                    + " a mark that stayed sticky would leave a corpus unable to recover")
                .isTrue();
        }
    }

    @Test
    @DisplayName("equal instants distrust, on the floor and on a mark alike")
    void tiesResolveToDistrust(@TempDir Path root) {
        {
            var observation = over(root);
            observation.observing(CORPUS);
            Path widgets = root.resolve("Widgets.java");

            assertThat(observation.trusts(CORPUS, widgets.toString(), observation.floorOf(CORPUS)))
                .as("a read the watcher's own start ties with is a read that may have begun first")
                .isFalse();

            observation.mark(widgets);
            var mark = observation.markOf(CORPUS, widgets.toString());
            assertThat(observation.trusts(CORPUS, widgets.toString(), mark))
                .as("and the same on a mark: two events inside one clock tick have no order")
                .isFalse();
        }
    }

    @Test
    @DisplayName("losing a corpus distrusts what was read before it and nothing read after")
    void losingACorpusRaisesTheFloor(@TempDir Path root) throws InterruptedException {
        {
            var observation = over(root);
            observation.observing(CORPUS);
            String widgets = file(root, "Widgets.java");
            var readBeforeTheLoss = between();
            assertThat(observation.trusts(CORPUS, widgets, readBeforeTheLoss)).isTrue();

            observation.lose(CORPUS, "OVERFLOW");

            assertThat(observation.trusts(CORPUS, widgets, readBeforeTheLoss))
                .as("an overflow is the corpus going cold, not a third state")
                .isFalse();
            assertThat(observation.trusts(CORPUS, widgets, longAfter()))
                .as("and trust rebuilds instance by instance as passes verify them again,"
                    + " so a loss costs one pass rather than the session")
                .isTrue();
            assertThat(observation.lossReason(CORPUS))
                .as("the reason is carried for a console line, never switched on")
                .isEqualTo("OVERFLOW");
        }
    }

    @Test
    @DisplayName("the read window: a change during the read is distrusted afterwards")
    void aChangeDuringTheReadIsDistrusted(@TempDir Path root) {
        {
            var observation = over(root);
            observation.observing(CORPUS);
            Path widgets = root.resolve("Widgets.java");

            // The pass's own order, which is the subject: the instant first, then the read, and the
            // save landing between the two. A read_at stamped when the row was written instead
            // would sit after this mark and the stale rows would read as current.
            var readAt = observation.pass(CORPUS);
            observation.mark(widgets);

            assertThat(observation.trusts(CORPUS, widgets.toString(), readAt))
                .as("the instant precedes the read it dates, so a change inside that window is"
                    + " later than it and the instance is read again")
                .isFalse();
        }
    }

    @Test
    @DisplayName("a corpus no crawler declares is refused")
    void anUndeclaredCorpusIsRefused(@TempDir Path root) {
        {
            var observation = new Observation(STORE.handle().dsl());

            assertThatThrownBy(() ->
                observation.register("not-a-corpus", List.of(root), Path::toString))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("meta_gatherer_corpus");
        }
    }

    @Test
    @DisplayName("an instance outside the registered scope is never trusted")
    void anInstanceOutsideTheScopeIsNeverTrusted(@TempDir Path root) {
        {
            var observation = over(root.resolve("watched"));
            observation.observing(CORPUS);

            assertThat(observation.trusts(
                CORPUS, file(root.resolve("elsewhere"), "Widgets.java"), longAfter()))
                .as("nobody claimed to be watching where it lives, so nothing vouches for it")
                .isFalse();
        }
    }

    @Test
    @DisplayName("a root-grain fold marks the root, and a thousand files under it cost one entry")
    void aCoarseFoldMarksItsRoot(@TempDir Path root) throws InterruptedException {
        {
            var observation = new Observation(STORE.handle().dsl());
            // The classpath corpus's shape: an instance is the entry, so every class beneath it
            // folds to the same key and one mark covers the lot.
            observation.register("classpath", List.of(root), path -> root.toString());
            observation.observing("classpath");
            var readBeforeTheBuild = between();

            for (int i = 0; i < 1_000; i++) {
                observation.mark(root.resolve("pkg").resolve("Class" + i + ".class"));
            }

            assertThat(observation.markCount())
                .as("a coarse fold's cost is its instances, not its events")
                .isEqualTo(1);
            assertThat(observation.trusts("classpath", root.toString(), readBeforeTheBuild))
                .as("and the mark lands on the root, which is the key the store partitions by,"
                    + " so one class file moving distrusts the entry that holds it")
                .isFalse();
        }
    }

    @Test
    @DisplayName("an observation that rediscovers everything trusts nothing and needs no store")
    void theEscapeHatchTrustsNothing(@TempDir Path root) {
        var observation = Observation.rediscovering();
        observation.register(CORPUS, List.of(root), Path::toString);
        observation.observing(CORPUS);
        observation.mark(root.resolve("Widgets.java"));

        assertThat(observation.trusts(CORPUS, file(root, "Widgets.java"), longAfter()))
            .as("every answer becomes read it, which is what the loop did before the mechanism")
            .isFalse();
        assertThat(observation.observesAnything()).isFalse();
    }
}
