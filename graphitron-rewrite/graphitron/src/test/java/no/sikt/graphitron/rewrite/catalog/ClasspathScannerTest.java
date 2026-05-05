package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@link ClasspathScanner}: synthesises minimal {@code .class}
 * files under a temp {@code target/classes} layout and asserts the scanner's
 * filter set (public top-level non-synthetic, not under jOOQ package, not
 * {@code module-info} / {@code package-info} / inner-class).
 */
@UnitTier
class ClasspathScannerTest {

    private static final String JOOQ_PKG = "no.sikt.graphitron.rewrite.test.jooq";

    @Test
    void emptyWhenClassesDirectoryIsAbsent(@TempDir Path basedir) {
        var refs = ClasspathScanner.scan(basedir.resolve("target/classes"), JOOQ_PKG);
        assertThat(refs).isEmpty();
    }

    @Test
    void picksUpPublicTopLevelClasses(@TempDir Path classes) throws IOException {
        writePublicClass(classes, "com.example.MyService");
        writePublicClass(classes, "com.example.OtherService");

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).extracting(CompletionData.ExternalReference::className)
            .containsExactlyInAnyOrder("com.example.MyService", "com.example.OtherService");
    }

    @Test
    void excludesNonPublicClasses(@TempDir Path classes) throws IOException {
        writePublicClass(classes, "com.example.Public");
        writeClass(classes, "com.example.PackagePrivate", 0); // no ACC_PUBLIC

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).extracting(CompletionData.ExternalReference::className)
            .containsExactly("com.example.Public");
    }

    @Test
    void excludesClassesUnderJooqPackage(@TempDir Path classes) throws IOException {
        writePublicClass(classes, "com.example.MyService");
        writePublicClass(classes, JOOQ_PKG + ".tables.Film");

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).extracting(CompletionData.ExternalReference::className)
            .containsExactly("com.example.MyService");
    }

    @Test
    void excludesNestedAndPackageOrModuleInfo(@TempDir Path classes) throws IOException {
        writePublicClass(classes, "com.example.Public");
        // Nested / package-info / module-info classes are filename-filtered
        // before any classfile parse, so the contents can be arbitrary bytes
        // for the purposes of this test.
        writeRawBytes(classes, "com/example/Outer$Inner.class", new byte[]{1, 2, 3});
        writeRawBytes(classes, "com/example/package-info.class", new byte[]{4, 5});
        writeRawBytes(classes, "module-info.class", new byte[]{6, 7});

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).extracting(CompletionData.ExternalReference::className)
            .containsExactly("com.example.Public");
    }

    @Test
    void populatesMethodsWithErasedParameterTypes(@TempDir Path classes) throws IOException {
        // An interface so we can declare abstract methods with no body.
        var fqn = "com.example.MyService";
        var stringDesc = ClassDesc.of("java.lang.String");
        var integerDesc = ClassDesc.of("java.lang.Integer");
        byte[] bytes = ClassFile.of().build(ClassDesc.of(fqn), cb -> cb
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)
            .withMethod(
                "list",
                java.lang.constant.MethodTypeDesc.of(stringDesc, integerDesc),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                mb -> {}
            )
            .withMethod(
                "noArgs",
                java.lang.constant.MethodTypeDesc.of(stringDesc),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                mb -> {}
            )
        );
        writeRawBytes(classes, "com/example/MyService.class", bytes);

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).hasSize(1);
        var ref = refs.get(0);
        assertThat(ref.className()).isEqualTo(fqn);
        assertThat(ref.methods()).extracting(CompletionData.Method::name)
            .containsExactlyInAnyOrder("list", "noArgs");
        var list = ref.methods().stream().filter(m -> m.name().equals("list")).findFirst().orElseThrow();
        assertThat(list.returnType()).isEqualTo("String");
        assertThat(list.parameters()).hasSize(1);
        assertThat(list.parameters().get(0).type()).isEqualTo("Integer");
        // No -parameters attribute on the synthesised classfile, so names
        // fall back to argN.
        assertThat(list.parameters().get(0).name()).isEqualTo("arg0");
    }

    @Test
    void skipsConstructorAndClassInitMethods(@TempDir Path classes) throws IOException {
        writePublicClass(classes, "com.example.Plain");

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        // The default classfile build emits a synthetic constructor; it
        // should not surface as a method candidate.
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).methods()).extracting(CompletionData.Method::name)
            .doesNotContain("<init>", "<clinit>");
    }

    @Test
    void skipsNonClassBytesGracefully(@TempDir Path classes) throws IOException {
        writePublicClass(classes, "com.example.Real");
        // A file ending in `.class` that isn't a valid classfile should not
        // crash the scan; we just skip it.
        Path bogus = classes.resolve("com/example/Bogus.class");
        Files.createDirectories(bogus.getParent());
        Files.write(bogus, new byte[]{0, 1, 2, 3, 4});

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).extracting(CompletionData.ExternalReference::className)
            .containsExactly("com.example.Real");
    }

    private static void writePublicClass(Path classes, String fqn) throws IOException {
        writeClass(classes, fqn, ClassFile.ACC_PUBLIC);
    }

    private static void writeClass(Path classes, String fqn, int accessFlags) throws IOException {
        writeRawClassBytes(classes, fqn, classBytes(fqn, accessFlags));
    }

    private static void writeRawClassBytes(Path classes, String fqn, byte[] bytes) throws IOException {
        Path target = classes.resolve(fqn.replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static void writeRawBytes(Path classes, String relPath, byte[] bytes) throws IOException {
        Path target = classes.resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] classBytes(String fqn, int accessFlags) {
        return ClassFile.of().build(ClassDesc.of(fqn), cb -> cb.withFlags(accessFlags));
    }
}
