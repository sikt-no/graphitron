package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.SourceGraph;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.DSLContext;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The language server's read access to the fact store: a reader of its own, plus the graph the
 * session was started for. One instance per session, held by {@link Workspace} and closed with it.
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

    private final StoreReader reader;
    private final String graphName;

    /**
     * @param reader the session's own reader, minted by the store the session writes through, whose
     *               lifetime this object takes over
     * @param graphName the graph this session was started for, which is the tiebreak when a
     *                  document belongs to more than one
     */
    public StoreAccess(StoreReader reader, String graphName) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.graphName = Objects.requireNonNull(graphName, "graphName");
    }

    /**
     * Runs {@code answer} inside one read transaction, handing it the handle for {@code sourceName}'s
     * graph or {@link Optional#empty()} when no graph of this session's answers for that document.
     *
     * <p>The handle is valid for the call only. A handler that stores it answers its next request
     * from a transaction that has already ended, which is the tear this method exists to prevent.
     */
    public <R> R answering(String sourceName, Function<Optional<StoreHandle>, R> answer) {
        return answeringAll(List.of(sourceName), handles -> answer.apply(handles.of(sourceName)));
    }

    /**
     * The same, for several documents answered together: one read transaction over all of them, and one
     * membership resolution for the whole set rather than one per document.
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
    public <R> R answeringAll(
        Collection<String> sourceNames, Function<DocumentHandles, R> answer
    ) {
        return reader.read(dsl -> {
            var resolved = SourceGraph.ofAll(dsl, sourceNames);
            return answer.apply(sourceName -> Optional.ofNullable(resolved.get(sourceName))
                .flatMap(graph -> handleFor(dsl, graph)));
        });
    }

    /**
     * Runs {@code read} against this session's own graph, inside one read transaction and without
     * resolving any document first. The door for the questions that are about the session rather than
     * about a file an editor has open: the directive vocabulary is the shipped case, being one
     * capture's answer that every document in the session is then judged against.
     */
    public <R> R readingSessionGraph(Function<StoreHandle, R> read) {
        return reader.read(dsl -> read.apply(new StoreHandle(dsl, graphName)));
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

    /** Releases the reader. The store itself belongs to the session's writer, never to this. */
    @Override
    public void close() {
        reader.close();
    }
}
