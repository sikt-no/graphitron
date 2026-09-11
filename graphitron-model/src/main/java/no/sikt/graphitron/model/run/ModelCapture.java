package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.capture.code.CodeCapture;
import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.capture.jooq.JooqFactCapture;
import no.sikt.graphitron.model.capture.store.StoreEntries;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The gatherers a run runs, against a store somebody else opened.
 *
 * <p>Each finds its own inputs from {@code config}, except the two that read compiled code, which
 * configuration cannot carry: a classpath is assembled by the build tool rather than declared by an
 * author. They want different things of it. The census reads bytes, so {@code classpath} is
 * directories and jars parsed as classfiles with nothing loaded. The catalog cannot be read that
 * way, jOOQ building its tables and keys in static initialisers rather than declaring them in the
 * bytes, so it arrives already built and carries the loader it was built through.
 *
 * <p>One of them reads no input at all. The configuration is not a source to be gathered from, it
 * is the run's own declaration about itself, and it is transcribed for the reader who cannot ask
 * the run: a sibling module, a maintenance surface, a later session looking at a graph whose build
 * has exited.
 *
 * <p>Absence is per input: no classpath is no census, no catalog is no catalog facts, and neither
 * touches the schema.
 *
 * <p>The classpath arrives classified rather than as bare paths. How an entry reached the classpath
 * is a decision its producer took and no consumer can recover from a path, and it is what a reader
 * scopes by to ask about the reactor rather than about the world.
 *
 * <p>Takes a {@link DSLContext} rather than a store, so a caller already holding one open for more
 * than this captures into the store its own readers are on.
 */
public final class ModelCapture {

    private ModelCapture() {}

    /**
     * Writes what {@code graph}'s inputs now say.
     *
     * <p>One instant per reading: every relation these fill sweeps by it, so two readings sharing
     * one could not tell each other's rows apart and what the second no longer finds would stay.
     */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                               List<ClasspathEntry> classpath, JooqCatalog jooq,
                               LocalDateTime readAt) {
        writeGraph(dsl, graph, readAt);
        SdlCapture.capture(dsl, graph, config, readAt);
        StoreEntries.write(dsl, graph.name(), config, readAt);
        JooqFactCapture.capture(dsl, jooq, readAt);
        CodeCapture.capture(dsl, classpath, config.jooqPackage().orElse(null),
            jooq == null ? null : jooq.codegenLoader(), readAt);
    }

    /**
     * The row every other relation this run writes hangs a foreign key on: which graph, where it
     * was read from, and when it was last read.
     */
    private static void writeGraph(DSLContext dsl, GraphIdentity graph, LocalDateTime readAt) {
        var t = STORE_GRAPH;
        dsl.insertInto(t, t.GRAPH_NAME, t.BASE_DIR, t.LAST_CAPTURED)
            .values(graph.name(), graph.baseDir().toString(), readAt)
            .onDuplicateKeyUpdate()
            .set(t.BASE_DIR, graph.baseDir().toString())
            .set(t.LAST_CAPTURED, readAt)
            .execute();
    }
}
