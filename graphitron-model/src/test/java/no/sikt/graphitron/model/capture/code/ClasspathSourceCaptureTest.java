package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.capture.code.ClasspathSourceCapture.ClasspathSource;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.sources.Observation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the classpath reading opens an entry again. A capture whose classpath has not moved opens
 * nothing, and under a dev session's observation it does not even look: an entry the watcher saw
 * nothing move under is left alone on the stamp the store holds.
 *
 * <p>The cases move a class file's modification time rather than its bytes, because that is what a
 * compile does to build output and what the stamp of a directory is made of.
 */
class ClasspathSourceCaptureTest {

    private static final String GRAPH = "classpath-reading";

    /** A compiled class to stand in for build output; this class's own file serves. */
    private static final String CLASS_FILE =
        ClasspathSourceCaptureTest.class.getName().replace('.', '/') + ".class";

    @Test
    @DisplayName("a second capture of an unchanged directory leaves it unopened")
    void anUnchangedDirectoryIsLeftUnopened(@TempDir Path dir) throws IOException {
        Path entry = entry(dir);
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            assertThat(capture(dsl, dir, entry, null)).as("the first capture reads it")
                .isInstanceOf(ClasspathSource.Read.class);
            assertThat(capture(dsl, dir, entry, null)).as("the second leaves it alone")
                .isInstanceOf(ClasspathSource.Unchanged.class);
        }
    }

    @Test
    @DisplayName("a class file rewritten under a directory has the directory read again")
    void aRewrittenClassIsReadAgain(@TempDir Path dir) throws IOException {
        Path entry = entry(dir);
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            capture(dsl, dir, entry, null);
            touch(entry.resolve(CLASS_FILE));
            assertThat(capture(dsl, dir, entry, null))
                .as("a new modification time is a compile, and the entry is opened")
                .isInstanceOf(ClasspathSource.Read.class);
        }
    }

    /**
     * The watcher's word stands in for the walk. The class file moves without the watcher being
     * told, so a capture that looked would read the entry again; one that trusted the observation
     * leaves it alone, which is the case for not looking. Telling the watcher then gets it read.
     */
    @Test
    @DisplayName("under an observation, an entry nothing was seen to move under is not looked at")
    void anObservedEntryIsNotLookedAt(@TempDir Path dir) throws IOException {
        Path entry = entry(dir);
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            var observation = new Observation(dsl);
            String root = entry.toAbsolutePath().normalize().toString();
            observation.register("classpath", List.of(entry), path -> root);
            observation.observing("classpath");

            capture(dsl, dir, entry, observation);
            touch(entry.resolve(CLASS_FILE));
            assertThat(capture(dsl, dir, entry, observation))
                .as("a change nobody reported is not found, because nothing looked")
                .isInstanceOf(ClasspathSource.Unchanged.class);

            observation.mark(entry.resolve(CLASS_FILE));
            assertThat(capture(dsl, dir, entry, observation))
                .as("reported, the entry is looked at and read")
                .isInstanceOf(ClasspathSource.Read.class);
        }
    }

    /**
     * A stamp computed and found equal is dated, so trust builds from it. Without that a session
     * whose first capture ran before its watcher was up would walk every round.
     */
    @Test
    @DisplayName("an entry verified unchanged is dated, so the next capture under observation trusts it")
    void aVerifiedEntryIsDated(@TempDir Path dir) throws IOException {
        Path entry = entry(dir);
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            capture(dsl, dir, entry, null);
            var observation = new Observation(dsl);
            String root = entry.toAbsolutePath().normalize().toString();
            observation.register("classpath", List.of(entry), path -> root);
            observation.observing("classpath");

            // Read before the watcher was up, so verified by looking.
            assertThat(capture(dsl, dir, entry, observation))
                .isInstanceOf(ClasspathSource.Unchanged.class);
            touch(entry.resolve(CLASS_FILE));
            assertThat(capture(dsl, dir, entry, observation))
                .as("and from then on believed without looking")
                .isInstanceOf(ClasspathSource.Unchanged.class);
            assertThat(dsl.fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_NAME.eq(entry.toString())))
                .isOne();
        }
    }

    /**
     * A repository jar is stamped by what it is, and a release cannot change under its coordinate,
     * so its first stamp stands. A snapshot can be replaced, so the same change reads it again. The
     * jar is rewritten with different bytes to make the difference unmissable.
     */
    @Test
    @DisplayName("a release jar keeps its first stamp, a snapshot jar is stamped each time")
    void aReleaseJarKeepsItsFirstStamp(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("library-1.0.jar");
        writeJar(jar, "first");
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            var release = repositoryJar(jar, ClasspathEntry.Kind.RELEASE);
            capture(dsl, dir, release);
            writeJar(jar, "second, and longer than the first");
            assertThat(capture(dsl, dir, release))
                .as("a release is believed on the stamp it was first read under")
                .isInstanceOf(ClasspathSource.Unchanged.class);
            assertThat(capture(dsl, dir, repositoryJar(jar, ClasspathEntry.Kind.SNAPSHOT)))
                .as("a snapshot is stamped and found to have moved")
                .isInstanceOf(ClasspathSource.Read.class);
        }
    }

    /**
     * A package two entries hold classes under, as sis's generated package does beside a
     * hand-written class in another module. The catalog's stamp covers both, so it is there to
     * compare at all, and a class moving in either entry moves it.
     */
    @Test
    @DisplayName("a package two entries hold is stamped by both, and moves when either does")
    void aPackageTwoEntriesHoldIsStampedByBoth(@TempDir Path dir) throws IOException {
        // Public classes, the census recording no other kind, so no test class of this package will do.
        Path first = dir.resolve("first");
        Path second = dir.resolve("second");
        copyClass(first, ClasspathSourceCapture.class);
        Path otherClass = copyClass(second, CodeCapture.class);
        var classpath = List.of(ClasspathEntry.project(first), ClasspathEntry.project(second));
        String pkg = ClasspathSourceCapture.class.getPackageName();
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            captureAll(dsl, dir, classpath);
            var stamp = ClasspathSourceCapture.stampOfEntriesHolding(dsl, classpath, pkg);
            assertThat(stamp).as("both entries hold the package, and it has one stamp").isPresent();

            captureAll(dsl, dir, classpath);
            assertThat(ClasspathSourceCapture.stampOfEntriesHolding(dsl, classpath, pkg))
                .as("unmoved, the same stamp").isEqualTo(stamp);

            touch(otherClass);
            captureAll(dsl, dir, classpath);
            assertThat(ClasspathSourceCapture.stampOfEntriesHolding(dsl, classpath, pkg))
                .as("a class moved in the second entry moves it").isNotEqualTo(stamp);
        }
    }

    /** Copies a main class's compiled file into {@code entry} at its package path, returning the copy. */
    private static Path copyClass(Path entry, Class<?> type) throws IOException {
        String relative = type.getName().replace('.', '/') + ".class";
        Path target = entry.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(Path.of("target", "classes").resolve(relative), target);
        return target;
    }

    private static void captureAll(DSLContext dsl, Path dir, List<ClasspathEntry> classpath) {
        pause();
        var readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ModelCapture.writeGraph(dsl, new GraphIdentity(GRAPH, dir), readAt);
        ClasspathSourceCapture.capture(dsl, GRAPH, classpath, null, readAt, null);
    }

    /** One capture of {@code entry} for the graph, and what it made of the entry. */
    private static ClasspathSource capture(DSLContext dsl, Path dir, Path entry,
                                           Observation observation) {
        return capture(dsl, dir, ClasspathEntry.project(entry), observation);
    }

    private static ClasspathSource capture(DSLContext dsl, Path dir, ClasspathEntry entry) {
        return capture(dsl, dir, entry, null);
    }

    private static ClasspathSource capture(DSLContext dsl, Path dir, ClasspathEntry entry,
                                           Observation observation) {
        // Strictly after any floor or mark already taken, the observation's comparisons being strict.
        pause();
        var readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ModelCapture.writeGraph(dsl, new GraphIdentity(GRAPH, dir), readAt);
        var reading = ClasspathSourceCapture.capture(dsl, GRAPH, List.of(entry), null, readAt,
            observation);
        return reading.sources().stream()
            .filter(source -> source.sourceName().equals(entry.path().toString()))
            .findFirst().orElseThrow();
    }

    /** A jar a repository holds, as a declared dependency of the given kind; no sidecar identity. */
    private static ClasspathEntry repositoryJar(Path jar, ClasspathEntry.Kind kind) {
        return new ClasspathEntry(jar, ClasspathEntry.Origin.DECLARED, "org.example:library", null,
            kind, null);
    }

    /** A jar holding the stand-in class and one text entry whose content tells two writes apart. */
    private static void writeJar(Path jar, String marker) throws IOException {
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry(CLASS_FILE));
            out.write(Files.readAllBytes(Path.of("target", "test-classes").resolve(CLASS_FILE)));
            out.closeEntry();
            out.putNextEntry(new java.util.jar.JarEntry("marker.txt"));
            out.write(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /** A directory entry holding one compiled class at the path its package names. */
    private static Path entry(Path dir) throws IOException {
        Path entry = dir.resolve("classes");
        Path target = entry.resolve(CLASS_FILE);
        Files.createDirectories(target.getParent());
        Files.copy(Path.of("target", "test-classes").resolve(CLASS_FILE), target);
        return entry;
    }

    /** Moves a file's modification time forward, which is what a compile rewriting it does. */
    private static void touch(Path file) throws IOException {
        Files.setLastModifiedTime(file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2_000));
    }

    private static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
