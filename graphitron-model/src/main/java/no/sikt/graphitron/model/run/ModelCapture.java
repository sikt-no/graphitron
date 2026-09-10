package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.capture.classpath.ClasspathFactCapture;
import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.capture.jooq.JooqFactCapture;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import org.jooq.DSLContext;

import java.nio.file.Path;
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
 * <p>Absence is per input: no classpath is no census, no catalog is no catalog facts, and neither
 * touches the schema.
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
                               List<Path> classpath, JooqCatalog jooq, LocalDateTime readAt) {
        SdlCapture.capture(dsl, graph, config, readAt);
        JooqFactCapture.capture(dsl, jooq, readAt);
        ClasspathFactCapture.capture(dsl, classpath, config.jooqPackage().orElse(null), readAt);
    }
}
