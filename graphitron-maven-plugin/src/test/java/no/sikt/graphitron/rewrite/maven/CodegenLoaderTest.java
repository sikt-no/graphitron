package no.sikt.graphitron.rewrite.maven;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pipeline-tier coverage for the codegen loader: the Mojo's codegen scope builds a {@link ClassLoader}
 * that resolves classes from the project's compile classpath (declared {@code <dependency>}
 * entries plus the consumer's own {@code target/classes}), not just from the plugin's own
 * realm. Previously the generator's reflection path went through the plugin loader, which
 * forced consumers to mirror service / catalog jars under {@code <plugin><dependencies>};
 * the new loader makes that block unnecessary.
 *
 * <p>The test stages a service class as a real {@code .class} file under a fake
 * {@code target/classes} directory, hands its path to a {@link MavenProject} via
 * {@code getCompileClasspathElements()}, and asserts:
 *
 * <ol>
 *   <li>Inside {@link AbstractRewriteMojo#withCodegenScope}, the {@link ClassLoader}
 *       resolves the staged class.</li>
 *   <li>Outside the scope, the plugin's own loader does not see it — the staging directory
 *       is genuinely off the test JVM's classpath.</li>
 *   <li>After the scope returns, the thread's context classloader is restored.</li>
 * </ol>
 *
 * <p>The 22 in-process {@code Class.forName(name, false, ctx.codegenLoader())} sites all
 * resolve through this same loader; per-site coverage is the IT's job (basic-generate
 * locks the contract for the reflection path the schema exercises) and
 * graphitron-sakila-example's compile/execute tiers.
 */
class CodegenLoaderTest {

    /**
     * Tiny synthetic class file: {@code public class no.example.service.MarkerService {}}.
     * Encoded as a Java 17 class (major version 61). Generated once at build time via
     * {@code javac} and frozen as a byte array so the test does not need a compiler on its
     * own classpath.
     */
    private static final String MARKER_CLASS_NAME = "no.example.service.MarkerService";

    @Test
    void codegenLoader_resolvesClassFromProjectCompileClasspath(@TempDir Path basedir) throws Exception {
        Path stagedClasses = writeMarkerClass(basedir.resolve("staged-classes"));

        var mojo = mojo(basedir, stagedClasses);

        // Outside the scope: the staged class is not on the test JVM's classpath.
        assertThatThrownBy(() -> Class.forName(MARKER_CLASS_NAME,
                false, CodegenLoaderTest.class.getClassLoader()))
            .isInstanceOf(ClassNotFoundException.class);

        var captured = new AtomicReference<Class<?>>();
        var capturedTcclInsideScope = new AtomicReference<ClassLoader>();
        var tcclBefore = Thread.currentThread().getContextClassLoader();

        mojo.withCodegenScope(ctx -> {
            try {
                captured.set(Class.forName(MARKER_CLASS_NAME, false, ctx.codegenLoader()));
            } catch (ClassNotFoundException e) {
                throw new AssertionError(
                    "Expected codegenLoader to resolve " + MARKER_CLASS_NAME
                        + " from the staged compile classpath, but the resolution failed.", e);
            }
            capturedTcclInsideScope.set(Thread.currentThread().getContextClassLoader());
        });

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getName()).isEqualTo(MARKER_CLASS_NAME);
        // Defense-in-depth TCCL install: inside the scope the codegenLoader is the TCCL.
        assertThat(capturedTcclInsideScope.get()).isSameAs(captured.get().getClassLoader());
        // The previous TCCL is restored after the scope.
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(tcclBefore);
    }

    /**
     * Constructs a {@link GenerateMojo} backed by a {@link MavenProject} whose
     * {@code getCompileClasspathElements()} returns the staged directory. This is the same
     * call {@link AbstractRewriteMojo#withCodegenScope} makes to build the URLClassLoader.
     */
    private static GenerateMojo mojo(Path basedir, Path stagedClasses) {
        var mojo = new GenerateMojo();
        var project = new MavenProject() {
            @Override
            public List<String> getCompileClasspathElements() {
                return List.of(stagedClasses.toAbsolutePath().toString());
            }
        };
        project.setFile(basedir.resolve("pom.xml").toFile());
        var build = new Build();
        build.setOutputDirectory(stagedClasses.toAbsolutePath().toString());
        project.setBuild(build);
        mojo.project = project;
        mojo.outputPackage = "com.example.generated";
        mojo.jooqPackage = "com.example.jooq";
        mojo.outputDirectory = basedir.resolve("target/generated-sources/graphitron").toString();
        return mojo;
    }

    /**
     * Stages the marker class as {@code <root>/no/example/service/MarkerService.class}. The
     * bytes are the canonical Java 17 encoding of {@code public class
     * no.example.service.MarkerService {}}.
     */
    private static Path writeMarkerClass(Path root) throws Exception {
        // Hand-rolled, minimal class file: Java 17 (major 61), public class with default ctor.
        // Constant pool layout:
        //   #1 Methodref(#2.#3)             = java/lang/Object."<init>":()V
        //   #2 Class(#4)                    = java/lang/Object
        //   #3 NameAndType(#5:#6)           = "<init>":"()V"
        //   #4 Utf8 "java/lang/Object"
        //   #5 Utf8 "<init>"
        //   #6 Utf8 "()V"
        //   #7 Class(#8)                    = no/example/service/MarkerService
        //   #8 Utf8 "no/example/service/MarkerService"
        //   #9 Utf8 "Code"
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);         // minor
        out.writeShort(61);        // major (Java 17)
        out.writeShort(10);        // constant_pool_count = 9+1
        // #1 Methodref
        out.writeByte(10); out.writeShort(2); out.writeShort(3);
        // #2 Class
        out.writeByte(7);  out.writeShort(4);
        // #3 NameAndType
        out.writeByte(12); out.writeShort(5); out.writeShort(6);
        // #4 Utf8 "java/lang/Object"
        out.writeByte(1); out.writeUTF("java/lang/Object");
        // #5 Utf8 "<init>"
        out.writeByte(1); out.writeUTF("<init>");
        // #6 Utf8 "()V"
        out.writeByte(1); out.writeUTF("()V");
        // #7 Class
        out.writeByte(7); out.writeShort(8);
        // #8 Utf8 "no/example/service/MarkerService"
        out.writeByte(1); out.writeUTF("no/example/service/MarkerService");
        // #9 Utf8 "Code"
        out.writeByte(1); out.writeUTF("Code");

        out.writeShort(0x0021);    // access_flags: ACC_PUBLIC | ACC_SUPER
        out.writeShort(7);         // this_class = #7
        out.writeShort(2);         // super_class = #2 (java/lang/Object)
        out.writeShort(0);         // interfaces_count
        out.writeShort(0);         // fields_count
        out.writeShort(1);         // methods_count
        // method: <init>()V
        out.writeShort(0x0001);    // access_flags = ACC_PUBLIC
        out.writeShort(5);         // name_index = <init>
        out.writeShort(6);         // descriptor_index = ()V
        out.writeShort(1);         // attributes_count
        // Code attribute
        out.writeShort(9);         // attribute_name_index = "Code"
        out.writeInt(17);          // attribute_length = 17 bytes
        out.writeShort(1);         // max_stack
        out.writeShort(1);         // max_locals
        out.writeInt(5);           // code_length
        out.writeByte(0x2A);       //   aload_0
        out.writeByte(0xB7);       //   invokespecial
        out.writeShort(1);         //     ref to Methodref #1
        out.writeByte(0xB1);       //   return
        out.writeShort(0);         // exception_table_length
        out.writeShort(0);         // attributes_count
        out.writeShort(0);         // class attributes_count

        Path classDir = root.resolve("no/example/service");
        Files.createDirectories(classDir);
        Path classFile = classDir.resolve("MarkerService.class");
        Files.write(classFile, bos.toByteArray());
        return root;
    }
}
