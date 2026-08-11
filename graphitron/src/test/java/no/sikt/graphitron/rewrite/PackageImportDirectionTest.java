package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard over the command/plan/render package triangle: the import-direction rules
 * that make "no emit vocabulary below the renderers" and "the shell decides nothing" checkable
 * from the first file instead of ratcheted.
 *
 * <ul>
 *   <li>{@code no.sikt.graphitron.command} holds pure data. It may import nothing of the emit
 *       library ({@code no.sikt.graphitron.javapoet}), nothing of {@code plan} or {@code render},
 *       and from the legacy tree only the named borrow dial below. The dial is the migration
 *       dial: entries leave it as the refs move to a shared pure-data floor, and the list is
 *       enforced instead of a blanket ban so the model's ref vocabulary is borrowed, never
 *       copied. From graphql-java, only {@code FieldCoordinates} (the coordinate key).</li>
 *   <li>{@code no.sikt.graphitron.plan} produces commands from the model: it may not import the
 *       emit library or {@code render}.</li>
 *   <li>{@code no.sikt.graphitron.render} interprets commands into emitted output: it holds no
 *       {@code GraphitronSchema} and no fact hierarchy. From the legacy tree it may import
 *       exactly what {@code command} may (the refs that ride the rows must be readable by the
 *       renderer of those rows, so the two legs read one dial and shrink in lockstep), and never
 *       {@code plan}.</li>
 *   <li>{@code no.sikt.graphitron.facts} gathers fact relations at the parse boundary: it holds
 *       graphql-java types legitimately (the gather reads the assembled schema; this is a
 *       positive allowance, not a gap in the rules), and imports nothing else of the tree: not
 *       the legacy core (the traversal is injected by the caller so reachability keeps its one
 *       home), not the emit library, and not {@code command} / {@code plan} / {@code render}
 *       (facts sit below commands; the corpus will read facts without a plan).</li>
 * </ul>
 *
 * <p>The guard also pins the minting site of {@code UnitRef} and {@code UnitMethodRef}: a unit
 * or unit-method name enters the world through the plan's naming vocabulary
 * ({@code GeneratedUnits}) and nowhere else, so a command naming a unit no scheme produces is
 * unrepresentable in practice.
 */
@UnitTier
class PackageImportDirectionTest {

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");

    /**
     * The one borrow dial both {@code command} and {@code render} read: the model refs they may
     * import, exactly as the shared-vocabulary decision enumerates them. An import of a nested
     * member (e.g. {@code JoinStep.Hop}) or a static member counts as its enclosing entry.
     * {@code HelperRef}, {@code TableExpr}, {@code JoinConditionRef}, {@code RoutineRef} and
     * {@code ParamSource} are not new surface: each rides a borrowed ref as a component
     * ({@code CallSiteExtraction.NodeIdDecodeKeys}; {@code JoinStep.Hop}'s target, {@code On}
     * and routine payloads), so the enumeration was already implicitly admitting them (see the
     * closure census below). The projection command added three genuinely new entries, borrowed
     * verbatim rather than narrowed so the launcher family inherits the shape decision:
     * {@code ParentCorrelation} (the step-0 dispatch its multiset arms render),
     * {@code OrderBySpec} (the inline subselect's fixed ordering), and {@code LookupMapping}
     * (the {@code @lookupKey} VALUES keyset and its rows helper). The launcher family added
     * {@code FacetSpec} (the faceted carrier's decode data, borrowed whole on the facet plan
     * rather than copied field by field) and {@code ParticipantRef} (the discriminated arm's
     * per-participant facts, borrowed whole so type name, discriminator value, cross-table
     * fields, the child-to-parent hop and the alias formulas ride one ref).
     */
    private static final Set<String> BORROWED_MODEL_REFS = Set.of(
        "no.sikt.graphitron.rewrite.model.TableRef",
        "no.sikt.graphitron.rewrite.model.ColumnRef",
        "no.sikt.graphitron.rewrite.model.MethodRef",
        "no.sikt.graphitron.rewrite.model.JoinStep",
        "no.sikt.graphitron.rewrite.model.On",
        "no.sikt.graphitron.rewrite.model.CallParam",
        "no.sikt.graphitron.rewrite.model.CallSiteExtraction",
        "no.sikt.graphitron.rewrite.model.HelperRef",
        "no.sikt.graphitron.rewrite.model.TableExpr",
        "no.sikt.graphitron.rewrite.model.JoinConditionRef",
        "no.sikt.graphitron.rewrite.model.RoutineRef",
        "no.sikt.graphitron.rewrite.model.ParamSource",
        "no.sikt.graphitron.rewrite.model.ParentCorrelation",
        "no.sikt.graphitron.rewrite.model.OrderBySpec",
        "no.sikt.graphitron.rewrite.model.LookupMapping",
        "no.sikt.graphitron.rewrite.model.FacetSpec",
        "no.sikt.graphitron.rewrite.model.ParticipantRef",
        // The batched child's delivery facts, borrowed whole so the row and the loader wiring
        // read one fact: SourceKey (key columns, wrap, element type) and LoaderRegistration
        // (container and dispatch). Both were already in the component closure via
        // ParentCorrelation; naming them here admits the direct import the Batched arm carries.
        "no.sikt.graphitron.rewrite.model.SourceKey",
        "no.sikt.graphitron.rewrite.model.LoaderRegistration",
        // A derivation helper rather than a ref: the four-cell loader-container wrap
        // (Map/List by per-key list-ness) has one formula, and the launcher renderer's
        // service arms read the same one the classifier's return-type equality check reads,
        // so the two hosts cannot drift.
        "no.sikt.graphitron.rewrite.model.RowsMethodShape",
        // Already admitted implicitly through ParamSource.Arg (see BORROWED_COMPONENT_CLOSURE);
        // named here because the routine call emitter forks on the path shape directly — a bare
        // slot reads the argument, a dot-path reads it through a registered descent helper.
        "no.sikt.graphitron.rewrite.PathExpr"
    );

    /**
     * The legacy-tree surface the borrow dial <em>implicitly</em> admits: the transitive closure
     * of the borrowed refs' sealed arms and record components. A hand-listed dial drifts the
     * moment a component is added to a borrowed type, so the census is computed by reflection
     * and pinned here; growing it is a deliberate edit to this list, not a silent widening.
     * (javapoet types also appear as components of several refs; that is the emit-vocabulary
     * debt the model guard's allowlist tracks, not new information here, so the census pins
     * only the {@code no.sikt.graphitron.rewrite} types.)
     */
    private static final Set<String> BORROWED_COMPONENT_CLOSURE = Set.of(
        "no.sikt.graphitron.rewrite.PathExpr",
        "no.sikt.graphitron.rewrite.model.CallParam",
        "no.sikt.graphitron.rewrite.model.CallSiteExtraction",
        "no.sikt.graphitron.rewrite.model.ColumnRef",
        "no.sikt.graphitron.rewrite.model.ConditionFilter",
        "no.sikt.graphitron.rewrite.model.FacetSpec",
        "no.sikt.graphitron.rewrite.model.ForeignKeyRef",
        "no.sikt.graphitron.rewrite.model.HelperRef",
        "no.sikt.graphitron.rewrite.model.InputColumnBinding",
        "no.sikt.graphitron.rewrite.model.JoinConditionRef",
        "no.sikt.graphitron.rewrite.model.JoinSlot",
        "no.sikt.graphitron.rewrite.model.JoinStep",
        "no.sikt.graphitron.rewrite.model.LoaderRegistration",
        "no.sikt.graphitron.rewrite.model.LookupMapping",
        "no.sikt.graphitron.rewrite.model.MethodRef",
        "no.sikt.graphitron.rewrite.model.On",
        "no.sikt.graphitron.rewrite.model.OrderBySpec",
        "no.sikt.graphitron.rewrite.model.ParamSource",
        "no.sikt.graphitron.rewrite.model.ParentCorrelation",
        "no.sikt.graphitron.rewrite.model.ParticipantRef",
        "no.sikt.graphitron.rewrite.model.RoutineRef",
        "no.sikt.graphitron.rewrite.model.RowsMethodShape",
        "no.sikt.graphitron.rewrite.model.SourceKey",
        "no.sikt.graphitron.rewrite.model.TableExpr",
        "no.sikt.graphitron.rewrite.model.TableRef"
    );

    private static final String COMMAND_GRAPHQL_ALLOWED = "graphql.schema.FieldCoordinates";

    private record Finding(Path file, String importName, String rule) {
        @Override public String toString() {
            return file + "  imports " + importName + "  (" + rule + ")";
        }
    }

    @Test
    void commandPlanRenderImportDirections() throws IOException {
        Path sourceRoot = GuardScope.locateRepoRoot().resolve("graphitron/src/main/java/no/sikt/graphitron");
        var findings = new ArrayList<Finding>();

        int commandFiles = scan(sourceRoot.resolve("command"), findings, (file, imp) -> {
            if (imp.startsWith("no.sikt.graphitron.javapoet")) {
                return "command never imports the emit library";
            }
            if (imp.startsWith("no.sikt.graphitron.plan") || imp.startsWith("no.sikt.graphitron.render")) {
                return "command sits below plan and render";
            }
            if (imp.startsWith("no.sikt.graphitron.") && !imp.startsWith("no.sikt.graphitron.command.")
                && !isBorrowedRef(imp)) {
                return "command may borrow only the enumerated model-ref dial";
            }
            if (imp.startsWith("graphql.") && !imp.equals(COMMAND_GRAPHQL_ALLOWED)) {
                return "command's only graphql-java borrow is FieldCoordinates";
            }
            return null;
        });

        int planFiles = scan(sourceRoot.resolve("plan"), findings, (file, imp) -> {
            if (imp.startsWith("no.sikt.graphitron.javapoet")) {
                return "plan never imports the emit library";
            }
            if (imp.startsWith("no.sikt.graphitron.render")) {
                return "plan produces commands; it never sees renderers";
            }
            return null;
        });

        int renderFiles = scan(sourceRoot.resolve("render"), findings, (file, imp) -> {
            if (imp.startsWith("no.sikt.graphitron.rewrite") && !isBorrowedRef(imp)) {
                return "render reads the borrowed refs that ride the rows and nothing else of the legacy core";
            }
            if (imp.startsWith("no.sikt.graphitron.plan")) {
                return "render interprets commands; it never sees producers";
            }
            return null;
        });

        int factsFiles = scan(sourceRoot.resolve("facts"), findings, (file, imp) -> {
            if (imp.startsWith("no.sikt.graphitron.")
                    && !imp.startsWith("no.sikt.graphitron.facts.")) {
                return "facts gathers at the parse boundary: graphql-java only, nothing of the"
                    + " tree (the traversal is injected, so no legacy-core import; commands sit"
                    + " above facts)";
            }
            return null;
        });

        assertThat(findings).as("package-triangle import-direction violations").isEmpty();
        assertThat(commandFiles).as("command sources scanned (walk must not be vacuous)").isGreaterThanOrEqualTo(4);
        assertThat(planFiles).as("plan sources scanned (walk must not be vacuous)").isGreaterThanOrEqualTo(3);
        assertThat(renderFiles).as("render sources scanned (walk must not be vacuous)").isGreaterThanOrEqualTo(3);
        assertThat(factsFiles).as("facts sources scanned (walk must not be vacuous)").isGreaterThanOrEqualTo(5);
    }

    private static boolean isBorrowedRef(String imp) {
        return BORROWED_MODEL_REFS.stream()
            .anyMatch(entry -> imp.equals(entry) || imp.startsWith(entry + "."));
    }

    /**
     * The closure census: what the borrow dial implicitly admits is computed, not remembered.
     * Walks the borrowed refs' sealed arms, record components (with type arguments), nested
     * types, and interface accessor returns, and pins the reachable
     * {@code no.sikt.graphitron.rewrite} top-level types against
     * {@link #BORROWED_COMPONENT_CLOSURE}, so adding a component to a borrowed type surfaces
     * here as a deliberate census edit instead of silently widening the admitted surface.
     */
    @Test
    void borrowDialComponentClosureIsPinned() throws Exception {
        var visited = new java.util.HashSet<Class<?>>();
        var reachable = new java.util.TreeSet<String>();
        for (String root : BORROWED_MODEL_REFS) {
            walkClosure(Class.forName(root), visited, reachable);
        }
        assertThat(reachable)
            .as("legacy-tree types reachable from the borrow dial's components")
            .containsExactlyInAnyOrderElementsOf(BORROWED_COMPONENT_CLOSURE);
    }

    private static void walkClosure(Class<?> c, java.util.Set<Class<?>> visited, java.util.Set<String> reachable) {
        if (c == null || c.isPrimitive()) {
            return;
        }
        while (c.isArray()) {
            c = c.getComponentType();
        }
        if (!c.getName().startsWith("no.sikt.graphitron.rewrite") || !visited.add(c)) {
            return;
        }
        Class<?> top = c;
        while (top.getEnclosingClass() != null) {
            top = top.getEnclosingClass();
        }
        reachable.add(top.getName());
        if (c.isSealed()) {
            for (Class<?> p : c.getPermittedSubclasses()) {
                walkClosure(p, visited, reachable);
            }
        }
        if (c.isRecord()) {
            for (var rc : c.getRecordComponents()) {
                walkClosure(rc.getType(), visited, reachable);
                walkTypeArguments(rc.getGenericType(), visited, reachable);
            }
        }
        for (Class<?> nested : c.getDeclaredClasses()) {
            walkClosure(nested, visited, reachable);
        }
        if (c.isInterface()) {
            for (var m : c.getDeclaredMethods()) {
                if (!m.isSynthetic()) {
                    walkClosure(m.getReturnType(), visited, reachable);
                    walkTypeArguments(m.getGenericReturnType(), visited, reachable);
                }
            }
        }
    }

    private static void walkTypeArguments(java.lang.reflect.Type type, java.util.Set<Class<?>> visited,
            java.util.Set<String> reachable) {
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (arg instanceof Class<?> cls) {
                    walkClosure(cls, visited, reachable);
                }
            }
        }
    }

    @Test
    void unitRefsAreMintedOnlyByThePlansNamingVocabulary() throws IOException {
        Path sourceRoot = GuardScope.locateRepoRoot().resolve("graphitron/src/main/java");
        Path mintingSite = sourceRoot.resolve("no/sikt/graphitron/plan/GeneratedUnits.java");
        var offenders = new ArrayList<Path>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (file.equals(mintingSite)) continue;
                String source = Files.readString(file);
                if (source.contains("new UnitRef(") || source.contains("new UnitMethodRef(")) {
                    offenders.add(file);
                }
            }
        }
        assertThat(offenders)
            .as("UnitRef and UnitMethodRef are minted only by GeneratedUnits' naming schemes; mint"
                + " through a scheme (or add one) instead of constructing a ref ad hoc")
            .isEmpty();
        assertThat(scanned).as("main sources scanned (walk must not be vacuous)").isGreaterThan(300);
    }

    private interface ImportRule {
        /** Returns the violated rule's description, or null when the import is fine. */
        String check(Path file, String importName);
    }

    /** Applies the rule to every import in every source under {@code packageRoot}; returns the file count. */
    private static int scan(Path packageRoot, List<Finding> findings, ImportRule rule) throws IOException {
        if (!Files.isDirectory(packageRoot)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> files = Files.walk(packageRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                count++;
                for (String line : Files.readAllLines(file)) {
                    var matcher = IMPORT.matcher(line);
                    if (!matcher.find()) continue;
                    String imported = matcher.group(1);
                    String violated = rule.check(file, imported);
                    if (violated != null) {
                        findings.add(new Finding(file, imported, violated));
                    }
                }
            }
        }
        return count;
    }
}
