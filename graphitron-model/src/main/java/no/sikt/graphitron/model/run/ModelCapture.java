package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.derive.StageProgress;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.sink.Progress;
import no.sikt.graphitron.model.capture.sdl.SdlFactCapture;
import no.sikt.graphitron.model.capture.code.ClasspathSourceCapture;
import no.sikt.graphitron.model.capture.code.CodeCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture.AssemblyReading;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.EmittedRegistry;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.LoadingRewrites;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.capture.federation.FederationCapture;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.capture.jooq.JooqFactCapture;
import no.sikt.graphitron.model.capture.store.StoreEntries;
import no.sikt.graphitron.model.sources.Observation;
import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The gatherers a run runs, against a store somebody else opened. Each reads its inputs from
 * {@code config}, except the classpath, which the build tool assembles, and the loader over it, for
 * the readings that have to run code: the jOOQ catalog and a scalar constant's coercing.
 */
public final class ModelCapture {

    private ModelCapture() {}

    /**
     * Writes what {@code graph}'s inputs now say, and hands back the two schemas the capture
     * built: the documents as written, and the post-synthesis schema the macros' rows make of them.
     *
     * <p>They are returned because they are built here and nowhere else: a run that goes on to
     * generate renders from them rather than building them again.
     *
     * @param readAt one instant per reading: every relation sweeps by it, so two readings sharing
     *     one could not tell each other's rows apart
     */
    public static CapturedSchema capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                                         List<ClasspathEntry> classpath, ClassLoader loader,
                                         LocalDateTime readAt) {
        return capture(dsl, graph, config, classpath, loader, readAt, null);
    }

    /**
     * {@link #capture(DSLContext, GraphIdentity, SubjectConfig, List, ClassLoader, LocalDateTime)}
     * reporting the derivations to {@code progress}, or to the log where it is null.
     *
     * <p>Each gatherer commits its own work, in transactions of its own choosing, and records how
     * far it got through {@link Progress}.
     */
    public static CapturedSchema capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                                         List<ClasspathEntry> classpath, ClassLoader loader,
                                         LocalDateTime readAt, StageProgress progress) {
        return capture(dsl, graph, config, classpath, loader, readAt, progress, null);
    }

    /**
     * {@link #capture(DSLContext, GraphIdentity, SubjectConfig, List, ClassLoader, LocalDateTime,
     * StageProgress)} under a dev session's observation, which lets the classpath reading leave an
     * entry the session saw nothing move under without looking at it.
     *
     * @param observation what the session watches and has seen move, or null for a run watching
     *                    nothing
     */
    @SuppressWarnings("deprecation")
    public static CapturedSchema capture(DSLContext dsl, GraphIdentity graph, SubjectConfig config,
                                         List<ClasspathEntry> classpath, ClassLoader loader,
                                         LocalDateTime readAt, StageProgress progress,
                                         Observation observation) {
        writeGraph(dsl, graph, readAt);
        Progress.started(dsl, graph.name(), Progress.CAPTURE);
        StoreEntries.write(dsl, graph.name(), config, classpath, readAt);

        String jooqPackage = config.jooqPackage().orElse(null);
        var classes = ClasspathSourceCapture.capture(dsl, graph.name(), classpath, jooqPackage,
            readAt, observation);
        // The catalog is the generated classes, so it is read again only where the entry holding
        // them moved; the classpath reading has just said whether it did.
        JooqFactCapture.capture(dsl, graph.name(), jooqPackage, loader, readAt,
            ClasspathSourceCapture.stampOfEntryHolding(dsl, classpath, jooqPackage).orElse(null));
        CodeCapture.capture(dsl, classes, loader, readAt);

        var reading = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
        // A document that will not parse ends the capture here, the store keeping its last clean
        // reading; the source reading has written the problems the throw reports.
        SchemaLoader.throwIfUnparsed(reading.failures());
        var documents = reading.documents();
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        FederationCapture.capture(dsl, graph.name(), readAt);

        // The decode reads only the corpus as written, so it runs ahead of the anchor.
        SdlFactCapture.decode(dsl, graph.name(), GraphQLAssemblyCapture.corpus(documents), readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
        // The composition hands federation-jvm the link the anchor just settled.
        var assembled = GraphQLAssemblyCapture.capture(dsl, graph, reading, readAt);
        GraphQLSourceCapture.reclaim(dsl, documents);
        // The post-synthesis schema, as soon as the anchor has resolved what the macros mint.
        var schemas = schemas(dsl, graph, assembled);
        // The store does not analyse itself, and ANALYZE commits: it runs outside the
        // derivations, never between a step's delete and its inserts.
        dsl.execute("ANALYZE");
        if (progress == null) {
            FactCapture.derive(dsl, graph, assembled.assembly());
        } else {
            FactCapture.derive(dsl, graph, assembled.assembly(), progress);
        }
        dsl.execute("ANALYZE");
        Progress.completed(dsl, graph.name(), Progress.CAPTURE);
        return schemas;
    }

    /**
     * The written schema and the post-synthesis one, which is the written composition with what
     * the anchor minted applied to it.
     */
    private static CapturedSchema schemas(DSLContext dsl, GraphIdentity graph,
                                          AssemblyReading written) {
        if (!(written.composition() instanceof LoadingRewrites.Outcome.Applied composed)) {
            return new CapturedSchema(written, Optional.empty(), List.of());
        }
        var emitted = EmittedRegistry.derive(composed.registry(), new StoreHandle(dsl, graph.name()));
        return new CapturedSchema(written, Optional.of(SchemaAssembly.of(emitted.registry())),
            emitted.narrowings());
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
