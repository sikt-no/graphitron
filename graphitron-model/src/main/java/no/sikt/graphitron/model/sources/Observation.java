package no.sikt.graphitron.model.sources;

import org.jooq.DSLContext;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static no.sikt.graphitron.model.Tables.META_GATHERER_CORPUS;

/**
 * What one process is watching, and what it has seen move: the session half of the two timestamps
 * a pass compares before deciding whether it has to read an input's bytes again.
 *
 * <p>The store holds the other half. Each relation that partitions by input carries a
 * {@code read_at} beside its content stamp, taken before the content was read and written for
 * every instance a pass verified. This type holds a <em>floor</em> per corpus, the instant this
 * process began watching it, and a <em>mark</em> per instance, the instant a watcher saw it move.
 * The rule is one comparison, and everything else here follows from it:
 *
 * <blockquote>An instance is trusted without re-reading its bytes exactly when its {@code read_at}
 * is above its corpus's floor and above every mark against it.</blockquote>
 *
 * <p>Comparisons are strict, so two events inside one clock tick resolve to distrust. Every answer
 * this type can get wrong is wrong in the direction of reading, which is the direction that costs
 * a pass rather than a wrong generated file: a gatherer under an observation can only ever be told
 * to do <em>less</em> than it does today, never something different.
 *
 * <p>Three consequences are worth stating, because they are what make the mechanism small.
 * Stale is the initial state: a session's floors are later than every {@code read_at} a previous
 * session wrote, so a cold session trusts nothing and needs no initialisation, and a workspace
 * edited while the loop was down is re-read. "I cannot say what moved" is not a third state but a
 * corpus dropping out of observation, which {@link #lose} expresses by raising its floor, so an
 * overflow costs one pass rather than the rest of the session. And trust is only ever established
 * under a running watcher, because only a pass that began after {@link #observing} can write a
 * {@code read_at} above the floor: a startup wired in the wrong order loses the saving and never
 * the soundness.
 *
 * <p>Declaring a corpus and beginning to watch it are two events. {@link #register} is the first
 * and establishes no floor; {@link #observing} is the second and does. Between them the corpus is
 * as good as cold, which is what keeps a pass that runs before its watcher is up honest.
 *
 * <p>Nothing here touches the store except {@link #register}, which reads the roster once to
 * refuse a corpus no crawler declares. In particular {@link #mark} is a map write on the watch
 * thread: there is no transaction to block on the round's, no second connection to open, and no
 * error path in which a lost mark leaves an instance reading current for the rest of the session.
 */
public final class Observation {

    /**
     * A path folded to the key of the row a gatherer stamps.
     *
     * <p>The fold belongs to the gatherer rather than to the watcher, because the instance key is
     * the key of the row the gatherer writes, and one corpus can feed readers wanting different
     * grains: the {@code classpath} corpus feeds a class census at root grain and a per-file
     * recompile invalidation at file grain, off the same events.
     */
    @FunctionalInterface
    public interface Fold extends Function<Path, String> { }

    /** What {@link #register} was told about one corpus: where it lives and how it folds. */
    private record Registration(List<Path> scope, Fold fold) { }

    private final DSLContext dsl;
    private final boolean disabled;
    private final Map<String, Registration> registered = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> floors = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> marks = new ConcurrentHashMap<>();
    private final Map<String, String> losses = new ConcurrentHashMap<>();

    /**
     * An observation over {@code dsl}'s roster. The handle is read by {@link #register} alone, and
     * only to refuse a corpus the store does not declare a crawler for.
     */
    public Observation(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.disabled = false;
    }

    private Observation() {
        this.dsl = null;
        this.disabled = true;
    }

    /**
     * An observation that establishes nothing: registration and {@link #observing} are no-ops and
     * {@link #trusts} answers false for everything, so every pass rediscovers what changed exactly
     * as it did before this mechanism existed.
     *
     * <p>This is the escape hatch's implementation, and it is deliberately the same code path as a
     * corpus nobody has begun watching rather than a second mode: filesystem watchers do lie, and a
     * developer on a bind mount or a network share where events are under-reported gets a working
     * session back by turning every answer here into "read it".
     */
    public static Observation rediscovering() {
        return new Observation();
    }

    /**
     * Declares that {@code corpus} lives under {@code scope} and folds to instances by
     * {@code fold}, and validates it against the store's roster of what each crawler reads.
     *
     * <p>Establishes no floor. Nothing is trusted in a corpus until {@link #observing} says a
     * watcher is up for it, so a gatherer may register as early as it likes, which is normally
     * while a session is still starting.
     *
     * <p><strong>The law the fold has to satisfy</strong>, because a mechanism this small invites a
     * later simplification that does not know the precondition is load-bearing:
     *
     * <blockquote>A mark at grain G is safe exactly when the consumer's unit of work is at grain G
     * or coarser.</blockquote>
     *
     * <p>The reason is dropped events. If the watcher loses the event for file {@code C} but
     * delivers {@code D}, a consumer whose unit is the whole containing instance re-reads it and
     * picks {@code C} up, so the loss heals; a consumer whose unit is one file re-reads {@code D}
     * alone and {@code C} stays stale until the session restarts. A fold coarser than its consumer
     * is therefore always sound and merely wasteful, and a fold finer than its consumer is a defect
     * this class cannot detect.
     *
     * @throws IllegalArgumentException if no gatherer declares {@code corpus}
     */
    public void register(String corpus, List<Path> scope, Fold fold) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(fold, "fold");
        if (disabled) {
            return;
        }
        // The roster is read once per corpus, not once per call: a gatherer re-declares its scope
        // on every pass (its roots are the walk's), and the store's answer cannot change under a
        // running session.
        if (!registered.containsKey(corpus) && !declared(corpus)) {
            throw new IllegalArgumentException(
                "no gatherer declares the corpus " + corpus + "; only a corpus some crawler reads"
                    + " can be observed, meta_gatherer_corpus being where that is declared");
        }
        registered.put(corpus, new Registration(normalised(scope), fold));
    }

    /**
     * Raises {@code corpus}'s floor to now: a watcher is up for it, so from here on a pass that
     * reads an instance of it establishes something.
     *
     * <p>Called by the watcher once its own registrations are in place, never by whoever declared
     * the corpus. An instance read before this instant was read while nothing was watching, and the
     * gap between two sessions is a gap nobody observed, so both are distrusted by the same
     * comparison rather than by an initialisation step.
     */
    public void observing(String corpus) {
        Objects.requireNonNull(corpus, "corpus");
        if (disabled) {
            return;
        }
        floors.put(corpus, now());
    }

    /**
     * Records that {@code path} moved, against every registered corpus whose scope covers it.
     *
     * <p>The instant is always advanced rather than skipped for an instance already marked. That
     * distinction matters: skipping is sound for a write and unsound for an observation, since a
     * second change arriving after a read, against an instance marked before it, would leave
     * nothing to see. Advancing is the mark.
     *
     * <p>A false mark is cheap. Distrust discards no stamp, so an instance marked by a save that
     * changed nothing is hashed, compared against the stamp it still carries, and skipped.
     */
    public void mark(Path path) {
        if (disabled || path == null) {
            return;
        }
        Path resolved = absolute(path);
        var at = now();
        registered.forEach((corpus, registration) -> {
            if (covers(registration, resolved)) {
                marks.put(key(corpus, registration.fold().apply(resolved)), at);
            }
        });
    }

    /**
     * Gives up on {@code corpus}: an event was dropped, a subtree arrived mid-session, or a watcher
     * could not resolve what it was told, so nothing read before now can be trusted for it.
     *
     * <p>Expressed as the floor rather than as a state of its own, which is what makes recovery
     * automatic: trust rebuilds instance by instance as the next verifying pass writes fresh
     * {@code read_at} values, so a loss costs one pass rather than the rest of the session. The
     * reason is carried for a console line and is not a case any consumer switches on.
     */
    public void lose(String corpus, String reason) {
        Objects.requireNonNull(corpus, "corpus");
        if (disabled) {
            return;
        }
        floors.put(corpus, now());
        if (reason != null) {
            losses.put(corpus, reason);
        }
    }

    /**
     * Why {@code corpus} last dropped out of observation, or null if it never has. Diagnostic: it
     * is what a console line says instead of naming files when the session cannot name them.
     */
    public String lossReason(String corpus) {
        return losses.get(corpus);
    }

    /**
     * Whether {@code instanceKey} of {@code corpus}, whose content was read at {@code readAt}, can
     * be believed without reading its bytes again. The comparison this whole type exists for.
     *
     * <p>False for a null {@code readAt} (nothing has read it under any observation), for a corpus
     * with no floor (nothing is watching it), and for an instance outside the registered scope
     * (nobody claimed to be watching where it lives).
     */
    public boolean trusts(String corpus, String instanceKey, LocalDateTime readAt) {
        if (disabled || readAt == null || instanceKey == null) {
            return false;
        }
        var floor = floors.get(corpus);
        if (floor == null || !readAt.isAfter(floor)) {
            return false;
        }
        var registration = registered.get(corpus);
        if (registration == null || !covers(registration, absolute(Path.of(instanceKey)))) {
            return false;
        }
        var mark = marks.get(key(corpus, instanceKey));
        return mark == null || readAt.isAfter(mark);
    }

    /**
     * The instant a pass over {@code corpus} writes into every row it verifies.
     *
     * <p>Taken here rather than by each caller so that "before the content is read" has one
     * implementation: a caller takes this before its walk and carries it through, and a
     * {@code read_at} stamped at commit time instead would swallow every change that landed while
     * the pass was reading.
     */
    public LocalDateTime pass(String corpus) {
        Objects.requireNonNull(corpus, "corpus");
        return now();
    }

    /** Whether any corpus is being watched at all; a session watching none trusts nothing. */
    public boolean observesAnything() {
        return !disabled && !floors.isEmpty();
    }

    /**
     * The floor {@link #observing} established for {@code corpus}, or null while nothing is
     * watching it. A seam for the cases that pin the comparison's strictness, which need an instant
     * exactly equal to the one held here and cannot construct one from outside.
     */
    LocalDateTime floorOf(String corpus) {
        return floors.get(corpus);
    }

    /** The mark against one instance, or null; the seam's other half. */
    LocalDateTime markOf(String corpus, String instanceKey) {
        return marks.get(key(corpus, instanceKey));
    }

    /** How many instances carry a mark; a seam for the case that pins a coarse fold's cost. */
    int markCount() {
        return marks.size();
    }

    private boolean declared(String corpus) {
        return dsl.fetchExists(dsl.selectOne().from(META_GATHERER_CORPUS)
            .where(META_GATHERER_CORPUS.CORPUS_NAME.eq(corpus)));
    }

    private static boolean covers(Registration registration, Path path) {
        for (Path root : registration.scope()) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static String key(String corpus, String instanceKey) {
        return corpus + '\0' + instanceKey;
    }

    private static List<Path> normalised(List<Path> scope) {
        if (scope == null) {
            return List.of();
        }
        return scope.stream().filter(Objects::nonNull)
            .map(Observation::absolute).distinct().toList();
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * Truncated to the resolution the store's {@code TIMESTAMP} columns keep, so an instant held
     * here and the same instant read back out of a row compare as equal rather than as a value the
     * write silently rounded down.
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
