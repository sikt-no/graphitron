package no.sikt.graphitron.rewrite.maven.watch;

import no.sikt.graphitron.model.sources.Observation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One watcher's half of the observation: which corpus it watches, what to tell about a change, and
 * where to put the path so a round can say what it was told.
 *
 * <p>A watcher knows exactly which file fired it and, before this existed, dropped that on the next
 * line: the resolved path and the event kind went out of scope and five stages downstream each
 * rediscovered the same answer by reading bytes. Every watch-side call here is against that.
 *
 * <p>The two sinks are deliberately not one. A mark is folded to the instance key of the row a
 * gatherer stamps, which for a coarse corpus is the root a file lives under, and it is what
 * correctness reads; the ring holds the path itself and is what a console line reads. Collapsing
 * them would make one of the two wrong.
 *
 * <p>Everything here runs on a watch thread, so nothing here touches the store.
 */
public record WatchedCorpus(Observation observation, String corpus, RecentChanges recent) {

    public WatchedCorpus {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(recent, "recent");
    }

    /**
     * A corpus nothing is watching, for a caller that has a watcher but no session behind it: every
     * call is a no-op and nothing is ever trusted, which is what a test-constructed watcher and the
     * rediscover escape hatch both want.
     */
    public static WatchedCorpus unobserved(String corpus) {
        return new WatchedCorpus(Observation.rediscovering(), corpus, RecentChanges.none());
    }

    /** Says a watcher is up for this corpus, so what a pass reads from here on establishes trust. */
    public void observing() {
        observation.observing(corpus);
    }

    /** Says {@code path} moved, before the round that will act on it is scheduled. */
    public void changed(Path path) {
        observation.mark(path);
        recent.record(corpus, path);
    }

    /**
     * Says this corpus dropped out of observation, which raises its floor: nothing read before now
     * is trusted, and trust rebuilds instance by instance as passes verify them again.
     */
    public void lost(String reason) {
        observation.lose(corpus, reason);
        recent.lost(corpus, reason);
    }
}
