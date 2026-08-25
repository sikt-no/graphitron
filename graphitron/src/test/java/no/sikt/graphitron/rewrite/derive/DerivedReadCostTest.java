package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.derive.MaterializeDependencies;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.RunawayRelation;
import no.sikt.graphitron.model.test.UnregisteredRelation;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A materialization registration may not make some <em>other</em> relation's read more expensive.
 *
 * <p>One directional claim with no number in it: for every registration in {@code meta_materialize}
 * and every relation whose derivation reaches that registration's target, reading the registered
 * shape must not visit more rows than reading the unregistered one. A registration is a shared
 * investment, bought for one reader's sake and paid for by every relation that names the target, so
 * making a different reader worse is a regression whether or not anybody has named a budget for that
 * reader. Nothing else in the tree makes this claim: {@code MaterializeRegistryGateTest} closes the
 * register against the schema and asks nothing about cost, and the {@code scanCount} ceilings in
 * {@code graphitron-lsp} are held over reader surfaces rather than over relations.
 *
 * <p>No ceiling, and that is a correction rather than an omission. A ceiling of the kind
 * {@code SurfaceScanCountTest} holds works there because the registration <em>helped</em>, so the
 * number sits between a lower registered figure and a higher unregistered one and reinstating the
 * defect raises the figure past it. Where a registration hurts, the ordering is inverted: a ceiling
 * above the current figure can never be failed by that mutation, and one between the two figures
 * fails on the shipping tree. The two rules are jointly unsatisfiable exactly when the regression is
 * real, so the discriminating assertion is the direction itself.
 *
 * <p><b>Instrument.</b> The {@code scanCount} H2 annotates each {@code EXPLAIN ANALYZE} plan node
 * with, summed over the plan. A count of rows visited is the same number on a fast machine and a
 * loaded one, which is what lets a tier that must not fail for being slow still hold a cost claim.
 * No duration is asserted anywhere here. Scan counts and wall clocks are different claims, so no
 * figure measured by a timing probe transfers into this test.
 *
 * <p><b>Both axes come off the booted store.</b> Registrations from
 * {@link Materializations#registrations}, readers from
 * {@link MaterializeDependencies#registrationsReachedByView}. A hand-kept list of either would rot
 * as views are added, and the reachability walk is also what keeps the matrix small: a registration
 * can only change what a relation costs if that relation's derivation names its target, so most of
 * the {@value #READERS_IN_SCHEMA} views times {@code meta_materialize}'s registrations do not
 * exist as cells.
 *
 * <p><b>Two pinned sets, both asserted by equality rather than as ceilings or allowlists.</b>
 * Equality is what gives the ratchet: adding a pair fails the build, and so does removing one, so the
 * day a lever lands the assertion fails until the row goes rather than the row surviving as a stale
 * exemption nobody is forced to revisit. {@code MaterializeRegistryGateTest}'s {@code HAND_WRITTEN}
 * set is the precedent for a pinned roster of deliberate exceptions in this family.
 *
 * <p>Joining {@code ExemptionRegistry}, this module's shipped exemption mechanism, is the right move
 * for the second and third mechanism that needs one and not for the first: its {@code Obligation} is
 * typed on {@code Class<?>} keys throughout and a pair of relation names is not a class, so joining
 * means generifying the row's key type, and its {@code Exemption} arms are a coverage-triage taxonomy
 * whose own javadoc argues against arms no population confirms, so an accepted-cost-regression arm
 * would have to be added. Two changes to a shared mechanism for one test is the wrong trade. Its
 * discovery guard does not force the issue either, firing on a static {@code Map<..., Exemption>}
 * where these are sets of strings. Stated here so the next author of such a set knows where the line
 * is rather than rediscovering it.
 */
@PipelineTier
class DerivedReadCostTest {

    /**
     * Repetitions of the fixture's node cluster. Chosen for a stated reason rather than by taste, and
     * now for two of them, because what this size buys is scale-dependent on both of the gate's
     * pinned sets.
     *
     * <p>{@link #KNOWN_NON_MONOTONIC} is scale-dependent, and not monotonically: it is empty at one
     * unit, where the gate would see nothing at all, three pairs at four units, four at eight, and
     * the nine above at twelve, where several borderline cells' plans flip against the statistics
     * that size implies. Every twelve-only pair was answered as an index question and declined on
     * measurement, per the set's own javadoc, so what twelve adds is visibility rather than noise.
     * Twelve is kept because it is the size that ships and the fixture may grow and may not shrink;
     * the set is pinned at the size the gate actually runs, and the two smaller sizes are recorded
     * here so the next re-pin knows the boundary moved before and can move again. Those three
     * figures were re-taken when the fixture grew its input surface, because a size boundary is a
     * measurement like any other and does not survive the fixture it was taken on.
     *
     * <p>The fixture may grow and may not shrink. A smaller one is the single change that would make
     * this gate pass while seeing nothing, the registrations existing precisely because a rule is
     * re-evaluated many times over a schema of real size.
     */
    private static final int UNITS = 12;

    /** Views in the fact schema, of which {@value #READERS_WITH_CELLS} reach a registration. */
    private static final int READERS_IN_SCHEMA = 98;

    /** Views whose derivation reaches at least one registration's target. */
    private static final int READERS_WITH_CELLS = 59;

    /**
     * The cells the domain holds: one per (registration, reaching relation) pair. Stated so the matrix
     * cannot grow silently as views are added; a new view that puts new cells in the domain fails this
     * figure until somebody has looked at what it costs.
     *
     * <p>It moves down as well as up, and a registration is what moves it both ways at once. The
     * reachability walk records a registration when it meets that registration's target and stops
     * there rather than descending, so registering a relation cuts every reader's reach at it: each
     * reader that reached a registration only through the newly registered relation loses that cell,
     * while the new registration and its {@code _live} view add cells of their own. Registering
     * {@code intent_node_id_instruction} was the first change to make the figure fall, by five on
     * net, and a delta rather than a pair of absolutes because every new view moves the baseline.
     * So a drop here is not the matrix quietly seeing less; it is cost moving off a reader and onto
     * a refresh, and the refresh is a view in this domain and priced like any other.
     */
    private static final int CELLS = 133;

    /**
     * The multiple of the registered side's own wall clock allowed to the unregistered side before the
     * cell is recorded as unmeasurable rather than compared. Relative rather than absolute for the
     * objection {@link RunawayRelation}'s javadoc raises against a fixed threshold, that one small
     * enough to be reliable on one machine is a flake on another: both sides of a cell are timed in
     * the same run on the same machine, so a loaded machine slows both and the ratio holds. Large
     * because the gap the register documents is seconds against never, not one shape against a
     * slightly slower one.
     */
    private static final long BUDGET_MULTIPLE = 50;

    /**
     * Floor under {@link #BUDGET_MULTIPLE}, in milliseconds, for the cells whose registered side is
     * a millisecond or two: fifty times nearly nothing is nearly nothing, and
     * {@link ReadBudget.Bounded} refuses a non-positive figure outright.
     *
     * <p>Generous on purpose, and the direction is what makes that safe: a larger floor compares
     * more cells and can only make this gate stricter, never more permissive, because a cell inside
     * its budget is asserted on and a cell outside it is merely recorded. The figure is set from the
     * slowest unregistered side that finishes, which is under seven seconds on this fixture, so that
     * every cell is compared and {@link #KNOWN_EXHAUSTED} stays empty on a machine of any speed.
     *
     * <p>It was two seconds, and two seconds was too tight to be a fact about the code. Two cells'
     * unregistered sides land within a factor of two of that figure, so which of them appeared in
     * {@link #KNOWN_EXHAUSTED} varied from run to run on one machine, and an equality-pinned set
     * whose contents depend on load is a flake wearing a ratchet's clothes. The distinction
     * {@link #BUDGET_MULTIPLE} exists to draw is seconds against never; a three-second read against
     * a three-second budget is not that distinction.
     */
    private static final long BUDGET_FLOOR_MILLIS = 30_000;

    /**
     * The budget {@link #aCellThatCannotAnswerIsRecordedRatherThanFailed} gives its own cell, which
     * is small where {@link #BUDGET_FLOOR_MILLIS} is generous and for the reason that makes both
     * right. That cell's relation cannot terminate by construction, so no budget lets it finish and
     * the only thing a larger one buys is waiting; every other cell's relation does terminate, and
     * there the budget must be clear of how long that takes.
     */
    private static final long RUNAWAY_BUDGET_MILLIS = 2_000;

    /**
     * The pairs where the registered shape costs more, {@code registration|reader}, each one a finding
     * rather than a tolerance.
     *
     * <p>Three mechanisms, each answered by measurement, and the set holds nothing else. The first
     * two are the pruning
     * an inlined view body offered and a table cannot: the argmapping parameter-type reader's
     * site-literal arms pruned the pair view's union to one arm apiece and now visit the whole table
     * per arm (815 scans registered against 357), and the errors-field member view drives from the
     * whole relation, whose inlined predicates let the fused plan skip rows the plain table join
     * visits (916 against 761). Each was answered as an
     * index question first: every index shape tried on either target moved no reader at all or made a
     * dear one worse, so the scans are the readers' own cost of standing on a table, sub-millisecond
     * against the seconds each registration buys.
     *
     * <p>The segment-binding reader stood beside the parameter-type one on the same mechanism, at
     * 433 scans against 391, and left when the fixture grew an input surface: the extra arguments
     * change what the planner knows about the pair relation and the arm stops being the cheaper
     * shape. Recorded rather than simply deleted, because the row leaving on a fixture change is a
     * different fact from a row leaving because a lever landed, and the difference is what the next
     * author needs.
     *
     * <p>The last four share one mechanism and one named lever. Registering the carrier relation
     * changed the planner's join order in the hop and the seat: both now reach {@code graphql_field}
     * through a join on its {@code named_type} column, which no key serves (the primary key leads
     * with the field's own coordinate), so one plan node seeks by graph alone and visits the whole
     * field census per driving row. The hop reads 19619 scans registered against 6820 with the
     * carrier unregistered and 1570 with the spelled table unregistered; the seat 27531 against 26255
     * and 11043. The wall clocks are one and ten milliseconds respectively, against the tens of
     * seconds per generation the carrier registration buys on a real schema. Every lever inside the
     * registration's own scope was measured and moved nothing: index shapes on the carrier target
     * (identical to the scan), a FROM-order restructure of the hop (H2 reorders base-table join
     * graphs freely), and driving the hop from the payload-producer relation instead of the reverse
     * named-type probe (equal answers, 17003 scans). The lever that works is a declared index on
     * {@code graphql_field}'s named-type coordinate, measured at 2137 / 10049 scans for hop and seat
     * and clearing three of these four rows, but an authored index on a captured base table is a
     * schema-wide discipline question (its reader axis is the whole derived stratum, its cost is on
     * every capture's write path, and the index-comment gate does not reach it), so it is filed on
     * the roadmap with these measurements rather than shipped on four readers' evidence. These rows
     * are pinned rather than tolerated because a tolerance would be a number, and a number here is
     * the one thing this gate is built without; the day that index lands, the equality assertion
     * deletes them.
     *
     * <p>The last four are the instrument's own floor rather than work, and they are worth reading
     * before adding anything that looks like them. The three input-field resolution relations each
     * name the reference-step hop relation, and against the registered target they visit exactly
     * four rows more than against the source view: 189 against 185, 345 against 341, 1110 against
     * 1106. H2 charges a table visit at least one scan per naming where a view whose evaluation
     * short-circuits is charged none, and four namings is what these three bodies make between the
     * walk's anchor and its recursive term. The wall clocks run the other way and decisively, two
     * milliseconds against thirteen, four against fourteen and six against twenty-six, which is the
     * shape that says this is the counter's floor and not a cost. No index question arises: the
     * target already carries one, and a difference of four scans is not an index's to move.
     *
     * <p>The fourth is the same floor counted three times over: the input-field role relation, 4845
     * against 4833, whose body names the column-match relation in three of its arms, so the walk's
     * four namings are made three times. Twelve scans on 4845 is a quarter of one per cent, and here
     * the clocks are a wash rather than decisively better, sixty milliseconds against fifty-nine,
     * which is what a difference that size looks like on a clock. Three namings looks like something
     * to fold into one, and the one-pass shape that would do it was written and measured and is not
     * an improvement: see that relation's own comment, which carries the figures and the reason.
     *
     * <p>Three larger pairs stood here until the targets were indexed, and how they left is worth
     * knowing before adding more. They were not the registrations' fault and no reader had to be
     * restructured: a materialized target was the only kind of table in this schema with no key on it,
     * so a registered view's join against one was a full scan of it, per driving row where the reader
     * correlates and per iteration where it recurses. Indexes on the targets and current statistics
     * after each refill answered all three, and the registrations they were charged to are unchanged.
     * So a new large pair here is a question about the target's index before it is a question about
     * the registration.
     *
     * <p>A trio left a second way, which is worth knowing because nothing was measured to send it.
     * Three readers of {@code intent_node_id_instruction} stood here charged to
     * {@code intent_argument_scope_table}: the encode, the decode slot, and the decode defect above
     * it. All three were small and flat, the instrument's own floor rather than work, H2 charging a
     * table visit at least one scan per naming where a view whose evaluation short-circuits is
     * charged none. Registering {@code intent_node_id_instruction} removed them by removing their
     * cells: none of the three names the scope table itself, all three reached that registration
     * through the instruction rule, and the walk now stops at the instruction's own target instead of
     * descending into what the rule reads. The cost did not evaporate, it moved: the rule still reads
     * the scope table, once per capture, in the refresh of
     * {@code intent_node_id_instruction_live}, which is a view in this domain and holds its own cell
     * against that registration monotonically. Read that as the shape to check the next time a
     * registration deletes rows here: a pair that leaves because its reader got cheaper and a pair
     * that leaves because its reader stopped reaching are different facts, and only the first is
     * about the registration it was charged to.
     */
    private static final Set<String> KNOWN_NON_MONOTONIC = Set.of(
        // The pruning an inlined body offered and a table cannot; measured index-free above.
        "intent_argmapping_pair|intent_argmapping_bound_parameter_type",
        "intent_errors_field|intent_errors_field_member",
        // The unindexed named-type join; the lever is filed on the roadmap, measured above.
        "intent_carrier_data_field|intent_carrier_routine_hop",
        "intent_carrier_data_field|intent_mutation_routine_seat",
        "intent_spelled_table|intent_carrier_routine_hop",
        "intent_spelled_table|intent_mutation_routine_seat",
        // The instrument's own floor, four scans apiece; measured above.
        "intent_field_reference_step_hop|intent_input_field_reference_step_target",
        "intent_field_reference_step_hop|intent_input_field_column_scope",
        "intent_field_reference_step_hop|intent_input_field_column_match",
        "intent_field_reference_step_hop|intent_input_field_filter_role",
        "intent_field_reference_step_hop|intent_input_field_carrier_role");

    /**
     * The cells whose unregistered side did not answer inside its budget, and so were recorded rather
     * than compared. Empty, and kept empty deliberately rather than observed to be: every cell's
     * unregistered side terminates on this fixture, and {@link #BUDGET_FLOOR_MILLIS} is set clear of
     * the slowest of them so that which cells appear here is a fact about the code and not about how
     * loaded the machine was. A row appearing here is therefore a relation that stopped terminating,
     * which is worth a look rather than a number to raise.
     *
     * <p>{@link #aCellThatCannotAnswerIsRecordedRatherThanFailed} is what shows the arm working, over
     * a relation made non-terminating by construction rather than by being slow, because a cell that
     * is merely slow could stop being so on a faster machine.
     */
    private static final Set<String> KNOWN_EXHAUSTED = Set.of();

    @TempDir
    static Path tmp;

    private static Map<String, Set<String>> reached;
    private static List<Materializations.Registration> registrations;
    private static final Map<String, Long> registeredScans = new TreeMap<>();
    private static final Map<String, Long> registeredMillis = new TreeMap<>();
    private static final Set<String> observedNonMonotonic = new TreeSet<>();
    private static final Set<String> observedExhausted = new TreeSet<>();
    private static int observedCells;

    /**
     * Prices the whole matrix once for the class: the baseline store gives every reader's registered
     * shape, then one store per registration gives the unregistered shape of the readers that reach
     * it. One store per registration because the swap spends the store it is installed into, and the
     * measurement is per registration rather than per cell, so this is eight captures and not a
     * hundred and two.
     */
    @BeforeAll
    static void priceTheMatrix() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        String sdl = scaledSdl(UNITS);

        try (var store = CapturedStore.ofCatalog(tmp.resolve("registered"), sdl, jooq)) {
            reached = MaterializeDependencies.registrationsReachedByView(store.dsl());
            registrations = Materializations.registrations(store.dsl());
            for (var cell : reached.entrySet()) {
                if (!cell.getValue().isEmpty()) {
                    var timed = scans(store, cell.getKey(), new ReadBudget.Unbounded());
                    registeredScans.put(cell.getKey(), timed.scans());
                    registeredMillis.put(cell.getKey(), timed.millis());
                }
            }
        }

        for (var registration : registrations) {
            List<String> readers = readersOf(registration);
            try (var store = CapturedStore.ofCatalog(
                    tmp.resolve("unregistered-" + registration.targetTableName()), sdl, jooq)) {
                UnregisteredRelation.install(store.dsl(), registration);
                for (String reader : readers) {
                    observedCells++;
                    String cell = registration.targetTableName() + "|" + reader;
                    var timed = scans(store, reader, budgetFor(reader));
                    if (timed.exhausted()) {
                        observedExhausted.add(cell);
                    } else if (timed.scans() < registeredScans.get(reader)) {
                        observedNonMonotonic.add(cell);
                    }
                }
            }
        }
    }

    // ===== The four assertions =====

    /**
     * The domain is the size this test says it is, on both axes and in cells. The reachability walk
     * is what makes the third figure much smaller than the product of the first two, and pinning it
     * is what stops a new view adding cells nobody priced.
     */
    @Test
    void theDomainIsTheSizeThisTestStates() {
        assertThat(reached).as("views in the fact schema").hasSize(READERS_IN_SCHEMA);
        assertThat(reached.values().stream().filter(regs -> !regs.isEmpty()).count())
            .as("views whose derivation reaches a registration").isEqualTo(READERS_WITH_CELLS);
        assertThat(observedCells)
            .as("cells priced: one per registration and reaching relation. A new view that adds"
                + " cells has to be priced rather than absorbed")
            .isEqualTo(CELLS);
    }

    /**
     * The claim itself. Every cell the pinned set does not name must be monotonic, and the pinned set
     * must be exactly what was observed, so a pair that stops being a regression fails this test
     * until its row goes.
     */
    @Test
    void aRegistrationDoesNotCostAnotherRelationMoreThanItSaves() {
        assertThat(observedNonMonotonic)
            .as("relations whose registered shape visits more rows than its unregistered one."
                + " Equality both ways: a new pair is a regression to answer, and a pair that has"
                + " stopped being one is a row to delete")
            .containsExactlyInAnyOrderElementsOf(KNOWN_NON_MONOTONIC);
    }

    /**
     * The cells recorded as unmeasurable are exactly the ones written down. Same ratchet as the pinned
     * pairs, and for the same reason: a cell that quietly stops being measured is a gate quietly
     * getting weaker.
     */
    @Test
    void theCellsThatCouldNotBeComparedAreExactlyTheOnesRecorded() {
        assertThat(observedExhausted)
            .as("cells whose unregistered side did not answer inside its own relative budget")
            .containsExactlyInAnyOrderElementsOf(KNOWN_EXHAUSTED);
    }

    /**
     * The pass-on-exhaustion arm, shown firing rather than asserted in prose. A relation the decode
     * family reads is made non-terminating by {@link RunawayRelation}, whose swap is structural rather
     * than slow, and the cell is then recorded as unmeasurable instead of failing the gate.
     *
     * <p>Passing is the only reading of a non-terminating unregistered side that is not a lie:
     * non-termination is the strongest possible form of "materializing did not make this worse". What
     * must not happen is passing <em>silently</em>, which is what the pinned set above is for.
     *
     * <p>And the cut is safe in the one direction that matters. The gate fails when the registered
     * shape costs more, so a regression is a cell whose <em>unregistered</em> side is the cheap one,
     * and a cheap side finishes. A budget on the unregistered side can therefore only ever discard
     * cells whose unregistered side was slow, and a slow unregistered side is evidence for the
     * registration rather than against it. The arm cannot swallow the defect the gate hunts, because
     * that defect arrives fast.
     */
    @Test
    void aCellThatCannotAnswerIsRecordedRatherThanFailed() {
        var ctx = TestConfiguration.testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var registration = registrations.stream()
            .filter(r -> r.targetTableName().equals("intent_resolved_type_binding"))
            .findFirst().orElseThrow();

        try (var store = CapturedStore.ofCatalog(
                tmp.resolve("runaway"), scaledSdl(1), jooq)) {
            UnregisteredRelation.install(store.dsl(), registration);
            RunawayRelation.install(store.dsl(), "intent_bound_table");
            var timed = scans(store, "intent_resolved_type_binding",
                new ReadBudget.Bounded(RUNAWAY_BUDGET_MILLIS));
            assertThat(timed.exhausted())
                .as("a cell whose unregistered side cannot terminate is recorded, not compared")
                .isTrue();
        }
    }

    // ===== Helpers =====

    private static List<String> readersOf(Materializations.Registration registration) {
        return reached.entrySet().stream()
            .filter(cell -> cell.getValue().contains(registration.sourceViewName()))
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * The budget for one cell's unregistered side: a multiple of what the registered side of that
     * same cell took, floored. Never an assertion; it decides only whether the cell is compared or
     * recorded, so slowness can make this gate more permissive and can never make it red.
     */
    private static ReadBudget budgetFor(String reader) {
        return new ReadBudget.Bounded(Math.max(BUDGET_FLOOR_MILLIS,
            BUDGET_MULTIPLE * registeredMillis.getOrDefault(reader, 0L)));
    }

    private record Timed(long scans, long millis, boolean exhausted) {}

    /** H2 annotates each plan node with the rows it visited. This is the whole instrument. */
    private static final Pattern SCAN_COUNT = Pattern.compile("scanCount: (\\d+)");

    /**
     * What one relation's whole evaluation visits, read through a reader minted after any swap. The
     * mint matters: {@link UnregisteredRelation} states why a session that already read these views
     * would not see the swap at all.
     */
    private static Timed scans(CapturedStore store, String relation, ReadBudget budget) {
        try (var reader = store.reader(budget)) {
            long started = System.nanoTime();
            StoreAnswer<String> answer = reader.read(dsl -> dsl
                .fetch("EXPLAIN ANALYZE SELECT * FROM " + relation)
                .get(0).get(0, String.class));
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (!(answer instanceof StoreAnswer.Answered<String> plan)) {
                return new Timed(-1, millis, true);
            }
            long total = 0;
            Matcher counts = SCAN_COUNT.matcher(plan.value());
            while (counts.find()) {
                total += Long.parseLong(counts.group(1));
            }
            return new Timed(total, millis, false);
        }
    }

    /**
     * The fixture: {@code units} repetitions of a film/language/inventory/store cluster of node types
     * over real catalog keys, plus one routine field, the mutations, and a routine-carrier cluster
     * per unit, so that nodehood, reference chains, node-id decoding, argument mapping and the
     * carrier family all have rows.
     *
     * <p>The routine-carrier cluster is a mutation-root {@code @routine} field per unit returning a
     * payload type that wraps one nullable data field beside an errors channel, the channel a union
     * whose one member carries {@code @error}. That shape is what populates
     * {@code intent_errors_field} and the relations over it ({@code intent_carrier_data_field},
     * {@code intent_field_error_channel}, {@code intent_mutation_routine_seat},
     * {@code intent_carrier_routine_hop}), all of which held no rows here before it: the fixture's
     * only other {@code @routine} sits on the Query root, so the seat relation was empty, and
     * {@code Film0}'s {@code @reference} fields disqualify it from the carrier view. Scaled with the
     * units so those targets hold rows proportional to schema size, like everything else here.
     *
     * <p>Every registered target is populated by this schema and so is every reader this gate prices
     * except the defect relations, which hold rows only on a schema with the defect in it and whose
     * emptiness here is the fixture being well-formed rather than being thin. A schema of
     * {@code @table}-bound types with a single scalar field, which is what the scaled fixtures
     * elsewhere in the reactor use, leaves much of {@code meta_materialize}'s roster and most of the
     * {@value #READERS_WITH_CELLS} readers empty, and a gate over empty relations measures the
     * instrument's floor and nothing else.
     *
     * <p>The per-unit filter input is in that list for the same reason and is the second such arm.
     * Before it the fixture's whole input surface was two fields on two mutation payloads, neither
     * scaled with the units and neither carrying a {@code @reference}, so the input-field resolution
     * relations held a couple of flat rows and their reference walk held none. A gate over relations
     * that size prices the counter rather than the work, and it showed: with the surface flat, the
     * resolving-table registration read as a regression against all three of its readers, and with
     * the surface scaled every one of those three went monotonic. The input is deliberately three
     * shapes rather than one, a plain column name, a nested input object so the descent has depth,
     * and a {@code @reference}-pathed field so the walk has a chain to follow, because the three
     * relations fork on exactly those.
     *
     * <p>{@code inventoryForFilm} is in that list for one target's sake and is the arm to understand
     * before touching it. A node-id argument populates the decode walk's hop relations only where the
     * argument's own scope table differs from the node type's table <em>and</em> exactly one foreign
     * key joins the two, which is what the endpoint view's {@code DISCOVERED_KEY} arm requires. The
     * {@code storeForFilm} arm above looks like it should qualify and does not: film reaches store
     * through inventory rather than directly, so no single key joins them and the endpoint contributes
     * no hop. Returning inventory instead gives the one key inventory declares on film. Without this
     * arm the hop-column target holds no rows at any size and its cell in this gate is a comparison
     * between two readings of an empty table, which is the state that made this test's own claim about
     * a populated fixture untrue of the hop-column registration. With it, that cell is the widest ratio
     * in the matrix by wall clock, tens of milliseconds registered against seconds unregistered, which
     * is the shape that registration's own registry reason describes.
     *
     * <p>{@code media} is the third such arm and it is a per-unit union of two of the cluster's own
     * bound types with one filter argument over a column both their tables carry. Before it the
     * fixture had no multi-table polymorphic root at all, so the participant fan-out held no rows at
     * any size: the field scope's participant arm, and therefore the branch multiplicity every
     * relation below it inherits, priced as an empty relation. That is the state a previous increment
     * of this work walked into from the other direction, reading a shape choice off a fixture whose
     * units made both correlated arms of a ranked view unselective, and the lesson is the same one in
     * reverse. A gate blind to a shape does not price it conservatively; it prices the instrument's
     * floor and reports a number. The union's members are the existing {@code Film} and
     * {@code Inventory} types rather than new ones, so the arm adds branches to price without adding
     * a table, and the filter column is the key one of them declares on the other, which is what
     * makes the name resolve on both branches instead of on one.
     */
    private static String scaledSdl(int units) {
        var sdl = new StringBuilder("""
            interface Node { id: ID! }
            input FilmInput { title: String }
            input FilmKeyInput { filmId: Int! @field(name: "film_id") }
            type Rental @table(name: "rental") { rentalId: Int @field(name: "rental_id") }
            """);
        sdl.append("type Mutation {\n").append("""
              createFilm(in: FilmInput!): Film0 @mutation(typeName: INSERT)
              createFilms(in: [FilmInput!]!): [Film0!]! @mutation(typeName: INSERT)
              deleteFilm(in: FilmKeyInput!): ID @mutation(typeName: DELETE, table: "film")
            """);
        IntStream.range(0, units).forEach(i -> sdl.append("""
              rentFilm%1$d(inventoryId: Int!, customerId: Int!): RentFilmPayload%1$d
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            """.formatted(i)));
        sdl.append("}\n");
        IntStream.range(0, units).forEach(i -> sdl.append("""
            type RentFilmFailed%1$d @error(handlers: [{
                handler: GENERIC,
                className: "org.jooq.exception.IntegrityConstraintViolationException"
              }]) {
              path: [String!]!
              message: String!
            }
            union RentFilmError%1$d = RentFilmFailed%1$d
            type RentFilmPayload%1$d {
              rental: Rental
              errors: [RentFilmError%1$d]
            }
            """.formatted(i)));
        IntStream.range(0, units).forEach(i -> sdl.append("""
            input NestedFilmFilter%1$d {
              releaseYear: Int @field(name: "release_year")
            }
            input FilmFilter%1$d {
              title: String
              nested: NestedFilmFilter%1$d
              inStore: Int @field(name: "store_id")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film%1$d implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
              title: String
              releaseYear: Int @field(name: "release_year")
              language: Language%1$d @reference(path: [{key: "film_language_id_fkey"}])
              inventory: [Inventory%1$d!]! @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Language%1$d implements Node @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
              name: String
            }
            type Inventory%1$d implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
              id: ID! @nodeId
              film: Film%1$d @reference(path: [{key: "inventory_film_id_fkey"}])
              store: Store%1$d @reference(path: [{key: "inventory_store_id_fkey"}])
            }
            type Store%1$d implements Node @table(name: "store") @node(keyColumns: ["store_id"]) {
              id: ID! @nodeId
              inventory: [Inventory%1$d!]! @reference(path: [{key: "inventory_store_id_fkey"}])
            }
            union Media%1$d = Film%1$d | Inventory%1$d
            """.formatted(i)));
        sdl.append("type Query {\n").append("""
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            """);
        IntStream.range(0, units).forEach(i -> sdl.append("""
              films%1$d(filter: FilmFilter%1$d): [Film%1$d!]!
              film%1$d(id: ID! @nodeId(typeName: "Film%1$d")): Film%1$d
              filmsByKey%1$d(film_id: [ID] @lookupKey): [Film%1$d!]!
              storeForFilm%1$d(id: ID! @nodeId(typeName: "Film%1$d")): [Store%1$d!]! @reference(path: [
                {key: "inventory_film_id_fkey"},
                {key: "inventory_store_id_fkey"}
              ])
              inventoryForFilm%1$d(id: ID! @nodeId(typeName: "Film%1$d")): [Inventory%1$d!]!
              media%1$d(filmId: Int @field(name: "film_id")): [Media%1$d!]!
            """.formatted(i)));
        return sdl.append("}\n").toString();
    }
}
