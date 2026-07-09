package no.sikt.graphitron.rewrite.test.compile;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test-only harness that compiles emitted JavaPoet {@link TypeSpec}s in-process and hands back a
 * class loader over the resulting bytecode, so a {@code @UnitTier} test can instantiate and drive
 * the <em>real emitted bytes</em> rather than assert on their source string.
 *
 * <p>R429 slice 1 needs this: the connection-lifecycle runtime is emitted into the consumer's
 * output package (never shipped as a graphitron artifact), yet the spec asks for {@code @UnitTier}
 * coverage of acquisition/connect/disconnect/release ordering "over a fake {@code DataSource}".
 * The only way to exercise emitted behaviour in the {@code graphitron} module's unit tier is to
 * compile the {@code TypeSpec}s and load them; R410's {@link
 * no.sikt.graphitron.rewrite.compile.IncrementalCompileEngine} compiles-and-byte-compares but never
 * loads-and-invokes, so this is a separate, deliberately minimal, test-scoped seam.
 *
 * <p>Mechanics mirror the R410 engine: {@link ToolProvider#getSystemJavaCompiler()} over a
 * {@link StandardJavaFileManager} whose class path is harvested from
 * {@code System.getProperty("java.class.path")} (so jOOQ, graphql-java, and the JDK resolve exactly
 * as they do in the reactor build), sources rendered via JavaPoet's own
 * {@link JavaFile#toJavaFileObject()}. Compilation failure throws with the collected diagnostics so
 * a malformed emission surfaces as a test failure at the emitting generator, not a cryptic
 * {@code ClassNotFoundException} downstream.
 *
 * <p>The loader is parented to this class's loader so emitted references to library types resolve
 * against the same instances the test sees; only the freshly compiled {@code .class} files come from
 * the temp output dir. {@link #close()} closes the loader and deletes the temp tree; use
 * try-with-resources.
 */
public final class EmittedCodeHarness implements AutoCloseable {

    private final Path outputDir;
    private final URLClassLoader classLoader;

    private EmittedCodeHarness(Path outputDir, URLClassLoader classLoader) {
        this.outputDir = outputDir;
        this.classLoader = classLoader;
    }

    /**
     * Compiles the given emitted units and returns a harness that can load them.
     *
     * @param units fully-qualified class name to its emitted {@link TypeSpec}; every unit named by a
     *              cross-reference in the set must itself be in the set (javac resolves siblings from
     *              source in one round)
     */
    public static EmittedCodeHarness compile(Map<String, TypeSpec> units) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "No system Java compiler available; EmittedCodeHarness needs a JDK, not a JRE");
        }
        Path outputDir;
        try {
            outputDir = Files.createTempDirectory("graphitron-emitted-");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create temp output dir", e);
        }

        var collector = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(collector, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            List<File> classpath = new ArrayList<>();
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    classpath.add(new File(entry));
                }
            }
            fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);

            List<JavaFileObject> sources = renderSources(units);
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, collector, List.of("-proc:none"), null, sources);
            boolean success = task.call();
            if (!success) {
                throw new IllegalStateException(
                    "Emitted code failed to compile:\n" + formatDiagnostics(collector));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Compile of emitted code failed", e);
        }

        try {
            var loader = new URLClassLoader(
                new URL[]{outputDir.toUri().toURL()}, EmittedCodeHarness.class.getClassLoader());
            return new EmittedCodeHarness(outputDir, loader);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open class loader over emitted output", e);
        }
    }

    /** Loads a compiled emitted class by its fully-qualified name. */
    public Class<?> load(String fqcn) {
        try {
            return Class.forName(fqcn, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Emitted class not found after compile: " + fqcn, e);
        }
    }

    /** The loader over the compiled emitted bytecode, parented to the test's own loader. */
    public ClassLoader classLoader() {
        return classLoader;
    }

    private static List<JavaFileObject> renderSources(Map<String, TypeSpec> units) {
        List<JavaFileObject> out = new ArrayList<>();
        for (var entry : units.entrySet()) {
            String fqcn = entry.getKey();
            int lastDot = fqcn.lastIndexOf('.');
            String packageName = lastDot < 0 ? "" : fqcn.substring(0, lastDot);
            out.add(JavaFile.builder(packageName, entry.getValue()).indent("    ").build().toJavaFileObject());
        }
        return out;
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> collector) {
        return collector.getDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING)
            .map(Object::toString)
            .collect(Collectors.joining("\n"));
    }

    @Override
    public void close() {
        try {
            classLoader.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not close emitted-code class loader", e);
        }
        if (Files.isDirectory(outputDir)) {
            try (Stream<Path> paths = Files.walk(outputDir)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException("Could not delete " + p, e);
                        }
                    });
            } catch (IOException e) {
                throw new UncheckedIOException("Could not clean temp output dir " + outputDir, e);
            }
        }
    }
}
