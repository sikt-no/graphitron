package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.tools.JavaFileObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R410 slice 6 — the dev-loop compile driver that ties the warm {@link IncrementalCompileEngine} to the
 * {@link CompileDependencyGraph} (slice 2), the ABI hashes ({@link AbiSignature}, slice 3), and the
 * recompile-set algorithm ({@link RecompileSet}, slice 3). It is the one long-lived component
 * {@code DevMojo} holds across saves: it owns the warm compiler and the previous round's ABI hash map,
 * the state the recompile set is computed against.
 *
 * <p>Three events drive it:
 * <ul>
 *   <li><b>Startup / consumer-{@code .class} change</b> → {@link #compileAll}: full compile of the whole
 *       generated tree, establishing (or re-establishing) the ABI baseline. A consumer service ABI edit
 *       is invalidated conservatively by recompiling the whole tree rather than modelling
 *       generated→consumer edges (the graph only carries generated→generated edges; a precise
 *       generated→consumer invalidation is deferred, and belongs with R333's method graph).</li>
 *   <li><b>Schema save</b> → {@link #recompile}: recompile the writer's delta plus the reverse-transitive
 *       dependents of the delta units whose ABI moved, then advance the ABI baseline.</li>
 * </ul>
 *
 * <p>The graph is supplied per call (rebuilt from the freshly classified model by
 * {@code GraphQLRewriteGenerator.generateIncremental}) rather than mutated in place: rebuilding is
 * always consistent with the sources just written and the build is cheap relative to generation. The
 * spec's "held and updated across saves" is an optimisation this driver forgoes for correctness-by-construction.
 *
 * <p><b>Thread safety.</b> In the dev loop two watcher threads can drive a round: the schema-save path
 * ({@link #recompile}) and the consumer-{@code .class}-change path ({@link #compileAll}). The warm
 * {@link IncrementalCompileEngine} reuses one file manager and is not safe for concurrent tasks, and the
 * ABI baseline is mutable, so both round methods are {@code synchronized} to serialise rounds.
 */
public final class IncrementalCompiler implements AutoCloseable {

    private final IncrementalCompileEngine engine;
    // The previous round's ABI hash per unit; the baseline abiChanged() compares this round against.
    private Map<String, String> previousHashes = Map.of();

    /**
     * @param classOutputDir the graphitron-exclusive class output root ({@code target/graphitron-classes})
     * @param classpath      the resolved compile classpath (consumer {@code target/classes}, reactor
     *                       output, dependency jars); the output dir is placed first by the engine
     */
    public IncrementalCompiler(Path classOutputDir, List<Path> classpath) {
        this(new IncrementalCompileEngine(classOutputDir, classpath));
    }

    /** Test seam: inject a pre-built engine (e.g. over a temp output dir with a synthetic classpath). */
    IncrementalCompiler(IncrementalCompileEngine engine) {
        this.engine = engine;
    }

    /**
     * Full compile of the whole generated tree: renders every emitted unit, compiles it, sweeps orphan
     * {@code .class}, and (re)sets the ABI baseline to this tree's hashes. Used at startup (a complete
     * runnable image before any edit) and on a consumer {@code .class} change (conservative whole-tree
     * invalidation).
     */
    public synchronized CompileOutcome compileAll(Map<String, TypeSpec> emittedUnits) {
        Set<String> units = emittedUnits.keySet();
        CompileRound round = engine.compile(render(units, emittedUnits));
        engine.sweepOrphans(units);
        this.previousHashes = hashes(emittedUnits);
        return new CompileOutcome(round, units);
    }

    /**
     * Incremental recompile for one schema save: {@code recompile = delta ∪ reverse-transitive dependents
     * of the delta units whose ABI moved}. Renders and compiles that set, sweeps orphan {@code .class}
     * against the full live unit set, and advances the ABI baseline to this round's hashes.
     *
     * @param emittedUnits every unit emitted this run (FQCN → its {@link TypeSpec}); the live set for the
     *                     sweep and the source of this round's ABI hashes
     * @param changedUnits the writer's content-changed delta by FQCN
     * @param graph        the compile-dependency graph coarsened from this run's classified model
     */
    public synchronized CompileOutcome recompile(Map<String, TypeSpec> emittedUnits, Set<String> changedUnits,
                                    CompileDependencyGraph graph) {
        Map<String, String> currentHashes = hashes(emittedUnits);
        Set<String> abiChanged = RecompileSet.abiChanged(changedUnits, previousHashes, currentHashes);
        Set<String> recompileSet = RecompileSet.compute(graph, changedUnits, abiChanged);
        CompileRound round = engine.compile(render(recompileSet, emittedUnits));
        engine.sweepOrphans(emittedUnits.keySet());
        this.previousHashes = currentHashes;
        return new CompileOutcome(round, recompileSet);
    }

    /** The graphitron-exclusive class output root this driver compiles into. */
    public Path classOutputDir() {
        return engine.classOutputDir();
    }

    @Override
    public void close() {
        engine.close();
    }

    private static Map<String, String> hashes(Map<String, TypeSpec> units) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : units.entrySet()) {
            result.put(entry.getKey(), AbiSignature.hash(entry.getValue()));
        }
        return result;
    }

    /**
     * Renders the requested units to compilable {@link JavaFileObject}s off their emitted {@link TypeSpec}s.
     * A requested FQCN with no emitted spec this run is skipped: the recompile set is drawn from the graph
     * (a superset), so an over-approximated node that was not emitted simply contributes nothing.
     */
    private static List<JavaFileObject> render(Collection<String> fqcns, Map<String, TypeSpec> units) {
        List<JavaFileObject> sources = new ArrayList<>();
        for (String fqcn : fqcns) {
            TypeSpec spec = units.get(fqcn);
            if (spec == null) {
                continue;
            }
            int lastDot = fqcn.lastIndexOf('.');
            String packageName = lastDot < 0 ? "" : fqcn.substring(0, lastDot);
            sources.add(JavaFile.builder(packageName, spec).indent("    ").build().toJavaFileObject());
        }
        return sources;
    }
}
