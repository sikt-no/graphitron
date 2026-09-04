package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.model.classpath.ClasspathCensus;
import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The census held across a session's rounds: what a round re-reads, and that reusing costs the
 * caller nothing in accuracy.
 *
 * <p>Every case here pairs a claim about the work with the equality check against a cold scan,
 * because the risk this cache carries is a stale census rather than a slow one. A round that
 * re-reads nothing and answers wrongly is a worse outcome than the whole-classpath parse it
 * replaced, and it fails in the editor rather than in the build: the counters alone cannot tell
 * those apart, so nothing here asserts a counter without also asserting the answer.
 */
@UnitTier
class ClasspathCensusTest {

    private static final String JOOQ_PACKAGE = "com.example.jooq";

    @Test
    @DisplayName("a round that changes nothing re-reads nothing and answers the same")
    void anUnchangedRoundRereadsNothing(@TempDir Path tmp) throws IOException {
        Path classes = classesWith(tmp, "com.example.own.First", "com.example.own.Second");
        Path jar = jarWith(tmp, "com.example.lib.Library");
        var entries = entriesOver(classes, jar);
        var census = new ClasspathCensus();

        var cold = census.read(entries, JOOQ_PACKAGE);
        assertThat(cold.round().filesRead()).as("the first round parses both class files").isEqualTo(2);
        assertThat(cold.round().jarsRead()).as("the first round parses the jar").isEqualTo(1);

        var warm = census.read(entries, JOOQ_PACKAGE);
        assertThat(warm.round().reusedEverything())
            .as("nothing on disk moved, so nothing is re-read: %s", warm.round().report()).isTrue();
        assertThat(warm.round().filesReused()).isEqualTo(2);
        assertThat(warm.round().jarsReused()).isEqualTo(1);
        assertThat(classNames(warm)).isEqualTo(classNames(cold));
        assertThat(warm.references()).isEqualTo(coldScan(entries));
    }

    /**
     * The one-file recompile, which is the case the dev loop meets on every {@code .java} save. The
     * count is the point: a directory re-read whole would report two here, and the loop would be
     * paying for the file the compiler did not touch.
     */
    @Test
    @DisplayName("a recompiled class file is re-parsed and its neighbour is not")
    void oneRecompiledFileIsRereadAlone(@TempDir Path tmp) throws IOException {
        Path classes = classesWith(tmp, "com.example.own.First", "com.example.own.Second");
        Path jar = jarWith(tmp, "com.example.lib.Library");
        var entries = entriesOver(classes, jar);
        var census = new ClasspathCensus();
        census.read(entries, JOOQ_PACKAGE);

        // The recompile adds a method, so a census that reused the stale parse answers wrongly
        // rather than merely answering late. Rewriting the identical bytes would leave the counters
        // as the only witness, and a counter is not the risk this cache carries.
        recompile(classes, "com.example.own.First");

        var round = census.read(entries, JOOQ_PACKAGE);
        assertThat(round.round().filesRead())
            .as("only the rewritten file: %s", round.round().report()).isEqualTo(1);
        assertThat(round.round().filesReused()).isEqualTo(1);
        assertThat(round.round().jarsRead()).as("a class file save leaves the jars alone").isZero();
        assertThat(methodNames(round, "com.example.own.First"))
            .as("the re-parse is of the new bytes, not a replay of the old ones")
            .contains("addedByRecompile");
        assertThat(round.references()).isEqualTo(coldScan(entries));
    }

    /**
     * A class the compiler stopped emitting has to leave the census. This is the failure a cache
     * keyed on "what changed" makes rather than fixes: nothing about the remaining files says the
     * deleted one is gone, so the entry is rebuilt from the walk instead of merged into the old map.
     */
    @Test
    @DisplayName("a deleted class file leaves the census")
    void aDeletedClassFileLeavesTheCensus(@TempDir Path tmp) throws IOException {
        Path classes = classesWith(tmp, "com.example.own.First", "com.example.own.Second");
        var entries = entriesOver(classes);
        var census = new ClasspathCensus();
        census.read(entries, JOOQ_PACKAGE);

        Files.delete(classFile(classes, "com.example.own.Second"));

        var round = census.read(entries, JOOQ_PACKAGE);
        assertThat(classNames(round))
            .as("the deleted class is not in the census").containsExactly("com.example.own.First");
        assertThat(round.references()).isEqualTo(coldScan(entries));
    }

    /**
     * A jar replaced under a live session, which is a sibling project installed from another
     * checkout. Its detector is a content hash rather than a stat, so the case that matters is a
     * rewrite the size and modification time could miss.
     */
    @Test
    @DisplayName("a rewritten jar is re-read and its new class reaches the census")
    void aRewrittenJarIsReread(@TempDir Path tmp) throws IOException {
        Path classes = classesWith(tmp, "com.example.own.First");
        Path jar = jarWith(tmp, "com.example.lib.Library");
        var entries = entriesOver(classes, jar);
        var census = new ClasspathCensus();
        census.read(entries, JOOQ_PACKAGE);

        writeJar(jar, "com.example.lib.Replacement");

        var round = census.read(entries, JOOQ_PACKAGE);
        assertThat(round.round().jarsRead())
            .as("the hash moved, so the jar is re-read: %s", round.round().report()).isEqualTo(1);
        assertThat(round.round().filesRead()).as("no class file changed").isZero();
        assertThat(classNames(round)).contains("com.example.lib.Replacement")
            .doesNotContain("com.example.lib.Library");
        assertThat(round.references()).isEqualTo(coldScan(entries));
    }

    /**
     * The jOOQ package decides which classes the parse admits, so it invalidates the whole cache
     * rather than one entry. A cache that held its answer across a change here would answer for a
     * classpath the caller no longer described.
     */
    @Test
    @DisplayName("a different jOOQ package re-reads everything")
    void aChangedJooqPackageRereadsEverything(@TempDir Path tmp) throws IOException {
        Path classes = classesWith(tmp, "com.example.own.First");
        var entries = entriesOver(classes);
        var census = new ClasspathCensus();
        census.read(entries, JOOQ_PACKAGE);

        var round = census.read(entries, "com.example.other");
        assertThat(round.round().filesRead())
            .as("the parse's own input changed: %s", round.round().report()).isEqualTo(1);
        assertThat(round.references()).isEqualTo(ClasspathScanner.scan(entries, "com.example.other"));
    }

    /**
     * A held census must not answer for an entry the caller stopped naming, and must not keep
     * paying for it either. The pre-compile state is the same shape: an entry that does not exist
     * yet costs an existence check and starts being read the round it appears.
     */
    @Test
    @DisplayName("an entry that leaves the classpath leaves the census")
    void anEntryOffTheClasspathLeavesTheCensus(@TempDir Path tmp) throws IOException {
        Path classes = classesWith(tmp, "com.example.own.First");
        Path jar = jarWith(tmp, "com.example.lib.Library");
        var census = new ClasspathCensus();
        census.read(entriesOver(classes, jar), JOOQ_PACKAGE);

        var narrowed = entriesOver(classes);
        var round = census.read(narrowed, JOOQ_PACKAGE);
        assertThat(classNames(round)).containsExactly("com.example.own.First");
        assertThat(round.references()).isEqualTo(coldScan(narrowed));

        // And the jar comes back as a cold read rather than out of a cache that outlived its entry.
        var restored = census.read(entriesOver(classes, jar), JOOQ_PACKAGE);
        assertThat(restored.round().jarsRead()).isEqualTo(1);
        assertThat(restored.references()).isEqualTo(coldScan(entriesOver(classes, jar)));
    }

    /**
     * A {@code TRANSITIVE} entry is not read, held or counted, exactly as the cold scan skips it
     * before opening it. The cache must not become the thing that quietly widens the census.
     */
    @Test
    @DisplayName("a transitive entry is skipped, as the cold scan skips it")
    void aTransitiveEntryIsSkipped(@TempDir Path tmp) throws IOException {
        Path classes = classesWith(tmp, "com.example.own.First");
        Path jar = jarWith(tmp, "com.example.lib.Library");
        var entries = List.of(
            ClasspathEntry.project(classes),
            new ClasspathEntry(jar, ClasspathEntry.Origin.TRANSITIVE, "com.example:transitive"));
        var census = new ClasspathCensus();

        var round = census.read(entries, JOOQ_PACKAGE);
        assertThat(round.round().jarsRead()).isZero();
        assertThat(round.round().jarsReused()).isZero();
        assertThat(classNames(round)).containsExactly("com.example.own.First");
        assertThat(round.references()).isEqualTo(coldScan(entries));
    }

    /** The census a cold scan of the same entries produces: the answer every reuse must match. */
    private static List<CompletionData.ExternalReference> coldScan(List<ClasspathEntry> entries) {
        return ClasspathScanner.scan(entries, JOOQ_PACKAGE);
    }

    private static List<String> classNames(ClasspathCensus.Reading reading) {
        return reading.references().stream().map(CompletionData.ExternalReference::className).toList();
    }

    private static List<ClasspathEntry> entriesOver(Path... entries) {
        return java.util.Arrays.stream(entries)
            .map(entry -> ClasspathScanner.isJar(entry)
                ? new ClasspathEntry(entry, ClasspathEntry.Origin.DECLARED, "com.example:library")
                : ClasspathEntry.project(entry))
            .toList();
    }

    private static Path classesWith(Path tmp, String... classNames) throws IOException {
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        for (String className : classNames) {
            writeClass(classes, className);
        }
        return classes;
    }

    /**
     * Rewrites a class the way a compiler would, and moves its modification time far enough that
     * the stat sees it. A recompile in the same filesystem timestamp tick is the heuristic's known
     * blind spot, disclosed on {@link ClasspathCensus}; pinning the detector means not sitting in
     * it, and a real save is separated from the previous one by a developer's keystroke.
     */
    private static void recompile(Path classes, String className) throws IOException {
        Path file = classFile(classes, className);
        Files.write(file, classBytes(className, true));
        Files.setLastModifiedTime(file,
            java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5_000L));
    }

    private static void writeClass(Path classes, String className) throws IOException {
        Path file = classFile(classes, className);
        Files.createDirectories(file.getParent());
        Files.write(file, classBytes(className, false));
    }

    /**
     * A public class, optionally carrying the method a recompile adds. The added method is what
     * makes a stale reuse visible in the census rather than only in the counters.
     */
    private static byte[] classBytes(String className, boolean recompiled) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            if (recompiled) {
                cb.withMethodBody("addedByRecompile",
                    java.lang.constant.MethodTypeDesc.of(ClassDesc.ofDescriptor("V")),
                    ClassFile.ACC_PUBLIC, code -> code.return_());
            }
        });
    }

    private static List<String> methodNames(ClasspathCensus.Reading reading, String className) {
        return reading.references().stream()
            .filter(reference -> className.equals(reference.className()))
            .flatMap(reference -> reference.methods().stream())
            .map(CompletionData.Method::name)
            .toList();
    }

    private static Path classFile(Path classes, String className) {
        return classes.resolve(className.replace('.', '/') + ".class");
    }

    private static Path jarWith(Path tmp, String className) throws IOException {
        Path jar = tmp.resolve("fixture-library.jar");
        writeJar(jar, className);
        return jar;
    }

    private static void writeJar(Path jar, String className) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(className.replace('.', '/') + ".class"));
            zip.write(ClassFile.of().build(ClassDesc.of(className),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC)));
            zip.closeEntry();
        }
    }
}
