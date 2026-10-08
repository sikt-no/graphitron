package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.diagnostics.ReflectionError;
import no.sikt.graphitron.model.diagnostics.ReflectionError.ClassUnlinkable.Cause;
import no.sikt.graphitron.rewrite.session.SessionHooks;
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
    private static final ClassDesc CONNECTION = ClassDesc.of("java.sql.Connection");
    private static final ClassDesc TABLE = ClassDesc.of("org.jooq.Table");
    private static final ClassDesc CONDITION = ClassDesc.of("org.jooq.Condition");

    @TempDir
    static Path classes;

    private static URLClassLoader loader;

    @BeforeAll
    static void stage() throws IOException {
        write("org.jooq.impl.StagedEditionOnly", ClassDesc.of("org.jooq.impl.AbstractStore"),
            ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, cb -> {});
        write("com.example.staged.SplitMount", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC, cb -> {
            mount(cb);
            nullReturning(cb, "helper", EDITION_ONLY);
        });
        write("com.example.staged.NestedMount", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC,
            ConsumerClassLinkageTest::mount);
        write("com.example.staged.NestedMount$Helpers", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC,
            cb -> nullReturning(cb, "helper", EDITION_ONLY));
        write("com.example.staged.SplitConditions", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC, cb -> {
            cb.withMethodBody("byTable", MethodTypeDesc.of(CONDITION, TABLE),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.aconst_null().areturn());
            nullReturning(cb, "helper", EDITION_ONLY);
        });
        write("com.example.staged.MissingTypeMount", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC, cb -> {
            mount(cb);
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
    void aMountWhoseSiblingNamesAnEditionOnlyTypeIsRejectedAsASplitPackage() {
        var result = resolve("com.example.staged.SplitMount#mount");

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOfSatisfying(ReflectionError.ClassUnlinkable.class, unlinkable -> {
                assertThat(unlinkable.className()).isEqualTo("com.example.staged.SplitMount");
                assertThat(unlinkable.cause()).isEqualTo(
                    new Cause.SplitPackage("org.jooq.impl.StagedEditionOnly", "org.jooq.impl"));
                assertThat(unlinkable.message())
                    .contains("com.example.staged.SplitMount")
                    .contains("org.jooq.impl.StagedEditionOnly")
                    .contains("org.jooq:jooq:" + org.jooq.Constants.VERSION)
                    .contains("nested or separate class");
            });
    }

    @Test
    void movingTheHelperIntoANestedClassIsTheWorkaround() {
        var result = resolve("com.example.staged.NestedMount#mount");

        assertThat(result.rejections()).isEmpty();
        assertThat(result.hooks()).isInstanceOf(SessionHooks.HandleLess.class);
    }

    @Test
    void aConditionClassWithTheSameSiblingIsRejectedTheSameWay() {
        var result = newCatalog().reflectTableMethod("com.example.staged.SplitConditions", "byTable",
            new ArgBindingMap(new LinkedHashMap<>(), Set.of()), Set.of(), Map.of());

        assertThat(result.failed()).isTrue();
        assertThat(result.rejection()).isInstanceOfSatisfying(ReflectionError.ClassUnlinkable.class,
            unlinkable -> assertThat(unlinkable.cause()).isInstanceOf(Cause.SplitPackage.class));
    }

    @Test
    void aSiblingNamingATypeAbsentFromTheClasspathIsNotGivenTheJooqRemedy() {
        var result = resolve("com.example.staged.MissingTypeMount#mount");

        assertThat(result.rejections()).singleElement()
            .isInstanceOfSatisfying(ReflectionError.ClassUnlinkable.class, unlinkable -> {
                assertThat(unlinkable.cause()).isEqualTo(new Cause.TypeMissing("com.example.staged.Absent"));
                assertThat(unlinkable.message())
                    .contains("compile classpath")
                    .doesNotContain("jOOQ");
            });
    }

    @Test
    void aClassThatIsNotThereIsStillNotLoaded() {
        var result = resolve("com.example.staged.Nowhere#mount");

        assertThat(result.rejections()).singleElement().isInstanceOf(ReflectionError.ClassNotLoaded.class);
    }

    private static ServiceCatalog.SessionHookResolution resolve(String mount) {
        return newCatalog().resolveSessionHooks(SessionStateConfig.from(mount, null), null);
    }

    private static ServiceCatalog newCatalog() {
        var ctx = new RunContext(List.of(), RunContext.DEFAULT_SCHEMA_FILE_EXTENSIONS, Path.of("."),
            "ConsumerClassLinkageTest", Path.of("."), Path.of("."), "unused", "unused", List.of(), loader);
        return new ServiceCatalog(new BuildContext(null, null, ctx));
    }

    private static void mount(ClassBuilder cb) {
        cb.withMethodBody("mount", MethodTypeDesc.of(ConstantDescs.CD_void, CONNECTION),
            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.return_());
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
