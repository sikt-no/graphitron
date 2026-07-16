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
    void walksMultipleRootsLikeReactorModules(@TempDir Path moduleA, @TempDir Path moduleB) throws IOException {
        // Simulate a reactor: schema module's own classes plus a sibling
        // service module's classes. The LSP catalog needs both visible so
        // @service(service: {className: "..."}) lit up against either.
        writePublicClass(moduleA, "no.sikt.example.schema.SchemaSupport");
        writePublicClass(moduleB, "no.sikt.example.service.SampleQueryService");
        writePublicClass(moduleB, "no.sikt.example.service.CategoryConditions");

        var refs = ClasspathScanner.scan(java.util.List.of(moduleA, moduleB), JOOQ_PKG);

        assertThat(refs).extracting(CompletionData.ExternalReference::className)
            .containsExactlyInAnyOrder(
                "no.sikt.example.schema.SchemaSupport",
                "no.sikt.example.service.SampleQueryService",
                "no.sikt.example.service.CategoryConditions"
            );
    }

    @Test
    void deduplicatesSameClassFromOverlappingRoots(@TempDir Path moduleA, @TempDir Path moduleB) throws IOException {
        // Possible (rare) when reactor configurations overlap: same FQN
        // compiled into more than one output dir. The scanner must surface
        // it once.
        writePublicClass(moduleA, "no.sikt.example.SharedClass");
        writePublicClass(moduleB, "no.sikt.example.SharedClass");

        var refs = ClasspathScanner.scan(java.util.List.of(moduleA, moduleB), JOOQ_PKG);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).className()).isEqualTo("no.sikt.example.SharedClass");
    }

    @Test
    void skipsMissingRootsWithoutFailing(@TempDir Path real) throws IOException {
        writePublicClass(real, "com.example.Real");
        Path missing = real.resolveSibling("does-not-exist");

        var refs = ClasspathScanner.scan(java.util.List.of(missing, real), JOOQ_PKG);

        assertThat(refs).extracting(CompletionData.ExternalReference::className)
            .containsExactly("com.example.Real");
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
        // No -parameters attribute on the synthesised classfile; the
        // contract on Parameter.name says null in that case (the Phase
        // 5c diagnostic uses the null as its detection signal).
        assertThat(list.parameters().get(0).name()).isNull();
    }

    @Test
    void classifiesJooqConditionReturnExactlyByFqn(@TempDir Path classes) throws IOException {
        // ReturnsCondition is set only for a method whose un-erased return type is exactly
        // org.jooq.Condition. A consumer's own type named Condition (here com.example.Condition)
        // must NOT be mis-tagged — that is the false positive the FQN lift defends against, which a
        // simple-name match on the erased "Condition" display name would fail.
        var fqn = "com.example.MyService";
        var realCondition = ClassDesc.of("org.jooq.Condition");
        var fakeCondition = ClassDesc.of("com.example.Condition");
        var stringDesc = ClassDesc.of("java.lang.String");
        byte[] bytes = ClassFile.of().build(ClassDesc.of(fqn), cb -> cb
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)
            .withMethod("realCondition", java.lang.constant.MethodTypeDesc.of(realCondition),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, mb -> {})
            .withMethod("fakeCondition", java.lang.constant.MethodTypeDesc.of(fakeCondition),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, mb -> {})
            .withMethod("plain", java.lang.constant.MethodTypeDesc.of(stringDesc),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, mb -> {}));
        writeRawClassBytes(classes, fqn, bytes);

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).hasSize(1);
        var methods = refs.get(0).methods();
        // Both the real jOOQ Condition and the consumer's own Condition erase to the simple display
        // name "Condition", so returnType alone cannot tell them apart.
        assertThat(methods).filteredOn(m -> m.name().equals("realCondition")).singleElement()
            .satisfies(m -> {
                assertThat(m.returnType()).isEqualTo("Condition");
                assertThat(m.returnsCondition()).isTrue();
            });
        assertThat(methods).filteredOn(m -> m.name().equals("fakeCondition")).singleElement()
            .satisfies(m -> {
                assertThat(m.returnType()).isEqualTo("Condition");
                assertThat(m.returnsCondition()).isFalse();
            });
        assertThat(methods).filteredOn(m -> m.name().equals("plain")).singleElement()
            .satisfies(m -> assertThat(m.returnsCondition()).isFalse());
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
    void populatesRecordComponentsForRecordClasses(@TempDir Path classes) throws IOException {
        // A Java record class carries its component list in the Record
        // attribute on the class file. The scanner reads it without
        // re-classifying — that's the seam the R157 snapshot projection
        // consumes for RecordBacking dispatch.
        var fqn = "com.example.FilmCard";
        var stringDesc = ClassDesc.of("java.lang.String");
        var integerDesc = ClassDesc.of("java.lang.Integer");
        byte[] bytes = ClassFile.of().build(ClassDesc.of(fqn), cb -> cb
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
            .withSuperclass(ClassDesc.of("java.lang.Record"))
            .with(java.lang.classfile.attribute.RecordAttribute.of(java.util.List.of(
                java.lang.classfile.attribute.RecordComponentInfo.of(
                    "filmId", integerDesc
                ),
                java.lang.classfile.attribute.RecordComponentInfo.of(
                    "title", stringDesc
                )
            )))
        );
        writeRawClassBytes(classes, fqn, bytes);

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).recordComponents())
            .extracting(CompletionData.RecordComponent::name, CompletionData.RecordComponent::displayType)
            .containsExactly(
                org.assertj.core.api.Assertions.tuple("filmId", "Integer"),
                org.assertj.core.api.Assertions.tuple("title", "String")
            );
    }

    @Test
    void plainClassLeavesRecordComponentsEmpty(@TempDir Path classes) throws IOException {
        writePublicClass(classes, "com.example.Plain");

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).recordComponents()).isEmpty();
    }

    @Test
    void populatesScalarConstantsForPublicStaticGraphQLScalarTypeFields(@TempDir Path classes) throws IOException {
        // @scalarType completion is fed by the public static GraphQLScalarType fields
        // scanned off the classpath. Only fields whose exact type descriptor is
        // graphql.schema.GraphQLScalarType and that are public + static surface; a differently
        // typed static field, a non-static field, and a non-public field are all ignored.
        var fqn = "com.example.Scalars";
        var scalarDesc = ClassDesc.of("graphql.schema.GraphQLScalarType");
        var stringDesc = ClassDesc.of("java.lang.String");
        int pubStaticFinal = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL;
        byte[] bytes = ClassFile.of().build(ClassDesc.of(fqn), cb -> cb
            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
            .withField("MONEY", scalarDesc, pubStaticFinal)
            .withField("NOT_A_SCALAR", stringDesc, pubStaticFinal)                                 // wrong field type
            .withField("INSTANCE_SCALAR", scalarDesc, ClassFile.ACC_PUBLIC)                         // not static
            .withField("PRIVATE_SCALAR", scalarDesc, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC)  // not public
        );
        writeRawClassBytes(classes, fqn, bytes);

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).scalarConstants())
            .extracting(CompletionData.ScalarConstant::fieldName)
            .containsExactly("MONEY");
    }

    @Test
    void plainClassLeavesScalarConstantsEmpty(@TempDir Path classes) throws IOException {
        writePublicClass(classes, "com.example.Plain");

        var refs = ClasspathScanner.scan(classes, JOOQ_PKG);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).scalarConstants()).isEmpty();
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
