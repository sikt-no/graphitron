package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ReflectionError;
import no.sikt.graphitron.model.diagnostics.ReflectionError.ClassUnlinkable.Cause;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consumer class graphitron reflects, one of whose methods names a type the codegen classloader
 * cannot host, resolves to a typed {@link ReflectionError.ClassUnlinkable} rather than a raw
 * {@link LinkageError} escaping the build.
 *
 * <p>The edition-only jOOQ type is staged rather than borrowed from a commercial jar:
 * {@code org.jooq.impl.StagedEditionOnly extends org.jooq.impl.AbstractStore}, written with the
 * ClassFile API into a temp directory that a {@link URLClassLoader} reads with this test's loader
 * as parent. {@code AbstractStore} is package-private and abstract in the open-source jar, so the
 * staged class lands in the same package under another classloader, which is exactly the split
 * the codegen classloader produces over a consumer's {@code ArrayRecordImpl}-derived record.
 */
@UnitTier
class ConsumerClassLinkageTest {

    private static final ClassDesc EDITION_ONLY = ClassDesc.of("org.jooq.impl.StagedEditionOnly");
    private static final ClassDesc TABLE = ClassDesc.of("org.jooq.Table");
    private static final ClassDesc CONDITION = ClassDesc.of("org.jooq.Condition");

    @TempDir
    static Path classes;

    private static URLClassLoader loader;

    @BeforeAll
    static void stage() throws IOException {
        write("org.jooq.impl.StagedEditionOnly", ClassDesc.of("org.jooq.impl.AbstractStore"),
            ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, cb -> {});
        write("com.example.staged.NestedConditions", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC,
            ConsumerClassLinkageTest::byTable);
        write("com.example.staged.NestedConditions$Helpers", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC,
            cb -> nullReturning(cb, "helper", EDITION_ONLY));
        write("com.example.staged.SplitConditions", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC, cb -> {
            cb.withMethodBody("byTable", MethodTypeDesc.of(CONDITION, TABLE),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.aconst_null().areturn());
            nullReturning(cb, "helper", EDITION_ONLY);
        });
        write("com.example.staged.MissingTypeConditions", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC, cb -> {
            byTable(cb);
            nullReturning(cb, "helper", ClassDesc.of("com.example.staged.Absent"));
        });
        loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
            ConsumerClassLinkageTest.class.getClassLoader());
    }

    @AfterAll
    static void close() throws IOException {
        loader.close();
    }

    @Test
    void aConditionClassWhoseSiblingNamesAnEditionOnlyTypeIsRejectedAsASplitPackage() {
        var result = reflect("com.example.staged.SplitConditions");

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection()).isInstanceOfSatisfying(ReflectionError.ClassUnlinkable.class,
            unlinkable -> {
                assertThat(unlinkable.className()).isEqualTo("com.example.staged.SplitConditions");
                assertThat(unlinkable.cause()).isEqualTo(
                    new Cause.SplitPackage("org.jooq.impl.StagedEditionOnly", "org.jooq.impl"));
                assertThat(unlinkable.message())
                    .contains("com.example.staged.SplitConditions")
                    .contains("org.jooq.impl.StagedEditionOnly")
                    .contains("org.jooq:jooq:" + org.jooq.Constants.VERSION)
                    .contains("nested or separate class");
            });
    }

    @Test
    void movingTheHelperIntoANestedClassIsTheWorkaround() {
        assertThat(reflect("com.example.staged.NestedConditions").failed()).isFalse();
    }

    @Test
    void aSiblingNamingATypeAbsentFromTheClasspathIsNotGivenTheJooqRemedy() {
        var result = reflect("com.example.staged.MissingTypeConditions");

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection()).isInstanceOfSatisfying(ReflectionError.ClassUnlinkable.class,
            unlinkable -> {
                assertThat(unlinkable.cause()).isEqualTo(new Cause.TypeMissing("com.example.staged.Absent"));
                assertThat(unlinkable.message())
                    .contains("compile classpath")
                    .doesNotContain("jOOQ");
            });
    }

    @Test
    void aClassThatIsNotThereIsStillNotLoaded() {
        var result = reflect("com.example.staged.Nowhere");

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection()).isInstanceOf(ReflectionError.ClassNotLoaded.class);
    }

    private static ServiceCatalog.ServiceReflectionResult reflect(String className) {
        return newCatalog().reflectTableMethod(className, "byTable",
            new ArgBindingMap(new LinkedHashMap<>(), Set.of()), Set.of());
    }

    private static ServiceCatalog newCatalog() {
        var ctx = new RunContext(List.of(), RunContext.DEFAULT_SCHEMA_FILE_EXTENSIONS, Path.of("."),
            "ConsumerClassLinkageTest", Path.of("."), Path.of("."), "unused", "unused", List.of(), loader);
        return new ServiceCatalog(new BuildContext(null, null, ctx));
    }

    private static void byTable(ClassBuilder cb) {
        cb.withMethodBody("byTable", MethodTypeDesc.of(CONDITION, TABLE),
            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.aconst_null().areturn());
    }

    private static void nullReturning(ClassBuilder cb, String name, ClassDesc returnType) {
        cb.withMethodBody(name, MethodTypeDesc.of(returnType),
            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.aconst_null().areturn());
    }

    private static void write(String binaryName, ClassDesc superclass, int flags,
                              Consumer<ClassBuilder> members) throws IOException {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(binaryName), cb -> {
            cb.withFlags(flags);
            cb.withSuperclass(superclass);
            members.accept(cb);
        });
        Path file = classes.resolve(binaryName.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
