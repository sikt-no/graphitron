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
 * The completeness oracle for the model-sourced {@link CompileDependencyGraph}. This is
 * the {@code TypeSpec}-{@code ClassName} walk kept only as a <em>transitional fallback</em>:
 * it reverse-engineers the file-level reference structure from the emit artifact (the emitted
 * {@link TypeSpec}s) rather than from the model, so it is explicitly <strong>not</strong> the graph's
 * source. Its sole job is to <em>falsify incompleteness</em>: if this walk finds a reference between
 * two generated units that the model-sourced graph is missing, the exhaustive-switch projection has a
 * gap to close (that is how the DML {@code Projected*}/{@code Discriminated*} residual was
 * caught).
 *
 * <p>Because the model graph must be a <em>superset</em> of javac's true cross-unit dependencies, the
 * oracle itself must be a faithful superset of those dependencies, or a gap it cannot see is a
 * false-green. The oracle's contract is therefore
 * {@code walkEdges(u) ⊆ modelGraph.directReferences(u)} for every generated unit {@code u}: a
 * violation means the model missed a real dependency (fix the builder) or the walk over-collected
 * (tighten the scan).
 *
 * <p><b>How references are detected.</b> Two sources unioned:
 * <ol>
 *   <li>{@link TypeSpec#referencedClassNames()}: the structured {@code $T} references throughout the
 *       declaration <em>including bodies</em>. The structured walk descends into {@code $L}
 *       {@link CodeBlock} / anonymous-class / annotation args (so a {@code $T} nested arbitrarily deep
 *       inside {@code $L} blocks is seen) and walks type-variable-bound declarations. Unlike a rendered
 *       file's {@code import} list it also sees <em>same-package</em> references (bare simple names with
 *       no import), so the connection-fetcher to edge-fetcher edges and the type to inline-projected
 *       type edges the builder co-locates are visible. This is the load-bearing source.</li>
 *   <li>A literal-FQCN scan of the rendered source, for a class name baked into a raw {@code $L}
 *       {@code String} / {@code $S} argument (invisible to the structured walk because it is a string,
 *       not a typed reference). Kept as-is; do <em>not</em> add a same-package simple-name literal scan,
 *       which would over-collect (schema type-name string literals such as {@code b.name("Language")}
 *       would demand spurious model edges).</li>
 * </ol>
 *
 * <p><b>Review-only residual.</b> With the {@code $L}-block and type-variable-bound blind
 * spots closed, the true residual shrinks to a generated type's simple name baked as a raw <em>code-bearing
 * string</em> in a <em>same-package</em> unit (net 2's FQCN scan catches the cross-package form; a
 * same-package bare simple name in a string is caught by neither net). No emitter produces this today.
 * This is a <em>review-only</em> caveat, not an enforced contract: nothing mechanically prevents a
 * future emitter from baking a same-package type name as a raw string, and prose that reads as a
 * guarantee would go silently false. Discovery recipe: when adding an emitter that bakes generated
 * code as a raw {@code $L} {@code String} / {@code $S} (rather than a {@code $T}), reference the target
 * in the FQCN form net 2 can see, or extend this oracle. Prefer the {@code $T} structured form
 * wherever possible so the reference stays visible to source (1) with no residual at all.
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
