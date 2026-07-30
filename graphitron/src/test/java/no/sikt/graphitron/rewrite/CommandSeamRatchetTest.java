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
     * then 22 to 21 when the projection command retired the type-class generator, then 21 to
     * 20 when the type-unit relation's input-record rows retired the input-record generator's
     * whole-population entry point (the per-row build method takes a type, not the model), then
     * 20 to 18 when the fetchers kind folded (the error-type and connection fetcher generators'
     * loops became per-row builds). The schema-shape fold held this at 18 by exchange: the
     * schema-class assembler's rows-taking canonical entry arrived (it legitimately keeps the
     * model for the TypeResolver, error-fetcher and scalar registration reads) and an unused
     * set-taking convenience overload left; the per-type emitters' whole-population entry
     * points became test conveniences that derive their rows through the producer.
     */
    private static final int MODEL_TAKING_ENTRY_POINTS = 18;

    /**
     * {@code instanceof} sites in {@code generators/} naming a leaf of the seven hierarchies.
     * Lowered 104 to 100 with the conditions shim generator's retirement, then 100 to 97 with
     * call-site convergence (the entity generator's participant dispatch and the inline hosts'
     * filter plumbing), then 97 to 83 when the projection command relocated the type-class
     * generator's and the four inline arm emitters' dispatch into the projection producer, then
     * 83 to 81 when the discriminated-interface assembly's residence-split reads moved into the
     * schema's joined-table reprojection fold, then 81 to 72 when the fetchers kind folded (the
     * two-pass membership loops' variant filters and the seen-seed moved into the type-unit
     * producer, and the nesting-reach recursion into the schema's reach fold), then 72 to 71
     * when the schema-shape kind folded (the enum generator's membership probe moved into the
     * type-unit producer's total form switch; the registrations emitter's dispatch count is
     * unchanged, its hosting filter having become per-row routing).
     */
    private static final int GENERATOR_LEAF_INSTANCEOF_SITES = 71;

    /**
     * {@code case} patterns in {@code generators/} naming a leaf of the seven hierarchies.
     * Lowered 89 to 87 with call-site convergence (the retired entity conditions generator's
     * participant dispatch), then 87 to 78 when the projection command relocated the retired
     * type-class generator's selection switch, then 78 to 77 when the input-record fold's
     * reach walk moved into the schema's argument-reachability fold (its InputType case went
     * with it, to `rewrite/`, outside both scans), then 77 to 75 when the fetchers kind folded
     * (the retired pivot-wiring helpers' PivotField/BatchedPivotField cases moved into the
     * reach fold with the same relocation), then 75 to 74 when the schema-shape kind folded
     * (the input generator's InputType membership case moved into the type-unit producer's
     * total form switch), then 74 to 76 when the polymorphic delivery split doubled the child
     * leaf pair (the fetcher dispatch gained the two batched arms; the parent-input rows-method
     * gate swapped its two wrapper-derived arms for two identity reads).
     */
    private static final int GENERATOR_LEAF_CASE_PATTERNS = 76;

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
     * replaced (with their capability {@code instanceof} probes) were deleted; raised to 56 when
     * the launcher command's producer ({@link no.sikt.graphitron.plan.LauncherCommands})
     * relocated the root family's covered-family fact (a total switch over
     * {@link no.sikt.graphitron.rewrite.model.QueryField}'s twelve permits) and its migration
     * dial out of the fetcher generator's dispatch, which now routes on row presence; raised to
     * 67 when the routine root migrated and the dial classification became a second total
     * switch over the permits (exhaustiveness in exchange for the default throw), with the row
     * production fork joining it; raised to 69 when the interface root migrated (its arm in the
     * production fork plus the schema-free assembly's membership read). The residence-split
     * dispatch itself did not land here: it reads <em>classified fields</em>, an ancestor-free
     * post-walk fact, so it folded into the schema's joined-table reprojection index beside the
     * other post-walk folds rather than into a producer. Lowered to 55 when the lookup root's
     * fold closed the migration: the covered-family census and the dial classification (two
     * dozen restated permits) collapsed into the one membership-and-production switch
     * ({@link no.sikt.graphitron.plan.LauncherCommands}), whose totality with no default is the
     * membership enforcer. Raised to 61 when the type-unit producer
     * ({@link no.sikt.graphitron.plan.TypeUnitCommands}) relocated the fetchers kind's variant
     * membership (the four hosting classifications, the error and connection arms) out of the
     * retired two-pass loops. Raised to 85 when the schema-shape kind joined it: the total form
     * switch over the classification's eighteen leaf permits (including the two deliberate
     * no-row verdicts) plus the registersFetchers rule's six identity reads, replacing the
     * fifth and last membership copy (the schema-class assembler's own enumeration). Raised to
     * 87 when the polymorphic delivery split doubled the child leaf pair (the projection
     * producer's contribution switch gained the two batched leaves' correlation-key arms).
     * Raised to 91 when the DML reentry fold landed the launcher producer's per-family
     * membership switches and the validator's launcher-method census mirror (the mirror's
     * root-kind case labels; the child and DML arms reference their leaves fully qualified,
     * which this counting rule deliberately does not chase). Raised to 145 when the recompile
     * graph became a projection: the fetcher edge relation's producer
     * ({@link no.sikt.graphitron.plan.FetcherEdgeCommands}) landed three total
     * membership-and-production switches (query, mutation and child families) so the
     * non-launcher fetcher references are plan data, and the model-sourced graph builder's
     * unpriced switch tree in {@code compile/} deleted; the edges were always computed at
     * produce time, only the re-derivation died.
     */
    private static final int PLAN_LEAF_REFERENCES = 145;

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
