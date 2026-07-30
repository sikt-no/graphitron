package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reference oracle for the plan-projected {@link CompileDependencyGraph}. It reverse-engineers
 * the file-level reference structure from the emit artifact (the emitted {@link TypeSpec}s)
 * rather than from the plan, so it is explicitly <strong>not</strong> the graph's source. It
 * feeds two legs of the acceptance oracle in the incremental harness: the emit-to-graph leg
 * falsifies incompleteness (a reference this walk finds that the projected graph misses is a
 * gap), and the bounded-gap leg falsifies undeclared over-collection (a projected edge this walk
 * cannot see must sit inside {@link PlanCompileGraph}'s declared superset).
 *
 * <p>The projected graph must be a <em>superset</em> of javac's true cross-unit dependencies, so
 * the oracle must be a faithful superset of those dependencies, or a gap it cannot see is a
 * false-green. The emit-to-graph contract, enforced by the harness's three-leg oracle test, is
 * {@code edges(u) ⊆ CompileDependencyGraph.directReferences(u)} for every generated unit
 * {@code u}: a violation means the projection missed a real dependency (fix the edge view or the
 * producing relation) or the walk over-collected (tighten the scan).
 *
 * <p><b>How references are detected.</b> Two sources unioned:
 * <ol>
 *   <li>{@link TypeSpec#referencedClassNames()}: the structured {@code $T} references throughout
 *       the declaration <em>including bodies</em>: {@code $L} {@link CodeBlock} / anonymous-class
 *       / annotation args at any depth, plus type-variable bounds. Unlike a rendered file's
 *       {@code import} list it also sees <em>same-package</em> references (bare simple names with
 *       no import), so the co-located edges (connection-fetcher to edge-fetcher, type to
 *       inline-projected type) are visible. The load-bearing source.</li>
 *   <li>A literal-FQCN scan of the rendered source, for a class name baked into a raw {@code $L}
 *       {@code String} / {@code $S} argument (a string, not a typed reference, so invisible to
 *       the structured walk). Do <em>not</em> add a same-package simple-name literal scan: it
 *       over-collects (schema type-name string literals such as {@code b.name("Language")} would
 *       demand spurious model edges).</li>
 * </ol>
 *
 * <p><b>Review-only residual.</b> A generated type's simple name baked as a raw
 * <em>code-bearing string</em> in a <em>same-package</em> unit is caught by neither source (the
 * FQCN scan catches only the cross-package form). No emitter produces this shape, but nothing
 * mechanically prevents one, so this is a review caveat, not an enforced contract. When adding an
 * emitter that bakes generated code as a raw {@code $L} {@code String} / {@code $S}, reference
 * the target in FQCN form or extend this oracle; prefer the structured {@code $T} form, which
 * leaves no residual at all.
 */
public final class TypeSpecReferenceWalk {

    private TypeSpecReferenceWalk() {}

    /**
     * The reference superset: for each generated unit (by FQCN), the set of <em>other</em> generated
     * units it references. Keys and values are drawn from {@code emittedUnits.keySet()} (the full
     * generated closure of one run).
     */
    public static Map<String, Set<String>> edges(Map<String, TypeSpec> emittedUnits) {
        Set<String> generated = emittedUnits.keySet();
        // Boundary-anchored literal matcher per candidate FQCN, for the $L-baked case.
        Map<String, Pattern> literalPatterns = new LinkedHashMap<>();
        for (String fqcn : generated) {
            literalPatterns.put(fqcn, Pattern.compile(
                "(?<![A-Za-z0-9_$.])" + Pattern.quote(fqcn) + "(?![A-Za-z0-9_$])"));
        }

        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (var entry : emittedUnits.entrySet()) {
            String fqcn = entry.getKey();
            TypeSpec spec = entry.getValue();
            Set<String> referenced = new LinkedHashSet<>();

            // (1) structured $T references, including same-package.
            for (ClassName referencedName : spec.referencedClassNames()) {
                String name = referencedName.canonicalName();
                if (!name.equals(fqcn) && generated.contains(name)) {
                    referenced.add(name);
                }
            }

            // (2) $L-baked literal FQCNs the structured walk cannot see.
            String source = render(fqcn, spec);
            for (var candidate : literalPatterns.entrySet()) {
                String name = candidate.getKey();
                if (!name.equals(fqcn) && !referenced.contains(name)
                    && candidate.getValue().matcher(source).find()) {
                    referenced.add(name);
                }
            }

            edges.put(fqcn, referenced);
        }
        return edges;
    }

    private static String render(String fqcn, TypeSpec spec) {
        int lastDot = fqcn.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : fqcn.substring(0, lastDot);
        return JavaFile.builder(packageName, spec).indent("    ").build().toString();
    }
}
