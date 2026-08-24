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
 * the {@value #READERS_IN_SCHEMA} views times seven registrations do not exist as cells.
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
     * Repetitions of the fixture's node cluster. Chosen for a stated reason rather than by taste: the
     * set of non-monotonic pairs is <em>scale-dependent below this size</em>. One unit reports eleven
     * pairs, all of them the step-hop registration and all of them the artifact described on
     * {@link #KNOWN_NON_MONOTONIC}; four units and twelve units report the same six. Twelve is taken
     * rather than four to sit clear of that boundary, at the cost of wall clock this test's own
     * javadoc owns.
     *
     * <p>The fixture may grow and may not shrink. A smaller one is the single change that would make
     * this gate pass while seeing nothing, the registrations existing precisely because a rule is
     * re-evaluated many times over a schema of real size.
     */
    private static final int UNITS = 12;

    /** Views in the fact schema, of which {@value #READERS_WITH_CELLS} reach a registration. */
    private static final int READERS_IN_SCHEMA = 84;

    /** Views whose derivation reaches at least one registration's target. */
    private static final int READERS_WITH_CELLS = 47;

    /**
     * The cells the domain holds: one per (registration, reaching relation) pair. Stated so the matrix
     * cannot grow silently as views are added; a new view that puts new cells in the domain fails this
     * figure until somebody has looked at what it costs.
     */
    private static final int CELLS = 105;

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
     */
    private static final long BUDGET_FLOOR_MILLIS = 2_000;

    /**
     * The pairs where the registered shape costs more, {@code registration|reader}, each one a finding
     * rather than a tolerance. Three of these are large and grow with the schema, which is what
     * distinguishes them from a fixture artifact: the delta at four units and at twelve differ by
     * roughly the ratio of the schemas.
     *
     * <p>The step-hop pairs are the item to answer first. That registration was made to take a
     * node-id decode read from about fifty seconds to about thirteen, and it does; what it also does
     * is make these two relations visit an order of magnitude more rows, because reading the
     * materialized target is a full scan charged once per naming while the rule it replaced is a cheap
     * join these two readers were pruning. The binding pair is the same shape one order down.
     *
     * <p>The three small pairs are the instrument's floor rather than a cost: H2 charges a table visit
     * at least one scan per naming where a view whose evaluation short-circuits is charged none, so a
     * relation named a few times can read a few scans dearer while doing strictly less work. They are
     * pinned rather than tolerated because a tolerance would be a number, and a number here is the one
     * thing this gate is built without.
     */
    private static final Set<String> KNOWN_NON_MONOTONIC = Set.of(
        // Large, and growing with the schema: the lever item's own subject.
        "intent_field_reference_step_hop|intent_field_reference_step_target",
        "intent_field_reference_step_hop|intent_field_column_scope_live",
        "intent_resolved_type_binding|intent_argument_scope_table_live",
        // Small, and flat: the per-naming floor of the instrument.
        "intent_argument_scope_table|intent_node_id_encode",
        "intent_argument_scope_table|intent_node_id_decode_defect",
        "intent_argument_scope_table|intent_node_id_decode_slot");

    /**
     * The cells whose unregistered side did not answer inside its budget, and so were recorded rather
     * than compared. One row, and it is the strongest form of the claim this gate makes rather than a
     * gap in it: the carrier relation names the data-channel rule four times, three of them in
     * correlated {@code NOT EXISTS} arms, so reversing that registration puts the rule back to being
     * re-derived per driving row and the read no longer lands inside a budget fifty times its
     * registered side. The registry row carries the timings.
     *
     * <p>Only the direct reader is here. The three relations that read the carrier
     * ({@code intent_mutation_routine_seat}, {@code intent_carrier_routine_hop},
     * {@code intent_field_error_channel}) are cells of the same registration and each answers inside
     * its budget, because the budget is relative to that reader's own registered figure and those are
     * larger than the carrier's.
     *
     * <p>{@link #aCellThatCannotAnswerIsRecordedRatherThanFailed} is still what shows the arm working
     * on a relation made non-terminating by construction rather than by being slow; this row is the
     * arm firing on a relation that is merely very slow, which is the case it was written for.
     */
    private static final Set<String> KNOWN_EXHAUSTED = Set.of(
        "intent_type_data_channel|intent_carrier_data_field");

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
                new ReadBudget.Bounded(BUDGET_FLOOR_MILLIS));
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
     * over real catalog keys, plus one routine field and the mutations, so that nodehood, reference
     * chains, node-id decoding and argument mapping all have rows.
     *
     * <p>Every registered target is populated by this schema and so is every reader this gate prices
     * except the defect relations, which hold rows only on a schema with the defect in it and whose
     * emptiness here is the fixture being well-formed rather than being thin. A schema of
     * {@code @table}-bound types with a single scalar field, which is what the scaled fixtures
     * elsewhere in the reactor use, leaves four of the seven targets and forty-one of the
     * {@value #READERS_WITH_CELLS} readers empty, and a gate over empty relations measures the
     * instrument's floor and nothing else.
     */
    private static String scaledSdl(int units) {
        var sdl = new StringBuilder("""
            interface Node { id: ID! }
            input FilmInput { title: String }
            input FilmKeyInput { filmId: Int! @field(name: "film_id") }
            type Rental @table(name: "rental") { rentalId: Int @field(name: "rental_id") }
            type Mutation {
              createFilm(in: FilmInput!): Film0 @mutation(typeName: INSERT)
              createFilms(in: [FilmInput!]!): [Film0!]! @mutation(typeName: INSERT)
              deleteFilm(in: FilmKeyInput!): ID @mutation(typeName: DELETE, table: "film")
            }
            """);
        IntStream.range(0, units).forEach(i -> sdl.append("""
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
            """.formatted(i)));
        sdl.append("type Query {\n").append("""
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            """);
        IntStream.range(0, units).forEach(i -> sdl.append("""
              films%1$d: [Film%1$d!]!
              film%1$d(id: ID! @nodeId(typeName: "Film%1$d")): Film%1$d
              filmsByKey%1$d(film_id: [ID] @lookupKey): [Film%1$d!]!
              storeForFilm%1$d(id: ID! @nodeId(typeName: "Film%1$d")): [Store%1$d!]! @reference(path: [
                {key: "inventory_film_id_fkey"},
                {key: "inventory_store_id_fkey"}
              ])
            """.formatted(i)));
        return sdl.append("}\n").toString();
    }
}
