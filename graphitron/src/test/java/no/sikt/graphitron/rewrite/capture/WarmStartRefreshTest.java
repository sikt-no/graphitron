package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a warm store keeps and what it rewrites.
 *
 * <p>The claim the whole mechanism rests on is agreement, not speed: a run that starts from the
 * previous run's rows must end holding exactly what a run that started from nothing would hold. The
 * census anchor states that relation by relation, so a partition that survived when it should not
 * have, or a clear that took something a walk does not put back, fails here rather than in whatever
 * consumer eventually reads the store.
 *
 * <p>Speed is then the reason to bother, and it is stated as the one thing that is allowed to
 * differ: the classes behind an unchanged jar are not written a second time.
 */
@PipelineTier
class WarmStartRefreshTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String, year: Int }
        """;

    @Test
    @DisplayName("a warm run ends with the rows a cold run would have produced")
    void warmAndColdAgreeRelationByRelation(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        var references = referencesOver(tmp, jar);
        Path directory = tmp.resolve("graphitron-model");

        capture(directory, tmp, references);
        capture(directory, tmp, references);

        Map<String, Integer> cold;
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), CapturedStore.registryOf(tmp, SDL), null, references,
                new NodeDeclaration(null));
            cold = census(store.dsl());
        }
        try (var warm = GraphitronModelStore.openAt(directory)) {
            assertThat(census(warm.dsl()))
                .as("relations whose warm row count differs from a cold load's")
                .isEqualTo(cold);
        }
    }

    @Test
    @DisplayName("an unchanged jar's classes are not written a second time")
    void anUnchangedJarIsNotReinserted(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        var references = referencesOver(tmp, jar);
        Path directory = tmp.resolve("graphitron-model");

        capture(directory, tmp, references);
        String first = stampOf(directory, jar);
        assertThat(first).as("a jar the scan read is a jar it stamped").isNotNull();

        // The census is identical whether the partition was retained or re-walked, so the witness
        // has to be something only a rewrite would put back. Marking the row with a value the
        // classfile does not carry is that: it survives a run that left the partition alone and
        // does not survive one that walked it again.
        try (var tampered = GraphitronModelStore.openAt(directory)) {
            tampered.dsl().update(JVM_CLASS).set(JVM_CLASS.CLASS_KIND, "INTERFACE")
                .where(JVM_CLASS.CLASS_NAME.eq("com.example.lib.LibraryClass")).execute();
        }
        capture(directory, tmp, references);

        assertThat(stampOf(directory, jar)).isEqualTo(first);
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(JVM_CLASS.CLASS_KIND).from(JVM_CLASS)
                .where(JVM_CLASS.CLASS_NAME.eq("com.example.lib.LibraryClass"))
                .fetchOne(0, String.class))
                .as("the retained partition is the one the first run wrote, untouched")
                .isEqualTo("INTERFACE");
        }
    }

    @Test
    @DisplayName("a jar whose contents changed is re-walked")
    void aChangedJarIsRewalked(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.Before");
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp, referencesOver(tmp, jar));
        String before = stampOf(directory, jar);

        Files.delete(jar);
        jarWith(tmp, "com.example.lib.After");
        capture(directory, tmp, referencesOver(tmp, jar));

        try (var store = GraphitronModelStore.openAt(directory)) {
            var classes = store.dsl().select(JVM_CLASS.CLASS_NAME).from(JVM_CLASS).fetch(0, String.class);
            assertThat(classes).contains("com.example.lib.After").doesNotContain("com.example.lib.Before");
        }
        assertThat(stampOf(directory, jar)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("a source that left the classpath takes its partition with it")
    void aDepartedSourceIsCleared(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com.example.lib.LibraryClass");
        Path directory = tmp.resolve("graphitron-model");
        capture(directory, tmp, referencesOver(tmp, jar));

        capture(directory, tmp, List.of());

        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().fetchCount(JVM_CLASS)).as("classes from a source nobody named").isZero();
            assertThat(store.dsl().fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_KIND.eq("JAR")))
                .as("the jar's own source row").isZero();
        }
    }

    private static void capture(Path directory, Path scratch,
                                List<CompletionData.ExternalReference> references) {
        FactCapture.run(directory, CapturedStore.registryOf(scratch, SDL), null, references,
            new NodeDeclaration(null));
    }

    /** Row counts per base relation; views hold nothing of their own and are left out. */
    private static Map<String, Integer> census(DSLContext dsl) {
        var counts = new LinkedHashMap<String, Integer>();
        for (Table<?> table : Public.PUBLIC.getTables()) {
            if (table.getOptions().type() != TableOptions.TableType.VIEW) {
                counts.put(table.getName(), dsl.fetchCount(table));
            }
        }
        return counts;
    }

    private static String stampOf(Path directory, Path jar) {
        try (var store = GraphitronModelStore.openAt(directory)) {
            return store.dsl().select(STORE_SOURCE.STAMP).from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_NAME.eq(jar.toString()))
                .fetchOne(0, String.class);
        }
    }

    private static List<CompletionData.ExternalReference> referencesOver(Path basedir, Path... entries) {
        return CatalogBuilder.buildExternalReferences(new RewriteContext(
            List.of(), basedir, basedir.resolve("target/generated"),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, List.of(entries)));
    }

    private static Path jarWith(Path directory, String className) throws IOException {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(className),
            cb -> cb.withFlags(ClassFile.ACC_PUBLIC));
        Path jar = directory.resolve("fixture-library.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(className.replace('.', '/') + ".class"));
            zip.write(bytes);
            zip.closeEntry();
        }
        return jar;
    }
}
