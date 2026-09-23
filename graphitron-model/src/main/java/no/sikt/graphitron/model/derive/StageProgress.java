package no.sikt.graphitron.model.derive;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * What the derivation stratum says about itself while it runs: the observer
 * {@link DerivationStratum#run} reports to.
 *
 * <p>An observer rather than a logger, for three reasons. The one property the instrument exists to
 * hold is that a stage's name is emitted <em>before</em> its statements are issued, so that a
 * statement which never returns has already named itself; that property is a sequence, and a
 * sequence is a list a test reads where a log is an appender a test has to install. This module
 * carries jOOQ and H2 and no logging framework, and every caller of the stratum already owns a place
 * to print. And progress written as rows is ruled out by the failure this instruments: on a warm
 * store the stratum runs inside one transaction, so rows are invisible until a commit the stuck run
 * never reaches.
 *
 * <p>An observer that throws is a programming error and propagates: containing an exception here
 * would hide a broken caller behind a stratum that looks silent for the ordinary reason.
 */
@FunctionalInterface
public interface StageProgress {

    /** Takes one event, in the order the stratum emits them. */
    void observe(Event event);

    /**
     * What the stratum reports. Sealed so a caller mapping events onto a surface writes one switch and
     * the compiler names the site when an event kind is added.
     *
     * <p>Every event carrying a name is emitted before the statements it names, and every event
     * carrying a duration after the statements it measures. That split is the whole point of the
     * instrument rather than a formatting choice: a pass that timed each stage and reported
     * afterwards would emit nothing at all for the stage that never returns, which is the only case
     * anybody turns this on for.
     */
    sealed interface Event {

        /**
         * The stratum is about to issue its first statement.
         *
         * @param stages how many steps it will run, in order
         * @param graph the graph whose partitions they rewrite
         * @param analysing whether each step commits and analyses what it wrote before the next,
         *     which is the cadence a store none of whose stage tables holds a row takes
         */
        record PassStarted(int stages, String graph, boolean analysing) implements Event {}

        /**
         * One step is about to issue its first statement.
         *
         * @param step the step, carrying its name and what it writes
         * @param position its 1-based place in the stratum
         * @param total how many steps the stratum runs
         */
        record StageStarted(DerivationStratum.Step step, int position, int total) implements Event {}

        /**
         * One step's statements have returned.
         *
         * @param step the step, as announced
         * @param nanos how long its statements took, the analysis that follows on the analysing
         *     cadence excluded
         * @param rows how many rows the tables it writes hold for the graph afterwards
         */
        record StageFinished(DerivationStratum.Step step, long nanos, int rows) implements Event {}

        /** The stratum's last step has returned. */
        record PassFinished(long nanos) implements Event {}
    }

    /** Observes nothing. What every caller that has not asked for progress gets. */
    static StageProgress none() {
        return event -> { };
    }

    /**
     * Renders each event to one line and hands it to the consumer for its tier, so the wording lives
     * in one place and a caller supplies two method references.
     *
     * <p>Two tiers because the two differ by an order of magnitude in how often a line is worth
     * printing. The pass boundary is two lines per capture and belongs wherever a person watching a
     * build can see it: a run that prints the first line and never the second is stuck inside the
     * stratum. The per-stage tier is two lines per step, which is more than any default would keep,
     * and it is what a person who has already killed a run turns on to have the stage named within
     * seconds.
     *
     * @param pass takes the pass-boundary lines
     * @param stage takes the per-stage lines
     */
    static StageProgress lines(Consumer<String> pass, Consumer<String> stage) {
        // The finished line repeats the position of the line that announced the step; the stratum
        // emits the pair back to back on one thread, so the announcement is what it is read off.
        var announced = new AtomicReference<Event.StageStarted>();
        return event -> {
            switch (event) {
                case Event.PassStarted started -> pass.accept("graphitron: deriving "
                    + started.stages() + (started.stages() == 1 ? " stage" : " stages")
                    + " for graph '" + started.graph() + "'"
                    + (started.analysing() ? ", committing and analysing each" : ""));
                case Event.StageStarted started -> {
                    announced.set(started);
                    stage.accept("graphitron: " + place(started) + " stage "
                        + started.step().name());
                }
                case Event.StageFinished finished -> {
                    var started = announced.get();
                    stage.accept("graphitron: " + (started == null ? "" : place(started) + " ")
                        + "done in " + duration(finished.nanos()) + ", " + finished.rows()
                        + (finished.rows() == 1 ? " row" : " rows"));
                }
                case Event.PassFinished finished -> pass.accept(
                    "graphitron: derivation stratum done in " + duration(finished.nanos()));
            }
        };
    }

    /** A step's place, the position padded to the total's width so one pass's lines line up. */
    private static String place(Event.StageStarted started) {
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
