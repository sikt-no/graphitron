package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.tools.JavaFileObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dev-loop compile driver that ties the warm {@link IncrementalCompileEngine} to the
 * {@link CompileDependencyGraph}, the ABI hashes ({@link AbiSignature}), and the
 * recompile-set algorithm ({@link RecompileSet}). It is the one long-lived component
 * {@code DevMojo} holds across saves: it owns the warm compiler and the previous round's ABI hash map,
 * the state the recompile set is computed against.
 *
 * <p>Three events drive it:
 * <ul>
 *   <li><b>Startup / consumer-{@code .class} change</b> → {@link #compileAll}: full compile of the whole
 *       generated tree, establishing (or re-establishing) the ABI baseline. A consumer service ABI edit
 *       is invalidated conservatively by recompiling the whole tree rather than modelling
 *       generated→consumer edges (the graph only carries generated→generated edges; a precise
 *       generated→consumer invalidation is deferred, and belongs with the eventual method graph).</li>
 *   <li><b>Schema save</b> → {@link #recompile}: recompile the writer's delta plus the reverse-transitive
 *       dependents of the delta units whose ABI moved, then advance the ABI baseline. A failed round's
 *       units are re-attempted on every later round until one compiles them clean, so an unrelated save
 *       can never report a clean round while a stale last-good {@code .class} lingers.</li>
 * </ul>
 *
 * <p>The graph is supplied per call (rebuilt from the freshly classified model by
 * {@code GraphQLRewriteGenerator.generateIncremental}) rather than mutated in place: rebuilding is
 * always consistent with the sources just written and the build is cheap relative to generation.
 * Holding and updating the graph across saves would be an optimisation this driver forgoes for
 * correctness-by-construction.
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
    // The units attempted in a failed round, re-attempted on every later round until one compiles them
    // clean. Without this, a failing unit whose .java is unchanged never re-enters the writer's delta,
    // so a later save touching only other units would report a clean round and clear the failure while
    // the failed unit's last-good .class stayed silently stale.
    private Set<String> retryUnits = Set.of();

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
        this.retryUnits = round.success() ? Set.of() : Set.copyOf(units);
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
        if (previousHashes.isEmpty()) {
            // No ABI baseline yet (skipInitial, or the startup compile never ran): an incremental round
            // would compile only the delta and leave every un-edited unit's .class missing, a
            // half-populated dir the MCP execution driver cannot run. Establish the full image first.
            return compileAll(emittedUnits);
        }
        Map<String, String> currentHashes = hashes(emittedUnits);
        Set<String> abiChanged = RecompileSet.abiChanged(changedUnits, previousHashes, currentHashes);
        Set<String> recompileSet = new LinkedHashSet<>(RecompileSet.compute(graph, changedUnits, abiChanged));
        for (String unit : retryUnits) {
            if (emittedUnits.containsKey(unit)) {
                recompileSet.add(unit);
            }
        }
        CompileRound round = engine.compile(render(recompileSet, emittedUnits));
        engine.sweepOrphans(emittedUnits.keySet());
        this.previousHashes = currentHashes;
        this.retryUnits = round.success() ? Set.of() : Set.copyOf(recompileSet);
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
