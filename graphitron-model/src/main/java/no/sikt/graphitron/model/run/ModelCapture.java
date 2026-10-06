package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.capture.graphitron.GraphitronFactCapture;
import no.sikt.graphitron.model.derive.NameMatchedKeys;
import no.sikt.graphitron.model.derive.StageProgress;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.capture.sdl.SdlFactCapture;
import no.sikt.graphitron.model.capture.code.ClasspathSourceCapture;
import no.sikt.graphitron.model.capture.code.CodeCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.graphitron.GraphitronAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.capture.jooq.JooqFactCapture;
import no.sikt.graphitron.model.capture.store.StoreEntries;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The gatherers a run runs, against a store somebody else opened. Each reads its inputs from
 * {@code config}, except the classpath and the jOOQ catalog, which the build tool assembles and so
 * arrive as arguments.
 */
public final class ModelCapture {

    private ModelCapture() {}

    /**
     * Writes what {@code graph}'s inputs now say.
     *
     * @param readAt one instant per reading: every relation sweeps by it, so two readings sharing
     *     one could not tell each other's rows apart
     */
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                               List<ClasspathEntry> classpath, JooqCatalog jooq,
                               LocalDateTime readAt) {
        capture(dsl, graph, config, classpath, jooq, readAt, null);
    }

    /**
     * {@link #capture(DSLContext, GraphIdentity, SubjectConfig, List, JooqCatalog, LocalDateTime)}
     * reporting the derivations to {@code progress}, or to the log where it is null.
     */
    @SuppressWarnings("deprecation")
    public static void capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                               List<ClasspathEntry> classpath, JooqCatalog jooq,
                               LocalDateTime readAt, StageProgress progress) {
        writeGraph(dsl, graph, readAt);
        StoreEntries.write(dsl, graph.name(), config, readAt);

        JooqFactCapture.capture(dsl, graph.name(), jooq, readAt);
        NameMatchedKeys.derive(dsl);
        var classes = ClasspathSourceCapture.capture(dsl, graph.name(), classpath,
            config.jooqPackage().orElse(null), readAt);
        CodeCapture.capture(dsl, classes, jooq == null ? null : jooq.codegenLoader(), readAt);

        var reading = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
        var documents = reading.documents();
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        var assembled = GraphQLAssemblyCapture.capture(dsl, graph, reading, readAt);
        GraphQLSourceCapture.reclaim(dsl, documents);

        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
        var decode = new FactSink(dsl, graph.name(), readAt);
        GraphitronFactCapture.clear(dsl, graph.name());
        SdlFactCapture.capture(decode, assembled.merged());
        decode.flush();
        GraphitronAssemblyCapture.capture(dsl, graph.name(), readAt);
        // The store does not analyse itself, and ANALYZE commits: it runs outside the derivations,
        // never between a step's delete and its inserts.
        dsl.execute("ANALYZE");
        if (progress == null) {
            FactCapture.derive(dsl, graph, assembled.assembly());
        } else {
            FactCapture.derive(dsl, graph, assembled.assembly(), progress);
        }
        dsl.execute("ANALYZE");
    }

    /**
     * The row every relation this run writes hangs a foreign key on. Public because a fixture
     * running the gatherers itself still owes it first.
     */
    public static void writeGraph(DSLContext dsl, GraphIdentity graph, LocalDateTime readAt) {
        var t = STORE_GRAPH;
        dsl.insertInto(t, t.GRAPH_NAME, t.BASE_DIR, t.LAST_CAPTURED)
            .values(graph.name(), graph.baseDir().toString(), readAt)
            .onDuplicateKeyUpdate()
            .set(t.BASE_DIR, graph.baseDir().toString())
            .set(t.LAST_CAPTURED, readAt)
            .execute();
    }
}
