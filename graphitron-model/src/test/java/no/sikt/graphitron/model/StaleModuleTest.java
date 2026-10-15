package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.store.StoreEntries;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.WorkspaceModule;
import no.sikt.graphitron.model.run.SubjectConfig;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.BUILD_WARNING_STALE_MODULE;
import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;
import static no.sikt.graphitron.model.Tables.JAVA_SOURCE_ROOT;
import static no.sikt.graphitron.model.Tables.STORE_CLASS_FILE;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which workspace modules a run read stale: one read from an installed copy for want of output, and
 * one whose own output is older than its newest source. A module compiled since its last edit is
 * not one.
 */
class StaleModuleTest {

    private static final String GRAPH = "g";
    private static final LocalDateTime READ = LocalDateTime.of(2026, 10, 14, 12, 0);
    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 7, 23, 9, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 10, 5, 9, 0);

    @Test
    @DisplayName("an installed copy and an output older than its sources are stale, a fresh compile is not")
    void staleModulesAreTheOnesReadStale() {
        withSeededStore(dsl -> {
            seedGraph(dsl, GRAPH);
            var current = module("current");
            var edited = module("edited");
            var installed = module("installed");
            StoreEntries.write(dsl, GRAPH, SubjectConfig.none(), List.of(
                entry(current.classesDirectory(), current),
                entry(edited.classesDirectory(), edited),
                entry(Path.of("/repo/installed-1.0-SNAPSHOT.jar"), installed)), READ);

            classes(dsl, current.classesDirectory(), LATER);
            sources(dsl, current, EARLIER);
            classes(dsl, edited.classesDirectory(), EARLIER);
            sources(dsl, edited, LATER);
            classes(dsl, Path.of("/repo/installed-1.0-SNAPSHOT.jar"), EARLIER);

            var w = BUILD_WARNING_STALE_MODULE;
            assertThat(dsl.select(w.COORDINATE, w.REASON, w.SOURCES_MODIFIED, w.OUTPUT_MODIFIED)
                .from(w).where(w.GRAPH_NAME.eq(GRAPH)).orderBy(w.COORDINATE)
                .fetch(r -> List.of(r.value1(), r.value2(), String.valueOf(r.value3()),
                    String.valueOf(r.value4()))))
                .containsExactly(
                    List.of("ex:edited", "SOURCES_NEWER", LATER.toString(), EARLIER.toString()),
                    List.of("ex:installed", "NO_OUTPUT", "null", EARLIER.toString()));

            assertThat(dsl.select(DIAGNOSTIC.SOURCE, DIAGNOSTIC.SEVERITY, DIAGNOSTIC.MESSAGE)
                .from(DIAGNOSTIC).where(DIAGNOSTIC.GRAPH_NAME.eq(GRAPH))
                .and(DIAGNOSTIC.SOURCE.eq("classpath")).orderBy(DIAGNOSTIC.MESSAGE)
                .fetch(r -> List.of(r.value1(), r.value2(), r.value3())))
                .as("one sentence per module, naming what is old and the command that compiles it")
                .containsExactly(
                    List.of("classpath", "warning", "ex:edited was last compiled 2026-07-23 09:00 but"
                        + " its sources changed 2026-10-05 09:00, so anything added to it since reads"
                        + " as not found. Compile it from the reactor root: mvn -pl :edited compile"),
                    List.of("classpath", "warning", "ex:installed has no compiled output, so its"
                        + " installed copy from 2026-07-23 09:00 was read in its place, so anything"
                        + " added to it since reads as not found. Compile it from the reactor root:"
                        + " mvn -pl :installed compile"));
        });
    }

    private static WorkspaceModule module(String name) {
        return WorkspaceModule.conventional("ex:" + name, Path.of("/w/" + name));
    }

    private static ClasspathEntry entry(Path path, WorkspaceModule module) {
        return new ClasspathEntry(path, ClasspathEntry.Origin.REACTOR, module.coordinate(), null,
            ClasspathEntry.Kind.unresolved(path), module);
    }

    /** One class file under {@code entry}, written at {@code at}. */
    private static void classes(DSLContext dsl, Path entry, LocalDateTime at) {
        seedSource(dsl, entry.toString(), entry.toString().endsWith(".jar") ? "JAR" : "DIRECTORY");
        dsl.insertInto(STORE_CLASS_FILE)
            .set(STORE_CLASS_FILE.SOURCE_NAME, entry.toString())
            .set(STORE_CLASS_FILE.FILE_PATH, "ex/A.class")
            .set(STORE_CLASS_FILE.MTIME, at)
            .set(STORE_CLASS_FILE.TOUCHED_AT, READ)
            .execute();
    }

    /** The module's src/main/java, its newest file saved at {@code at}. */
    private static void sources(DSLContext dsl, WorkspaceModule module, LocalDateTime at) {
        dsl.insertInto(JAVA_SOURCE_ROOT)
            .set(JAVA_SOURCE_ROOT.SOURCE_ROOT, module.baseDirectory().resolve("src/main/java").toString())
            .set(JAVA_SOURCE_ROOT.NEWEST_MODIFIED, at)
            .set(JAVA_SOURCE_ROOT.READ_AT, READ)
            .execute();
    }
}
