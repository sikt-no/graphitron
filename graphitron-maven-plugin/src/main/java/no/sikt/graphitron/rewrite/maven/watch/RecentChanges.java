package no.sikt.graphitron.rewrite.maven.watch;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the session was told since each corpus's round last spoke, so a round can say why it is
 * running instead of leaving a developer to guess.
 *
 * <p>Guessing is the problem worth naming. Today a round announces that it happened, which is
 * enough while the guess is right and useless exactly when it is wrong: an editor writing a stray
 * file, or another terminal's build landing class files, produces a round that looks like it came
 * from the save just made.
 *
 * <p>Diagnostic and never authoritative. It holds paths where the marks an
 * {@link no.sikt.graphitron.model.sources.Observation} keeps hold folded instance keys, because a
 * console line wants the file that moved and a fold may have coarsened it to the root it lives
 * under. Nothing decides anything from what is here, so a bound on how much it keeps costs
 * accuracy in the log and nothing else.
 *
 * <p>Drained per corpus rather than read, because there is exactly one round per corpus and it is
 * the consumer of that corpus's events: draining is what stops the next round repeating what this
 * one already said. The watch threads write and the round threads drain, so the state is
 * concurrent.
 */
public final class RecentChanges {

    /** How many paths one corpus keeps; beyond it the count is reported and the paths are not. */
    private static final int LIMIT = 8;

    private final Path base;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @param base what paths are rendered relative to, normally the module's base directory, so a
     *             line names {@code src/main/resources/schema.graphqls} rather than an absolute
     *             path the developer has to read past. A path outside it renders whole.
     */
    public RecentChanges(Path base) {
        this.base = base == null ? null : base.toAbsolutePath().normalize();
    }

    /**
     * A ring with nothing to render paths against, for a caller that has no module directory: it
     * records and drains exactly as any other and names paths whole.
     */
    public static RecentChanges none() {
        return new RecentChanges(null);
    }

    /** Records that {@code path} of {@code corpus} moved. Called on a watch thread. */
    public void record(String corpus, Path path) {
        if (corpus == null || path == null) {
            return;
        }
        pending.computeIfAbsent(corpus, ignored -> new Pending()).add(path);
    }

    /**
     * Records that {@code corpus} dropped out of observation and why, which is the arm where the
     * session has a reason to give instead of files to name.
     */
    public void lost(String corpus, String reason) {
        if (corpus == null) {
            return;
        }
        pending.computeIfAbsent(corpus, ignored -> new Pending()).lose(reason);
    }

    /**
     * What {@code corpus} has been told since this returned last, as the clause a caller puts
     * after "told about", or empty when it has been told nothing.
     */
    public Optional<String> drain(String corpus) {
        Pending state = pending.get(corpus);
        return state == null ? Optional.empty() : state.drain(this::render);
    }

    private String render(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (base != null && absolute.startsWith(base)) {
            return base.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    /** One corpus's unspoken events: a bounded ring of paths, a total, and a loss reason. */
    private static final class Pending {

        private final Deque<Path> paths = new ArrayDeque<>();
        private int total;
        private String reason;

        synchronized void add(Path path) {
            total++;
            paths.addLast(path);
            if (paths.size() > LIMIT) {
                paths.removeFirst();
            }
        }

        synchronized void lose(String why) {
            reason = why;
        }

        synchronized Optional<String> drain(java.util.function.Function<Path, String> render) {
            if (total == 0 && reason == null) {
                return Optional.empty();
            }
            var named = new ArrayList<String>(paths.size());
            paths.forEach(path -> named.add(render.apply(path)));
            int shown = named.size();
            int seen = total;
            String why = reason;
            paths.clear();
            total = 0;
            reason = null;
            return Optional.of(sentence(named, shown, seen, why));
        }

        private static String sentence(List<String> named, int shown, int seen, String why) {
            var line = new StringBuilder();
            if (seen > 0) {
                line.append(seen).append(seen == 1 ? " changed file: " : " changed files: ");
                line.append(String.join(", ", named));
                if (shown < seen) {
                    line.append(" (most recent ").append(shown).append(')');
                }
            }
            if (why != null) {
                line.append(seen > 0 ? "; and " : "")
                    .append("a change it cannot name (").append(why).append(')');
            }
            return line.toString();
        }
    }
}
