package no.sikt.graphitron.lsp.state;

import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.SourceGraph;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.DSLContext;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
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
        return reader.read(dsl -> answer.apply(handleFor(dsl, sourceName)));
    }

    private Optional<StoreHandle> handleFor(DSLContext dsl, String sourceName) {
        return switch (SourceGraph.of(dsl, sourceName)) {
            case SourceGraph.Scoped scoped -> Optional.of(scoped.handle());
            case SourceGraph.Shared shared -> shared.graphNames().contains(graphName)
                ? Optional.of(new StoreHandle(dsl, graphName))
                : Optional.empty();
            case SourceGraph.Uncaptured ignored -> Optional.empty();
        };
    }

    /**
     * The store's name for the document at {@code uri}. Capture writes a schema file's
     * {@code source_name} as the absolute normalized path it read, which is what
     * {@code SchemaSource.File} renders and what {@code ValidationReport.canonicalUri} turns into a
     * URI; this is that trip backwards, so an editor's URI and a captured row meet on one spelling.
     * A URI naming no local file (an untitled buffer, a non-file scheme) resolves to no source name,
     * and the store has nothing to say about content that is not on disk.
     */
    public static Optional<String> sourceNameOf(String uri) {
        if (uri == null || !uri.startsWith("file:")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(URI.create(uri)).toAbsolutePath().normalize().toString());
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            return Optional.empty();
        }
    }

    /** Releases the reader. The store itself belongs to the session's writer, never to this. */
    @Override
    public void close() {
        reader.close();
    }
}
