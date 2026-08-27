package no.sikt.graphitron.model.derive;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * What a materialization refresh says about itself while it runs: the observer
 * {@link Materializations#refresh(org.jooq.DSLContext, String, RefreshProgress)} and
 * {@link Materializations#refreshAll(org.jooq.DSLContext, RefreshProgress)} report to.
 *
 * <p>An observer rather than a logger, for three reasons. The one property the instrument exists to
 * hold is that a registration's name is emitted <em>before</em> its statement is issued, so that a
 * statement which never returns has already named itself; that property is a sequence, and a
 * sequence is a list a test reads where a log is an appender a test has to install. This module
 * carries jOOQ and H2 and no logging framework, which {@link Materializations#analyse} already
 * explains a design choice by, and both callers of the refresh already own a place to print. And
 * progress written as rows is ruled out by the failure this instruments: the capture cadence
 * refreshes inside its own transaction, so rows are invisible until a commit the stuck run never
 * reaches.
 *
 * <p>An observer that throws is a programming error and propagates. This seam has none of
 * {@link Materializations#analyse}'s best-effort posture: containing an exception here would hide a
 * broken caller behind a refresh that looks silent for the ordinary reason.
 *
 * @see Materializations
 */
@FunctionalInterface
public interface RefreshProgress {

    /** Takes one event, in the order the refresh emits them. */
    void observe(Event event);

    /**
     * What a refresh reports. Sealed so a caller mapping events onto a surface writes one switch and
     * the compiler names the site when an event kind is added.
     *
     * <p>Every event carrying a name is emitted before the statement it names, and every event
     * carrying a duration after the statements it measures. That split is the whole point of the
     * instrument rather than a formatting choice: a pass that times each registration and reports
     * afterwards emits nothing at all for the registration that never returns, which is the only
     * case anybody reaches for this in.
     */
    sealed interface Event {

        /**
         * The pass is about to issue its first statement.
         *
         * @param registrations how many registrations the pass will run, in refresh order
         * @param graphs the graphs being refreshed: the one graph a capture is refreshing, or every
         *     graph the store holds under {@link Materializations#refreshAll}
         */
        record PassStarted(int registrations, List<String> graphs) implements Event {
            public PassStarted {
                graphs = List.copyOf(graphs);
            }
        }

        /**
         * One registration is about to issue its {@code DELETE}. Emitted before that statement and
         * not between the two, so a refresh stuck in either statement has already named itself.
         *
         * @param registration the view stating the rule and the table taking its rows
         * @param position this registration's 1-based place in the refresh order
         * @param total how many registrations the pass runs
         * @param graph the graph whose partition is being refreshed, empty where the target carries
         *     no graph in its shape and is refreshed whole
         */
        record RegistrationStarted(Materializations.Registration registration, int position,
                                   int total, Optional<String> graph) implements Event {}

        /**
         * One registration's statements have both returned.
         *
         * <p>The two durations are split because the two statements fail differently and nothing
         * else in the record would tell a slow refill from a slow delete. The row counts are the
         * return values of the two {@code execute()} calls, so they cost nothing and they are what
         * explains a slow registration that does finish.
         */
        record RegistrationFinished(Materializations.Registration registration, long deleteNanos,
                                    long insertNanos, int rowsDeleted, int rowsInserted)
            implements Event {}

        /**
         * The pass's last registration has returned.
         *
         * @param nanos how long the pass took, measured from {@link PassStarted}: the statements
         *     and the per-registration shape probes between them, and not the registry reads that
         *     derived the refresh order or the analysis that follows a whole-store refresh
         */
        record PassFinished(long nanos) implements Event {}
    }

    /** Observes nothing. What every caller that has not asked for progress gets. */
    static RefreshProgress none() {
        return event -> { };
    }

    /**
     * Renders each event to one line and hands it to the consumer for its tier, so the wording lives
     * in one place and a caller supplies two method references.
     *
     * <p>Two tiers because the two cadences differ by an order of magnitude in how often a line is
     * worth printing. The pass boundary is two lines per refresh and belongs wherever a person
     * watching a build can see it: a run that prints the first line and never the second is stuck
     * inside the refresh. The per-registration tier is two lines per registration per graph, which
     * is more than any default would keep, and it is what a person who has already killed a run
     * turns on to have the relation named within seconds.
     *
     * <p>The tiers are this rendering's reading of the events, not a rule the interface enforces: a
     * caller that wants both at one level passes one consumer twice.
     *
     * @param pass takes the pass-boundary lines
     * @param registration takes the per-registration lines
     */
    static RefreshProgress lines(Consumer<String> pass, Consumer<String> registration) {
        // The finished line repeats the position of the line that announced the registration, and
        // the finished event carries no position of its own: the pass emits the pair back to back on
        // one thread, so the announcement is what the position is read off. A fresh holder per
        // observer, since a caller mints one of these per refresh.
        var announced = new AtomicReference<Event.RegistrationStarted>();
        return event -> {
            switch (event) {
                case Event.PassStarted started -> pass.accept("graphitron: refreshing "
                    + started.registrations()
                    + (started.registrations() == 1 ? " materialization " : " materializations ")
                    + scope(started.graphs()));
                case Event.RegistrationStarted started -> {
                    announced.set(started);
                    registration.accept("graphitron: " + place(started) + " "
                        + started.registration().sourceViewName() + " -> "
                        + started.registration().targetTableName() + ", "
                        + started.graph().map(graph -> "graph '" + graph + "'")
                            .orElse("whole relation"));
                }
                case Event.RegistrationFinished finished -> {
                    var started = announced.get();
                    registration.accept("graphitron: "
                        + (started == null ? "" : place(started) + " ")
                        + "done in " + duration(finished.deleteNanos() + finished.insertNanos())
                        + ", deleted " + finished.rowsDeleted() + " rows in "
                        + duration(finished.deleteNanos())
                        + ", inserted " + finished.rowsInserted() + " rows in "
                        + duration(finished.insertNanos()));
                }
                case Event.PassFinished finished -> pass.accept(
                    "graphitron: materialization refresh done in " + duration(finished.nanos()));
            }
        };
    }

    /** The graphs a pass is refreshing, as the pass-boundary line names them. */
    private static String scope(List<String> graphs) {
        return switch (graphs.size()) {
            case 0 -> "for no graphs";
            case 1 -> "for graph '" + graphs.getFirst() + "'";
            default -> graphs.stream()
                .map(graph -> "'" + graph + "'")
                .collect(Collectors.joining(", ", "for graphs ", ""));
        };
    }

    /**
     * A registration's place in the sequence, the position padded to the width of the total so the
     * per-registration lines of one pass line up in a console.
     */
    private static String place(Event.RegistrationStarted started) {
        int width = String.valueOf(started.total()).length();
        return String.format(Locale.ROOT, "%" + width + "d/%d", started.position(),
            started.total());
    }

    /**
     * A duration as a person reading a console wants it: milliseconds below a second, one decimal of
     * a second above, since the figures worth ranking here span microseconds to minutes.
     */
    private static String duration(long nanos) {
        return nanos < 1_000_000_000L
            ? Math.round(nanos / 1_000_000d) + " ms"
            : String.format(Locale.ROOT, "%.1f s", nanos / 1_000_000_000d);
    }
}
