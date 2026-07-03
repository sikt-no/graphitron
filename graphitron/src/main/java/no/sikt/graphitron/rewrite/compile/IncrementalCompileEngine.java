package no.sikt.graphitron.rewrite.compile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * R410 slice 4 — the warm incremental compile engine. One {@link JavaCompiler}
 * ({@link ToolProvider#getSystemJavaCompiler()}, no new JDK-25 dependency) with a <em>reused</em>
 * {@link StandardJavaFileManager}, so the classpath is scanned once at dev startup rather than per
 * save; a fresh compilation task per round runs over that warm manager. This is the trick that
 * defeats the fixed per-invocation cost, the same one the frameworks use.
 *
 * <p>The engine owns the graphitron-exclusive output directory
 * ({@code target/graphitron-classes/<outputPackage>}, rooted at {@code classOutputDir}). Because that
 * dir is graphitron's alone (never the shared {@code target/classes}), incremental compilation is
 * sound by construction and the {@code .class} orphan {@linkplain #sweepOrphans sweep} can drop
 * anything not backing a live unit without an {@code OWNED_SUBPACKAGES} safety fence: there is no
 * consumer bytecode under it to protect. The engine deliberately does <em>not</em> decide the
 * recompile set (that is {@link RecompileSet} + slice-6 wiring); it compiles the sources it is handed
 * and reports the outcome.
 *
 * <p><b>Manager staleness (the named risk).</b> Reusing a file manager risks serving a cached symbol
 * for a class just recompiled, a silent-wrong-output bug. The mitigation is a fresh task per round
 * plus {@link StandardJavaFileManager#flush()} after each round so the next round re-reads freshly
 * written {@code .class} from disk rather than a cached object; the previously compiled units resolve
 * as {@code .class} on the class path (the output dir is on it), and units recompiled together in a
 * round resolve from source. {@code IncrementalCompileEngineTest} pins that a stale symbol never
 * survives a round.
 */
public final class IncrementalCompileEngine implements AutoCloseable {

    private final JavaCompiler compiler;
    private final StandardJavaFileManager fileManager;
    private final Path classOutputDir;

    /**
     * @param classOutputDir the graphitron-exclusive class output root
     *                       ({@code target/graphitron-classes/<outputPackage>}); created if absent
     * @param classpath      the resolved compile classpath (consumer {@code target/classes},
     *                       dependency jars, reactor output). The output dir itself is added so
     *                       already-compiled units resolve as dependencies of a later round.
     */
    public IncrementalCompileEngine(Path classOutputDir, List<Path> classpath) {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "No system Java compiler available; graphitron:dev incremental compile needs a JDK, not a JRE");
        }
        this.classOutputDir = classOutputDir;
        try {
            Files.createDirectories(classOutputDir);
            this.fileManager = compiler.getStandardFileManager(null, null, null);
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOutputDir.toFile()));
            List<java.io.File> classpathFiles = new ArrayList<>();
            classpathFiles.add(classOutputDir.toFile());
            for (Path entry : classpath) {
                classpathFiles.add(entry.toFile());
            }
            fileManager.setLocation(StandardLocation.CLASS_PATH, classpathFiles);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not initialise incremental compile engine", e);
        }
    }

    /**
     * Compiles one round's source set into the exclusive output dir. Returns javac's verdict plus the
     * round's diagnostics. A unit that fails to compile emits no {@code .class}, so it keeps its
     * last-good bytecode; the round reports {@code success == false} rather than pretending it is fresh.
     */
    public CompileRound compile(Collection<? extends JavaFileObject> sources) {
        if (sources.isEmpty()) {
            return new CompileRound(true, List.of());
        }
        var collector = new DiagnosticCollector<JavaFileObject>();
        JavaCompiler.CompilationTask task = compiler.getTask(
            null, fileManager, collector, List.of("-proc:none"), null, sources);
        boolean success = task.call();
        flush();
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
            diagnostics.add(CompileDiagnostic.from(diagnostic));
        }
        return new CompileRound(success, diagnostics);
    }

    /**
     * Drops every {@code .class} under the exclusive output dir that does not back a live unit (the
     * {@code .class} twin of the generator's {@code .java} orphan sweep). A nested class
     * {@code Outer$Inner.class} is kept iff its top-level {@code Outer} is live. Safe without scoping
     * because the dir is graphitron-exclusive.
     *
     * @param liveUnits fully-qualified names of every generated top-level unit that should exist now
     */
    public void sweepOrphans(Set<String> liveUnits) {
        if (!Files.isDirectory(classOutputDir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(classOutputDir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !liveUnits.contains(topLevelFqcn(p)))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Could not sweep orphan class " + p, e);
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not sweep orphan classes under " + classOutputDir, e);
        }
    }

    /** The top-level FQCN a {@code .class} file backs: relative path minus {@code .class}, {@code /}
     *  to {@code .}, truncated at the first {@code $} (nested/anonymous classes share their outer's). */
    private String topLevelFqcn(Path classFile) {
        String relative = classOutputDir.relativize(classFile).toString()
            .replace(java.io.File.separatorChar, '.');
        String fqcn = relative.substring(0, relative.length() - ".class".length());
        int nested = fqcn.indexOf('$');
        return nested < 0 ? fqcn : fqcn.substring(0, nested);
    }

    /** The exclusive class output root this engine compiles into. */
    public Path classOutputDir() {
        return classOutputDir;
    }

    private void flush() {
        try {
            fileManager.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not flush compile output", e);
        }
    }

    @Override
    public void close() {
        try {
            fileManager.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not close compile file manager", e);
        }
    }
}
