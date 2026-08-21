package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
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

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported bug, at the boundary where it lived:
 * {@code scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")} generated
 * fine and red-squiggled in the editor, because codegen resolved the constant through a loader over
 * the whole compile classpath while the catalog scan opened only compile-output directories. The
 * two now read one list, so a class in a jar is a class the census holds and the unknown-class
 * detection has nothing to report.
 *
 * <p>Pinned against a fixture jar rather than a real dependency: the claim is about the census
 * reading jars at all, and a fixture says so without tying the test to what happens to be on the
 * reactor's own classpath.
 */
@PipelineTier
class JarResidentClassCensusTest {

    /** JVM field descriptor of {@code graphql.schema.GraphQLScalarType}, the scan's exact match. */
    private static final ClassDesc GRAPHQL_SCALAR_TYPE = ClassDesc.of("graphql.schema.GraphQLScalarType");

    @Test
    void aJarResidentClassAndItsScalarFieldReachTheCensus(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com/example/lib/LibraryScalars.class",
            ClassFile.of().build(ClassDesc.of("com.example.lib.LibraryScalars"), cb -> {
                cb.withFlags(ClassFile.ACC_PUBLIC);
                cb.withField("Date", GRAPHQL_SCALAR_TYPE,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
            }));

        var references = CatalogBuilder.buildExternalReferences(contextOver(tmp, jar));

        var libraryClass = references.stream()
            .filter(reference -> "com.example.lib.LibraryScalars".equals(reference.className()))
            .findFirst();
        assertThat(libraryClass).as("a jar on the compile classpath is scanned").isPresent();
        assertThat(libraryClass.get().scalarConstants())
            .extracting(CompletionData.ScalarConstant::fieldName)
            .containsExactly("Date");
        assertThat(libraryClass.get().sourceName()).isEqualTo(jar.toString());
        assertThat(libraryClass.get().fromJar()).isTrue();
    }

    /**
     * The ordering the widened census owes the editor: a class the consumer compiled ranks ahead of
     * one from a jar. Ordering, never filtering, so the jar class is still in the list.
     */
    @Test
    void reactorClassesRankAheadOfJarClasses(@TempDir Path tmp) throws IOException {
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        Path own = classes.resolve("com/example/own/OwnService.class");
        Files.createDirectories(own.getParent());
        Files.write(own, ClassFile.of().build(ClassDesc.of("com.example.own.OwnService"),
            cb -> cb.withFlags(ClassFile.ACC_PUBLIC)));
        Path jar = jarWith(tmp, "com/example/lib/LibraryClass.class",
            ClassFile.of().build(ClassDesc.of("com.example.lib.LibraryClass"),
                cb -> cb.withFlags(ClassFile.ACC_PUBLIC)));

        // Jar first on the classpath, so a list that came out reactor-first was ranked, not walked.
        var references = CatalogBuilder.buildExternalReferences(contextOver(tmp, jar, classes));
        var fromJar = references.stream().map(CompletionData.ExternalReference::fromJar).toList();

        assertThat(references).extracting(CompletionData.ExternalReference::className)
            .contains("com.example.own.OwnService", "com.example.lib.LibraryClass");
        assertThat(fromJar).as("the scan reports which entries are jars").contains(true, false);
    }

    private static RewriteContext contextOver(Path basedir, Path... entries) {
        // The jar is classified DECLARED, the way a real consumer's library arrives: this test's
        // subject (a jar-resident class reaches the census) is unchanged by the transitive cut,
        // because the cut skips only TRANSITIVE entries.
        var classified = java.util.Arrays.stream(entries)
            .map(entry -> no.sikt.graphitron.rewrite.catalog.ClasspathScanner.isJar(entry)
                ? new no.sikt.graphitron.rewrite.ClasspathEntry(entry,
                    no.sikt.graphitron.rewrite.ClasspathEntry.Origin.DECLARED,
                    "com.example:fixture-library")
                : no.sikt.graphitron.rewrite.ClasspathEntry.project(entry))
            .toList();
        var inputs = java.util.List.<no.sikt.graphitron.rewrite.schema.input.SchemaInput>of();
        return new RewriteContext(
            inputs, basedir, "JarResidentClassCensusTest", basedir.resolve("target/generated"),
            basedir.resolve("target/generated-resources"),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, classified,
            Thread.currentThread().getContextClassLoader(), java.util.List.of(),
            null, null, null, null, null,
            no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.literalOver(
                inputs, RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS),
            null);
    }

    private static Path jarWith(Path directory, String entryName, byte[] bytes) throws IOException {
        Path jar = directory.resolve("fixture-library.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(bytes);
            zip.closeEntry();
        }
        return jar;
    }
}
