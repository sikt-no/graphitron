package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410 slice 6 — the incremental compile driver, exercised over synthetic {@link TypeSpec}s so it needs
 * no jOOQ catalog (unit tier). This is the seam that wires the three earlier slices together: the ABI
 * hash (slice 3), the recompile-set algorithm (slice 3) over a supplied {@link CompileDependencyGraph}
 * (slice 2), and the warm engine (slice 4). The clauses pinned here are the pruning contract at the
 * driver level (a body-only edit recompiles only the delta; an ABI edit pulls in its dependents), the
 * orphan-sweep on a removed unit, and the classpath-first precedence the output-ownership design
 * promises. The tier-crossing correctness harness (clause (a)/(b) over a real schema) lives in
 * {@code IncrementalCompileHarnessTest}; this covers the driver's own wiring.
 */
@UnitTier
class IncrementalCompilerTest {

    private static final String PKG = "gen.pkg";

    /** {@code gen.pkg.<name>} with one method returning a constant string; the body varies per version. */
    private static TypeSpec typeReturning(String name, String body) {
        return TypeSpec.classBuilder(name)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", body)
                .build())
            .build();
    }

    /** {@code gen.pkg.<name>} whose method body references a symbol that does not exist: never compiles. */
    private static TypeSpec typeThatFailsToCompile(String name) {
        return TypeSpec.classBuilder(name)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $L", "noSuchSymbol()")
                .build())
            .build();
    }

    /** Same as {@link #typeReturning} but with an extra method, so its ABI (not just its body) moves. */
    private static TypeSpec typeWithExtraMethod(String name) {
        return TypeSpec.classBuilder(name)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", "v1")
                .build())
            .addMethod(MethodSpec.methodBuilder("extra")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT)
                .addStatement("return 0")
                .build())
            .build();
    }

    private static String fqcn(String name) {
        return PKG + "." + name;
    }

    private static Map<String, TypeSpec> units(TypeSpec... specs) {
        var map = new LinkedHashMap<String, TypeSpec>();
        for (TypeSpec spec : specs) {
            map.put(fqcn(spec.name()), spec);
        }
        return map;
    }

    /** A graph where {@code gen.pkg.B} depends on {@code gen.pkg.A} (so an A ABI change recompiles B). */
    private static CompileDependencyGraph bDependsOnA() {
        return new CompileDependencyGraph() {
            @Override public Set<String> nodes() {
                return Set.of(fqcn("A"), fqcn("B"));
            }
            @Override public Set<String> directReferences(String node) {
                return fqcn("B").equals(node) ? Set.of(fqcn("A")) : Set.of();
            }
            @Override public Set<String> directDependents(String node) {
                return fqcn("A").equals(node) ? Set.of(fqcn("B")) : Set.of();
            }
        };
    }

    private static Path classFile(Path outputDir, String name) {
        return outputDir.resolve(PKG.replace('.', '/')).resolve(name + ".class");
    }

    @Test
    void compileAll_writesEveryUnit_andReportsThem(@TempDir Path dir) {
        try (var compiler = new IncrementalCompiler(dir, List.of())) {
            var outcome = compiler.compileAll(units(typeReturning("A", "v0"), typeReturning("B", "v0")));

            assertThat(outcome.round().success()).isTrue();
            assertThat(outcome.compiledUnits()).containsExactlyInAnyOrder(fqcn("A"), fqcn("B"));
            assertThat(classFile(dir, "A")).exists();
            assertThat(classFile(dir, "B")).exists();
        }
    }

    @Test
    void recompile_bodyOnlyEdit_compilesOnlyTheDelta(@TempDir Path dir) {
        try (var compiler = new IncrementalCompiler(dir, List.of())) {
            compiler.compileAll(units(typeReturning("A", "v0"), typeReturning("B", "v0")));

            // A's body changes but its signature does not, so its ABI hash is unchanged: B (a dependent)
            // must be pruned out of the recompile set.
            var outcome = compiler.recompile(
                units(typeReturning("A", "v1"), typeReturning("B", "v0")),
                Set.of(fqcn("A")),
                bDependsOnA());

            assertThat(outcome.round().success()).isTrue();
            assertThat(outcome.compiledUnits()).containsExactly(fqcn("A"));
        }
    }

    @Test
    void recompile_abiEdit_compilesDeltaAndDependents(@TempDir Path dir) {
        try (var compiler = new IncrementalCompiler(dir, List.of())) {
            compiler.compileAll(units(typeReturning("A", "v0"), typeReturning("B", "v0")));

            // A gains a method: its signature surface (ABI) moves, so the reverse-transitive dependents
            // (B) are pulled into the recompile set alongside the delta.
            var outcome = compiler.recompile(
                units(typeWithExtraMethod("A"), typeReturning("B", "v0")),
                Set.of(fqcn("A")),
                bDependsOnA());

            assertThat(outcome.round().success()).isTrue();
            assertThat(outcome.compiledUnits()).containsExactlyInAnyOrder(fqcn("A"), fqcn("B"));
        }
    }

    @Test
    void recompile_afterFailedRound_reattemptsTheFailedUnitUntilClean(@TempDir Path dir) {
        // B fails to compile; its .java then never changes, so it never re-enters the writer's delta.
        // A later save touching only A must still re-attempt B (and keep reporting failure), or the
        // round would read as clean while B's stale last-good .class lingered.
        var noEdges = new CompileDependencyGraph() {
            @Override public Set<String> nodes() { return Set.of(fqcn("A"), fqcn("B")); }
            @Override public Set<String> directReferences(String node) { return Set.of(); }
            @Override public Set<String> directDependents(String node) { return Set.of(); }
        };
        try (var compiler = new IncrementalCompiler(dir, List.of())) {
            var startup = compiler.compileAll(units(typeReturning("A", "v0"), typeThatFailsToCompile("B")));
            assertThat(startup.round().success()).isFalse();

            // Body-only edit to A; B unchanged (and still broken): B is re-attempted, round still fails.
            var retried = compiler.recompile(
                units(typeReturning("A", "v1"), typeThatFailsToCompile("B")),
                Set.of(fqcn("A")),
                noEdges);
            assertThat(retried.compiledUnits()).containsExactlyInAnyOrder(fqcn("A"), fqcn("B"));
            assertThat(retried.round().success()).isFalse();

            // B's fix arrives through the delta: the round is clean and the retry set drains, so the
            // next unrelated save is back to pure delta compilation.
            var fixed = compiler.recompile(
                units(typeReturning("A", "v1"), typeReturning("B", "v0")),
                Set.of(fqcn("B")),
                noEdges);
            assertThat(fixed.round().success()).isTrue();
            assertThat(classFile(dir, "B")).exists();

            var afterwards = compiler.recompile(
                units(typeReturning("A", "v2"), typeReturning("B", "v0")),
                Set.of(fqcn("A")),
                noEdges);
            assertThat(afterwards.compiledUnits()).containsExactly(fqcn("A"));
        }
    }

    @Test
    void recompile_withoutAnAbiBaseline_compilesTheWholeTree(@TempDir Path dir) {
        // No startup compileAll happened (the skipInitial path): the first recompile has no ABI baseline
        // to diff against, so it must establish the complete image rather than leaving every un-edited
        // unit's .class missing.
        try (var compiler = new IncrementalCompiler(dir, List.of())) {
            var outcome = compiler.recompile(
                units(typeReturning("A", "v0"), typeReturning("B", "v0")),
                Set.of(fqcn("A")),
                bDependsOnA());

            assertThat(outcome.round().success()).isTrue();
            assertThat(outcome.compiledUnits()).containsExactlyInAnyOrder(fqcn("A"), fqcn("B"));
            assertThat(classFile(dir, "B")).exists();
        }
    }

    @Test
    void compileAll_removedUnit_sweepsItsOrphanClass(@TempDir Path dir) {
        try (var compiler = new IncrementalCompiler(dir, List.of())) {
            compiler.compileAll(units(typeReturning("A", "v0"), typeReturning("B", "v0")));
            assertThat(classFile(dir, "B")).exists();

            // The next generation no longer emits B (a removed coordinate). Its .class is an orphan and
            // the exclusive-dir sweep must drop it, while A survives.
            compiler.compileAll(units(typeReturning("A", "v1")));

            assertThat(classFile(dir, "A")).exists();
            assertThat(classFile(dir, "B")).doesNotExist();
        }
    }

    @Test
    void classpathPrecedence_freshCopyWinsWhenGraphitronDirIsFirst(@TempDir Path graphitronDir,
                                                                    @TempDir Path staleDir) throws Exception {
        // A stale copy of gen.pkg.Widget lives in a separate dir (standing in for target/classes); a
        // fresh copy is compiled into the graphitron-exclusive dir. The output-ownership design places
        // the graphitron dir first on the run classpath, so the fresh copy must shadow the stale one.
        try (var stale = new IncrementalCompiler(staleDir, List.of())) {
            stale.compileAll(units(typeReturning("Widget", "stale")));
        }
        try (var fresh = new IncrementalCompiler(graphitronDir, List.of())) {
            fresh.compileAll(units(typeReturning("Widget", "fresh")));
        }

        assertThat(loadWidgetValue(graphitronDir, staleDir))
            .as("graphitron dir first: the fresh copy wins")
            .isEqualTo("fresh");
        assertThat(loadWidgetValue(staleDir, graphitronDir))
            .as("stale dir first: ordering is what decides, confirming precedence is real")
            .isEqualTo("stale");
    }

    /** Loads {@code gen.pkg.Widget} off a classpath of the two dirs in order and invokes {@code value()}. */
    private static String loadWidgetValue(Path first, Path second) throws Exception {
        var urls = new URL[]{first.toUri().toURL(), second.toUri().toURL()};
        // Null parent so only these two dirs resolve gen.pkg.Widget, isolating the ordering effect.
        try (var loader = new URLClassLoader(urls, null)) {
            Class<?> widget = loader.loadClass(fqcn("Widget"));
            Object instance = widget.getDeclaredConstructor().newInstance();
            return (String) widget.getMethod("value").invoke(instance);
        }
    }

    @Test
    void classOutputDir_isTheDirTheEngineWritesInto(@TempDir Path dir) {
        try (var compiler = new IncrementalCompiler(dir, List.of())) {
            assertThat(compiler.classOutputDir()).isEqualTo(dir);
        }
    }
}
