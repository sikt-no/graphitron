package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.SourceGraph;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The language server's read access to the fact store: readers of its own, plus the graph the
 * session was started for. One instance per session, held by {@link Workspace} and closed with it.
 *
 * <p>Three readers, not one, because reads on one reader serialize and the session has three things
 * waiting on them. A keystroke and a whole-workspace recalculation queued behind each other on one
 * reader is head-of-line blocking with nothing to show for it, and the two want different budgets
 * besides: the drain is the read that most wants headroom and least wants to fail, a hover the
 * opposite. The four doors below partition the grains exactly as they stand, which is what makes
 * routing by door a delegation split rather than a restructure: {@link #answering} is every surface
 * an editor blocks a cursor on, {@link #annotating} is the document-scoped annotation surfaces,
 * {@link #answeringAll} is the diagnostics drain alone, and {@link #readingSessionGraph} is session
 * state read once per triggering event. So the interactive reader serves the first, a reader of its
 * own serves the second, and the session-wide reader serves the other two, which never contend:
 * both hang off the same enqueue, so they run in sequence on one trigger.
 *
 * <h2>Why annotation is its own reader</h2>
 *
 * <p>An inlay-hint request is the one request surface whose cost scales with the document rather
 * than with the cursor: it arrives per visible region, an editor reissues it on every scroll, and
 * the work is one statement over every annotatable site in that region. Sharing the interactive
 * reader therefore put a cursor behind a request nobody is looking at, and how far behind is a
 * function of how much schema the region covers. Over a four-thousand-line schema with every hint
 * axis enabled, a whole-file request does not finish inside the interactive budget at all: it spends
 * the budget and is aborted, and every hover and jump queued behind it waited for that. Which is
 * what a developer reports as the feature hanging in a few places in a row, and no single surface's
 * own cost explains it.
 *
 * <p>So the split is by who is waiting, not by a number. A hint arriving late is invisible where a
 * cursor arriving late is not, and the two therefore do not belong in one queue. Both readers keep
 * the same runaway guard: this is a connection split and not a latency policy, {@code ReadBudget}
 * being a guard against a query that would never return rather than a threshold on how long an
 * answer may take.
 *
 * <p>A caller that picks the wrong door therefore gets the wrong queue, and possibly the wrong
 * budget. Each door says which grain it is for, so the choice is visible where it is made, and the
 * consequence is a mis-sized budget or a needless wait rather than a wrong answer.
 *
 * <p>Every answer goes through {@link #answering}, which does two things a handler must not do for
 * itself. It opens the one read transaction the answer assembles inside, so a handler running
 * several queries cannot see one capture for its first and the next for its second. And it resolves
 * the document to the graph whose facts answer for it, which is the step that makes a store shared
 * by a whole workspace safe to query: the graph-keyed relations lead with {@code graph_name}, and
 * the source-keyed ones reach it through {@code store_graph_source}.
 *
 * <p>The session's own graph is what settles the shared-file case. {@link SourceGraph} refuses to
 * pick when a schema file belongs to two graphs, because at that layer both memberships are equally
 * true; here there is more to go on. A request arrived from an editor with this project open, so if
 * the session's graph is one of the members it is the one the author meant, and picking it is a
 * decision rather than a row order. When it is not a member the file belongs to other modules
 * entirely and the session has nothing to say about it.
 *
 * <p>A session with no store answers everything absent. That is not a degraded store but the
 * absence of one: a bare {@code Launcher} started outside a build has no store home to be told
 * about, exactly as it has no catalog today. A dev session always has one, since the store's own
 * fallback is a private in-memory database rather than nothing.
 */
public final class StoreAccess implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreAccess.class);

    private final StoreReader interactive;
    private final StoreReader annotation;
    private final StoreReader sessionWide;
    private final String graphName;

    /**
     * @param interactive the reader every cursor-blocking read goes through, whose lifetime this
     *                    object takes over
     * @param annotation the reader the document-scoped annotation reads go through, whose lifetime
     *                   this object takes over
     * @param sessionWide the reader the diagnostics drain and the session-state reads go through,
     *                    whose lifetime this object takes over
     * @param graphName the graph this session was started for, which is the tiebreak when a
     *                  document belongs to more than one
     */
    public StoreAccess(StoreReader interactive, StoreReader annotation, StoreReader sessionWide,
                       String graphName) {
        this.interactive = Objects.requireNonNull(interactive, "interactive");
        this.annotation = Objects.requireNonNull(annotation, "annotation");
        this.sessionWide = Objects.requireNonNull(sessionWide, "sessionWide");
        this.graphName = Objects.requireNonNull(graphName, "graphName");
    }

    /**
     * Runs {@code answer} inside one read transaction on the <em>interactive</em> reader, handing it
     * the handle for {@code sourceName}'s graph or {@link Optional#empty()} when no graph of this
     * session's answers for that document. The keystroke grain: every surface an editor blocks a
     * cursor on comes through here.
     *
     * <p>The handle is valid for the call only. A handler that stores it answers its next request
     * from a transaction that has already ended, which is the tear this method exists to prevent.
     *
     * <p>Not delegated to {@link #answeringAll}, which is what it used to be. The two doors carry
     * different budgets now, so a single-document read routed through the bulk one would answer
     * every keystroke on the reader the drain owns, which is both the wrong budget and the
     * head-of-line blocking two readers exist to remove. They share the private form below instead.
     */
    public <R> StoreAnswer<R> answering(
        StoreRead read, String sourceName, Function<Optional<StoreHandle>, R> answer
    ) {
        return resolving(read, interactive, List.of(sourceName),
            handles -> answer.apply(handles.of(sourceName)));
    }

    /**
     * The same, for a read that annotates a region of one document rather than answering about one
     * coordinate in it, on a reader of its own. The class javadoc above carries why: this grain's
     * cost scales with the region an editor happens to be showing, and a cursor must not queue
     * behind it.
     *
     * <p>Same shape and same guard as {@link #answering}, and deliberately so. What differs is the
     * connection, which is the whole point: nothing here is a claim that an annotation read may take
     * longer, only that whoever is waiting for one is not holding a cursor.
     */
    public <R> StoreAnswer<R> annotating(
        StoreRead read, String sourceName, Function<Optional<StoreHandle>, R> answer
    ) {
        return resolving(read, annotation, List.of(sourceName),
            handles -> answer.apply(handles.of(sourceName)));
    }

    /**
     * The same, for several documents answered together, on the <em>session-wide</em> reader: one read
     * transaction over all of them, and one membership resolution for the whole set rather than one per
     * document. The drain grain, and its only caller is the diagnostics recalculation.
     *
     * <p>Bulk because a request about many documents is one request. Resolving each separately cost a
     * query per document before a single fact had been read, which for a whole-workspace recalculation
     * was half its statements; {@link SourceGraph#ofAll} answers the set at once and this applies the
     * session's tiebreak to each answer.
     *
     * <p>The handles are valid for the call only, exactly as the single-document form's is, and
     * {@link DocumentHandles} exists to say so in a signature: it is a lookup into one transaction, not
     * a map a caller may keep.
     */
    public <R> StoreAnswer<R> answeringAll(
        StoreRead read, Collection<String> sourceNames, Function<DocumentHandles, R> answer
    ) {
        return resolving(read, sessionWide, sourceNames, answer);
    }

    /**
     * Runs {@code reading} against this session's own graph on the <em>session-wide</em> reader, inside
     * one read transaction and without resolving any document first. The door for the questions that
     * are about the session rather than about a file an editor has open: the directive vocabulary is
     * the shipped case, being one capture's answer that every document in the session is then judged
     * against.
     *
     * <p>The session-state grain rather than the keystroke one, which is why it shares the drain's
     * reader: it is read once per triggering event, not once per answer.
     */
    public <R> StoreAnswer<R> readingSessionGraph(StoreRead read, Function<StoreHandle, R> reading) {
        return warned(read, sessionWide.read(dsl -> reading.apply(new StoreHandle(dsl, graphName))));
    }

    /**
     * The membership resolution both document-keyed doors share, with the reader passed in rather
     * than picked here: which reader answers is the door's decision, and the resolution is the same
     * either way.
     */
    private <R> StoreAnswer<R> resolving(
        StoreRead read, StoreReader reader, Collection<String> sourceNames,
        Function<DocumentHandles, R> answer
    ) {
        return warned(read, reader.read(dsl -> {
            var resolved = SourceGraph.ofAll(dsl, sourceNames);
            return answer.apply(sourceName -> Optional.ofNullable(resolved.get(sourceName))
                .flatMap(graph -> handleFor(dsl, graph)));
        }));
    }

    /**
     * One warning per expired budget, here rather than at each surface. The surfaces decide what to
     * show, which is a different decision and genuinely theirs; whether the developer hears about it
     * at all is the store boundary's, and stating it once means a new surface cannot forget to and
     * five surfaces cannot each say it.
     *
     * <p>The WARN names the read and nothing else, because what a developer scanning a console can
     * act on is <em>which question</em> the server gave up on: that is the thing to say in a bug
     * report and to grep for when the same surface goes quiet again. The statement is still the one
     * thing a fix needs and the one thing nobody can reconstruct afterwards, so it is not thrown
     * away: it drops to DEBUG on this same logger, where somebody debugging asks for it and nobody
     * else pays a thousand-character line for it. The WARN carries the pointer, spelling the logger
     * name out because that is what a developer types into a logback config.
     *
     * <p>Through slf4j rather than {@link no.sikt.graphitron.lsp.trace.LspTrace}, whose javadoc warns
     * that the LSP's stdio deployment often carries {@code slf4j-api} with no backend bound and would
     * discard this. That caveat does not reach here: a session that can overrun a budget is a session
     * with a store, which is a {@code graphitron:dev} session running inside the Maven JVM and its
     * bound backend, and the build log is where the draft in the user manual says to look. A bare
     * {@code Launcher} started outside a build has no store to read at all.
     */
    private static <R> StoreAnswer<R> warned(StoreRead read, StoreAnswer<R> answer) {
        if (answer instanceof StoreAnswer.OutOfBudget<R> expired) {
            LOGGER.warn("{} ran out of its {} budget and was aborted, so this surface keeps what it "
                    + "was already showing rather than answering from a partial read. The statement "
                    + "that overran is logged at DEBUG on {}.",
                read.phrase(), expired.budget().describe(), LOGGER.getName());
            LOGGER.debug("the statement {} overran: {}", read.phrase(), expired.sql());
        }
        return answer;
    }

    /**
     * Resolves a document to the handle that answers for it. Valid only inside the
     * {@link #answeringAll} call it arrived in: a handle used after its transaction has ended is a read
     * that can tear against a capture.
     */
    @FunctionalInterface
    public interface DocumentHandles {

        /** The handle for {@code sourceName}, empty where no graph of this session's answers for it. */
        Optional<StoreHandle> of(String sourceName);
    }

    private Optional<StoreHandle> handleFor(DSLContext dsl, SourceGraph graph) {
        return switch (graph) {
            case SourceGraph.Scoped scoped -> Optional.of(scoped.handle());
            case SourceGraph.Shared shared -> shared.graphNames().contains(graphName)
                ? Optional.of(new StoreHandle(dsl, graphName))
                : Optional.empty();
            case SourceGraph.Uncaptured ignored -> Optional.empty();
        };
    }

    /**
     * The store's name for the document at {@code uri}. Delegates to {@link SourceUri}, which owns
     * both directions of the trip in the module that declares the columns they meet on, so an
     * editor's URI and a captured row cannot drift apart on one side's spelling.
     */
    public static Optional<String> sourceNameOf(String uri) {
        return SourceUri.sourceNameOf(uri);
    }

    /** Releases every reader. The store itself belongs to the session's writer, never to this. */
    @Override
    public void close() {
        interactive.close();
        annotation.close();
        sessionWide.close();
    }
}
