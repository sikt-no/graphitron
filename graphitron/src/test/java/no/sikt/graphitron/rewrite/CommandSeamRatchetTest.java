package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command-migration ratchets: "a command-based emitter takes no {@link GraphitronSchema}"
 * made mechanical, installed at the measured current counts so the boundary can only move in the
 * stated direction. Each count carries its counting rule here, in the pattern constants, because
 * a number whose derivation does not travel with it gets re-derived differently by the next
 * reader; these patterns are the definition, not an approximation of one.
 *
 * <ul>
 *   <li><b>Primary</b>: {@code generate} entry points under {@code rewrite/generators/} that take
 *       the model. Entry points only; the non-entry-point helpers in {@code generators/} that
 *       also take the model are out of scope until their family migrates. Drives to zero as
 *       families move onto command relations.</li>
 *   <li><b>Secondary</b>: leaf-naming dispatch sites under {@code rewrite/generators/}, counted
 *       as {@code instanceof} matches and {@code case} patterns over the seven sealed hierarchies
 *       named in {@link #LEAF_HIERARCHIES}, as two separate figures because they move at
 *       different times. Falls family by family; a migrated family's dispatch relocates into a
 *       producer rather than disappearing.</li>
 *   <li><b>Tertiary</b>: the same two greps inside {@code plan/}. This one is expected to rise
 *       while producers are fed by leaf dispatch and to ratchet back to zero when the fact-visitor
 *       engine re-sources them, so its pin moves in both directions, consciously, one slice at a
 *       time. It exists to make a stalled relocation a flat line on a named number.</li>
 * </ul>
 *
 * <p>For new code the same boundary is structural rather than ratcheted:
 * {@link PackageImportDirectionTest} keeps the emit library out of {@code command} and {@code
 * plan} and restricts {@code render}'s legacy-tree imports to the borrowed ref dial, which covers
 * the render-side half of the rule (a renderer reads the refs riding the rows, never the schema
 * or a fact hierarchy).
 *
 * <p>When a count drops, lower its pin in the same commit; never raise the generators-side pins.
 */
@UnitTier
class CommandSeamRatchetTest {

    /**
     * Entry points in {@code generators/} still taking the model. Ratchets down to zero.
     * Lowered 24 to 23 when the root conditions shim generator retired into the condition
     * command's producer and glue renderer, then 23 to 22 when call-site convergence deleted the
     * entity conditions generator (the WHERE family's second and last model-taking emitter),
     * then 22 to 21 when the projection command retired the type-class generator.
     */
    private static final int MODEL_TAKING_ENTRY_POINTS = 21;

    /**
     * {@code instanceof} sites in {@code generators/} naming a leaf of the seven hierarchies.
     * Lowered 104 to 100 with the conditions shim generator's retirement, then 100 to 97 with
     * call-site convergence (the entity generator's participant dispatch and the inline hosts'
     * filter plumbing), then 97 to 83 when the projection command relocated the type-class
     * generator's and the four inline arm emitters' dispatch into the projection producer.
     */
    private static final int GENERATOR_LEAF_INSTANCEOF_SITES = 83;

    /**
     * {@code case} patterns in {@code generators/} naming a leaf of the seven hierarchies.
     * Lowered 89 to 87 with call-site convergence (the retired entity conditions generator's
     * participant dispatch), then 87 to 78 when the projection command relocated the retired
     * type-class generator's selection switch.
     */
    private static final int GENERATOR_LEAF_CASE_PATTERNS = 78;

    /**
     * Leaf references ({@code instanceof} plus {@code case}) inside {@code plan/}: the relocation
     * guard. Rises as families migrate their dispatch into producers, ratchets to zero when the
     * fact walk replaces it. Update deliberately per slice, in either direction. Opened at 1 (the
     * node-fetcher membership gate in {@link no.sikt.graphitron.plan.EmitPlan#produce} reads a
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType} leaf); raised to 10 when the
     * condition command's producer ({@link no.sikt.graphitron.plan.ConditionCommands}) relocated
     * the WHERE family's coordinate dispatch out of the retired shim generator; lowered to 6 when
     * that producer's membership collapsed onto the
     * {@link no.sikt.graphitron.rewrite.model.SqlGeneratingField} capability read (identity arms
     * remain only for the participant-bearing roots, the nesting recursion, and the two
     * emit-gap backstops); raised to 42 when the projection command's producer
     * ({@link no.sikt.graphitron.plan.ProjectionCommands}) relocated the projection family's
     * exhaustive leaf dispatch, the required-projection walk and its containment check out of
     * {@code generators/} — the four-layer window the tertiary count exists to make visible,
     * ratcheting back down when the fact walk replaces the relocated dispatch; lowered to 38
     * when the correlation keys became gated contribution arms and the walk and check they
     * replaced (with their capability {@code instanceof} probes) were deleted.
     */
    private static final int PLAN_LEAF_REFERENCES = 38;

    /**
     * The seven sealed hierarchies whose leaf names count as emit dispatch. This is the wide
     * definition (all seven, {@code instanceof} and {@code case} counted separately); the
     * narrower three-field-hierarchy figure that has circulated is a different number under a
     * different rule and must not be swapped in here.
     */
    private static final String LEAF_HIERARCHIES =
        "(?:ChildField|QueryField|MutationField|InputField|OutputField|GraphitronType|GraphitronField)";

    private static final Pattern ENTRY_POINT =
        Pattern.compile("static\\s+[\\w<>,.\\[\\]\\s?]+?\\s+generate\\([^)]*GraphitronSchema");

    private static final Pattern LEAF_INSTANCEOF =
        Pattern.compile("instanceof " + LEAF_HIERARCHIES + "[.A-Za-z]*");

    private static final Pattern LEAF_CASE =
        Pattern.compile("case " + LEAF_HIERARCHIES + "[.A-Za-z]*");

    private static Path mainSourceRoot() {
        return GuardScope.locateRepoRoot().resolve("graphitron/src/main/java/no/sikt/graphitron");
    }

    @Test
    void modelTakingEntryPointsInGenerators() throws IOException {
        int count = countMatches(mainSourceRoot().resolve("rewrite/generators"), ENTRY_POINT);
        assertThat(count)
            .as("generate(...GraphitronSchema...) entry points under generators/; a rise is a new "
                + "model-holding emitter (add a producer instead), a drop means lowering the pin "
                + "in the same commit")
            .isEqualTo(MODEL_TAKING_ENTRY_POINTS);
    }

    @Test
    void leafDispatchSitesInGenerators() throws IOException {
        Path generators = mainSourceRoot().resolve("rewrite/generators");
        assertThat(countMatches(generators, LEAF_INSTANCEOF))
            .as("instanceof sites in generators/ naming a leaf of the seven hierarchies; a rise "
                + "is new emit dispatch on leaf identity, a drop means lowering the pin in the "
                + "same commit")
            .isEqualTo(GENERATOR_LEAF_INSTANCEOF_SITES);
        assertThat(countMatches(generators, LEAF_CASE))
            .as("case patterns in generators/ naming a leaf of the seven hierarchies; same rule "
                + "as the instanceof pin")
            .isEqualTo(GENERATOR_LEAF_CASE_PATTERNS);
    }

    @Test
    void leafReferencesInPlan() throws IOException {
        Path plan = mainSourceRoot().resolve("plan");
        int count = countMatches(plan, LEAF_INSTANCEOF) + countMatches(plan, LEAF_CASE);
        assertThat(count)
            .as("leaf references (instanceof + case) inside plan/: producers may hold relocated "
                + "leaf dispatch while it awaits the fact walk, and every move of this number is "
                + "a deliberate pin update, not drift")
            .isEqualTo(PLAN_LEAF_REFERENCES);
    }

    /** Line-based occurrence count over every {@code .java} file under {@code root}. */
    private static int countMatches(Path root, Pattern pattern) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    var matcher = pattern.matcher(line);
                    while (matcher.find()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
