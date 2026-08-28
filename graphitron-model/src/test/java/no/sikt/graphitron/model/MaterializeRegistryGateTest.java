package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.MaterializeDependencies;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.RefreshStages;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Closes {@code meta_materialize}'s authored rows against the observed schema, the shape
 * {@code meta_relation_family} already uses for the family roster.
 *
 * <p>The registry is a claim about relations that have to exist, in kinds that have to match, with
 * a shape that makes {@code INSERT INTO target SELECT * FROM source} a statement whose result is
 * the view's own rows. Every clause of that claim is checked here rather than argued in prose,
 * because the design's whole safety argument is that a registration changes no answer, and a
 * mismatched column list would break it silently in the one direction nobody reads: the target
 * would hold rows, they would simply be the wrong ones.
 *
 * <p>Structural only, over a booted schema with no captured rows in it; the only rows in play are
 * the DDL's own registry and the dependency edges the bootstrap derived from it. Whether a
 * target's rows actually equal its view's rows is the other half of the claim, and answering it
 * needs a capture rather than a schema, so it sits with the schema gates that already have one.
 */
class MaterializeRegistryGateTest {

    /**
     * The {@code intent_} base tables written by a hand-written derivation rather than by the
     * materializer. Each argues impossibility in its own table comment: no view could state its
     * rule. Enumerated so the gate below can tell a deliberate hand-written derivation from a
     * bespoke materializer someone wrote beside the mechanism instead of inside it.
     */
    private static final Set<String> HAND_WRITTEN = Set.of(
        "intent_type_domain",
        "intent_type_backing_class",
        "intent_authored_claim_rejection",
        "intent_input_occurrence_path",
        "intent_input_occurrence_path_step");

    /**
     * How many registrations the register holds, and how many stages its refresh takes: the
     * register's shape, pinned by equality so it cannot change while nobody is looking.
     *
     * <p>The read side of a registration is already ratcheted. A new one necessarily moves
     * {@code DerivedReadCostTest}'s equality-pinned reader and cell figures, so it cannot land
     * without somebody editing a number and confronting what it costs every relation that reaches
     * it. Nothing held the refresh side, and the gap is not hypothetical: a registration added for a
     * reason that had nothing to do with materialization went in at what is now stage five, moved
     * twelve registrations across four families down a stage each, and was noticed by nobody,
     * because no file anybody edited and no assertion anywhere mentions the register's shape.
     *
     * <p><b>Neither figure is a budget and the depth least of all.</b> Timing the pass with
     * {@link no.sikt.graphitron.model.derive.RefreshProgress} against the stage partition
     * {@link RefreshStages} computes says the ordering costs about four percent: on a schema of real
     * size the serial pass lands within that of what a perfectly parallel refresh of the same
     * refills could reach, because the dear registrations sit in stages holding nothing else. So a
     * deeper register is not by itself a dearer one, and a registration that raises either figure may
     * raise it in the commit that argues for it. What the pin prevents is the shape moving in a
     * commit that argues for something else, which is exactly how it last moved. {@link #NO_INDEX}
     * is the model: a figure that has to be edited deliberately, not a ceiling nobody may exceed.
     */
    private static final int REGISTRATIONS = 22;

    /**
     * Stages the refresh takes, the register's depth.
     *
     * <p>Twelve until {@code intent_node_id_decode_column} and {@code intent_input_field_carrier_role}
     * were registered, a stage each, and both for the same reason rather than because the register
     * grew: each already sat on the chain from {@code intent_node_id_decode_hop_column} to the two
     * mutation payload views as an unregistered intermediate the reachability walk saw straight
     * through, and registering one turns a link the walk was passing over into a stage the refresh
     * has to wait for. The chain is now the hop column, the decode column, the carrier role and the
     * payload views where it was the hop column and the payload views. Depth bought a fall in cost
     * rather than a rise, which is the point {@link #REGISTRATIONS}' note makes in the abstract and
     * this pair makes concretely: the payload column refresh statement fell about thirtyfold on the
     * schema those two registrations were measured against.
     *
     * @see #REGISTRATIONS
     */
    private static final int REFRESH_STAGES = 14;

    /**
     * The registered targets carrying no index, each with the argument that says why. A roster
     * on {@link #HAND_WRITTEN}'s model and asserted the same way, by equality in both directions,
     * so a target that later earns an index fails this test until its row goes rather than the row
     * surviving as an exemption nobody revisits.
     *
     * <p>A roster rather than a bare "every target carries an index", because an index is a cost
     * on every refresh and wants a reader to justify it, and these have none.
     *
     * <p>The arguments come in two kinds, and the difference is worth keeping visible. Most were
     * measured as several index shapes, over every view whose derivation reaches the target, with
     * statistics current on both sides so the figure is the index's own: a lever existed and was
     * declined on what it cost. {@code intent_node_id_instruction}'s row has no measurement because
     * it has no candidate to measure, every reader reaching the target by scanning it whole rather
     * than by probing a coordinate. A row of that kind is falsified by a new reader rather than by
     * a new figure, so it is the reader roster that wants re-reading when one is added, not the
     * scan counts. Named rather than counted, because the roster gains rows from concurrent work
     * and a count in this paragraph is stale the moment one lands.
     *
     * <p>A row can also be falsified by the readers changing under it, and one has been.
     * {@code intent_resolved_type_binding} sat here on a measurement that every index shape made
     * some reader dearer, taken while every reader spelled its type expression inline; H2 pushes
     * such an expression down into the probe, so the index had nothing to add and something to
     * cost. Once {@code intent_field_navigated_type} stated that expression as a relation, the
     * readers joined a column instead and the planner began scanning this target where it had
     * probed. The index on the coordinate they actually hold is what closed that, and it removed
     * three pairs {@code DerivedReadCostTest} had carried rather than adding any, so the row went.
     * The argument it was resting on was sound and is simply about a tree that no longer exists,
     * which is the failure mode a roster of measured declines has: the readers, not the figures,
     * are what a row is really pinned to.
     *
     * <p>A second row went the same way, and it is the kind that paragraph predicted rather than the
     * kind it described. {@code intent_node_id_instruction} sat here with no measurement at all: its
     * readers each named the target in their own driving {@code FROM} and joined outward from it, so
     * nothing probed in and no coordinate existed for an index to serve. {@code
     * intent_condition_param_decode} is a reader that does probe in, seeking one row per captured
     * {@code @condition} by site and coordinate, and the registration cost it more than the rule it
     * replaced for exactly the reason the target had no index: a view is evaluated restricted and a
     * table without a key is scanned whole. The index on the coordinate that reader holds took it
     * from 9787 scans to 2426, against 2923 over the unregistered rule, so a pair
     * {@code DerivedReadCostTest} would have carried never appeared. The row was falsified by a new
     * reader, which is what its own argument said would falsify it.
     *
     * <p>The rows that remain:
     *
     * <ul>
     *   <li>{@code intent_field_column_scope}: on the field coordinate its three readers join,
     *   with or without {@code basis}, it takes {@code intent_field_reference_discovery} from 148
     *   scans to 246 and returns 59 elsewhere. Nothing to weigh.</li>
     *   <li>{@code intent_errors_field}: on the coordinate its two probing readers join (graph,
     *   type and field, the carrier scan's {@code NOT EXISTS} and the error channel's join with
     *   its correlated minimum), no reader improves: the three cheap readers move within the
     *   instrument's noise, and the two dear ones get worse, {@code intent_carrier_routine_hop}
     *   from 3876 scans to 8136 and {@code intent_mutation_routine_seat} from 28857 to 33117,
     *   the planner preferring a seek into a relation of a dozen rows over the plan it picks
     *   unaided. Nothing to weigh.</li>
     *   <li>{@code intent_carrier_data_field}: on the two coordinates its readers spell (the
     *   error channel's graph, type and family; the sigil surface's graph, type and field), no
     *   reader moves at all, to the scan: the seat, the hop, the error channel and the whole
     *   read are identical with either shape declared and with none. The table is a dozen rows
     *   and its readers arrive through joins the planner already orders correctly. Nothing to
     *   weigh.</li>
     *   <li>{@code intent_argument_column_scope}: on the argument coordinate its one reader is
     *   keyed by, and on the resolved-table triple that reader could in principle seek, no reader
     *   moves at all, to the scan: {@code intent_argument_column_match} reads 5187 scans with
     *   either shape declared and with none, and the target's own read stays 265. Its one reader
     *   drives from it, first in its own FROM clause, so there is no per-row seek for an index to
     *   serve. Nothing to weigh, and the field-site sibling above declines for its own reasons at
     *   the same position in the same pair.</li>
     *   <li>{@code intent_argument_column_match}: the first target here whose reader genuinely
     *   probes in. {@code intent_argument_filter_role} seeks it by argument coordinate once per
     *   node-id instruction, so unlike the two scope targets above there was a seek for an index
     *   to serve, and the coordinate it seeks was declared and timed rather than reasoned about:
     *   on the sakila example schema the reader stays at six milliseconds with
     *   {@code (graph_name, type_name, field_name, argument_name)} declared and with none, and its
     *   plan visits the same 4713 rows either way. At this population the probing side is smaller
     *   than the table, so H2 reads the table whole in both. Worth revisiting where a reader
     *   probes it from a population larger than the table itself, which is the shape that would
     *   change the answer.</li>
     *   <li>{@code intent_mutation_payload_column}: its one reader reads it whole. The matched-key
     *   relation collects the columns a payload contributes as a {@code DISTINCT} over every row of
     *   this target and then joins the candidate keys onto that, so there is no probe for an index
     *   to serve and a declared one would only be maintained. The shape that would change the
     *   answer is a reader keyed by one mutation coordinate. The write destination relation was
     *   expected to be that reader and is not: it reads this target whole in its DELETE arm and
     *   reaches it otherwise through the key-membership target below, so nothing probes it by
     *   coordinate yet and there is still no seek for an index to serve.</li>
     *   <li>{@code intent_mutation_write_payload}: all three of its readers drive from it. The
     *   refusal rule and the payload-column rule each name it in their own {@code FROM} and join
     *   outward, and the matched key does the same now that its ranking is one pass rather than a
     *   join back to itself. Nothing probes it by coordinate, so there is no seek an index could
     *   serve. An index was declared here first, on the assumption that the matched key sought it,
     *   and removed when the rewrite that made that relation one pass removed the seek along with
     *   the join.</li>
     *   <li>{@code intent_input_field_carrier_role}: the row where the counter and the clock
     *   disagree and the clock decides. Both readers are the mutation payload views and both join
     *   this target on the whole six-column grain from inside an inlined common table expression, so
     *   unlike the two scope targets above there is a coordinate an index could serve, and the
     *   roster's structural route is unavailable. Four shapes were measured against a store captured
     *   from the sakila example schema with statistics current on both sides, in rows visited by the
     *   payload column relation and the payload refusal: with no index 4286 / 2163; on the grain
     *   3101 / 790; on the grain with {@code carrier_role} appended 3101 / 787; on
     *   {@code (graph_name, type_name, field_name)} 3101 / 790; on
     *   {@code (graph_name, carrier_role)} 4286 / 811. By that counter three of the four are a
     *   large improvement. The clocks are what refuse them: the payload column is 25/24/23
     *   milliseconds with no index and 22/22/23 on the grain, inside its own spread, while the
     *   refusal goes the wrong way and consistently, 13/11/11 with none against 21/23/22 on the
     *   grain, 30/18/20 with the role appended and 24/23/20 on the field coordinate. A third of the
     *   rows visited for twice the time is this file's own most-repeated lesson arriving again, and
     *   the mechanism is the target's size: it holds ninety-five rows here, so the seek an index
     *   installs costs more than the scan it replaces. What would change the answer is a driving
     *   side larger than the target, which is the same shape {@code intent_argument_column_match}'s
     *   row above names, and on a consumer schema both sides grow. Nothing here is the hazard the
     *   register met on {@code intent_field_scope_table}, where an unindexed target was worse than
     *   the view it replaced: this target unindexed takes its expensive reader from 85 milliseconds
     *   to 25.</li>
     *   <li>{@code intent_mutation_payload_key_membership}: five namings and none of them probes in.
     *   Two arms of {@code intent_mutation_write_refusal} drive from this target and
     *   {@code intent_mutation_write_destination} names it three times, twice as a set it collects
     *   from and once as the population it disposes. The registration is there to stop the rule
     *   being re-derived per driving row, not to make any one naming seek, so there is no coordinate
     *   an index would serve. A reader keyed by one mutation coordinate would change that, and none
     *   of the readers this family has today is one.</li>
     * </ul>
     */
    private static final Set<String> NO_INDEX = Set.of(
        "intent_field_column_scope",
        "intent_argument_column_scope",
        "intent_argument_column_match",
        "intent_input_field_carrier_role",
        "intent_errors_field",
        "intent_carrier_data_field",
        "intent_input_field_resolving_table",
        "intent_mutation_payload_column",
        "intent_mutation_write_payload",
        "intent_mutation_payload_key_membership");

    @Test
    @DisplayName("every registered source is a view and every registered target is a table")
    void registeredRelationsExistInTheKindsTheRegistryClaims() {
        withStore(dsl -> {
            var offenders = new ArrayList<String>();
            for (var registration : Materializations.registrations(dsl)) {
                String source = registration.sourceViewName();
                String target = registration.targetTableName();
                if (!"VIEW".equals(kindOf(dsl, source))) {
                    offenders.add(source + " is registered as a source but is "
                        + describe(kindOf(dsl, source)));
                }
                if (!"BASE TABLE".equals(kindOf(dsl, target))) {
                    offenders.add(target + " is registered as a target but is "
                        + describe(kindOf(dsl, target)));
                }
            }
            assertThat(offenders).as("registrations naming a relation of the wrong kind").isEmpty();
        });
    }

    @Test
    @DisplayName("every target's column list matches its source view's, name for name in order")
    void targetsAreShapedLikeTheViewsThatFillThem() {
        withStore(dsl -> {
            var offenders = new ArrayList<String>();
            for (var registration : Materializations.registrations(dsl)) {
                var source = columnsOf(dsl, registration.sourceViewName());
                var target = columnsOf(dsl, registration.targetTableName());
                if (!source.equals(target)) {
                    offenders.add(registration.targetTableName() + " has columns " + target
                        + " but is filled from " + registration.sourceViewName()
                        + ", whose columns are " + source);
                }
            }
            assertThat(offenders)
                .as("targets whose shape would make INSERT .. SELECT * write the wrong columns")
                .isEmpty();
        });
    }

    /**
     * The bootstrap has already run {@link MaterializeDependencies#populate} by the time a test
     * store opens, so this asserts over the populated relation, and failing means the DDL now
     * registers a cycle: a set of registrations no refresh order can settle, each needing
     * another's target current first. {@link Materializations#refreshOrder} is what throws,
     * naming the cycle; the completeness assertion is what the call buys when it does not.
     */
    @Test
    @DisplayName("the derived dependency rows are acyclic, so a refresh order exists")
    void theDerivedDependenciesAdmitARefreshOrder() {
        withStore(dsl -> assertThat(Materializations.refreshOrder(dsl).registrations())
            .as("every registration placed exactly once in the refresh order")
            .containsExactlyInAnyOrderElementsOf(Materializations.registrations(dsl)));
    }

    @Test
    @DisplayName("the register holds the registrations and the refresh stages this test states")
    void theRegisterIsTheShapeThisTestStates() {
        withStore(dsl -> {
            assertThat(Materializations.registrations(dsl))
                .as("registrations in meta_materialize. Equality both ways: a new one is a refresh"
                    + " every capture and every store open pays, and this is the only place that"
                    + " has to be edited for it")
                .hasSize(REGISTRATIONS);
            assertThat(RefreshStages.depth(dsl))
                .as("stages the refresh takes, the longest chain of registrations each waiting on"
                    + " the last. A registration can restage families it never names, so the depth"
                    + " moves without any edited file mentioning it")
                .isEqualTo(REFRESH_STAGES);
        });
    }

    @Test
    @DisplayName("the refresh order respects every derived dependency row")
    void theRefreshOrderRespectsEveryDependencyRow() {
        withStore(dsl -> {
            var order = Materializations.refreshOrder(dsl).registrations();
            var position = new java.util.HashMap<String, Integer>();
            for (int i = 0; i < order.size(); i++) {
                position.put(order.get(i).sourceViewName(), i);
            }
            var offenders = new ArrayList<String>();
            dsl.select(field(name("SOURCE_VIEW_NAME"), String.class),
                    field(name("DEPENDS_ON"), String.class))
                .from(table(name("META_MATERIALIZE_DEPENDENCY")))
                .fetch()
                .forEach(row -> {
                    if (position.get(row.value1()) < position.get(row.value2())) {
                        offenders.add(row.value1() + " refreshes before " + row.value2()
                            + ", whose target its view reads");
                    }
                });
            assertThat(offenders).as("dependency rows the refresh order violates").isEmpty();
        });
    }

    /**
     * The one cross-boundary direction the derived order can check. A registered view reading a
     * hand-written table is safe because the hand-written producers run first in the capture, and
     * the population walk sees such a read; what would invert the boundary is a hand-written
     * table being itself a registered target, which is what this refuses. The other direction, a
     * hand-written derivation reading a registered target, is jOOQ code rather than a stored view
     * definition, so no catalog parse can see it; the capture's own stratum comment discloses
     * that.
     */
    @Test
    @DisplayName("no hand-written derivation is a registered target")
    void noOrderingNeedCrossesTheHandWrittenBoundary() {
        withStore(dsl -> assertThat(Materializations.registrations(dsl).stream()
                .map(Materializations.Registration::targetTableName))
            .as("hand-written derivations registered as materializer targets")
            .doesNotContainAnyElementsOf(HAND_WRITTEN));
    }

    @Test
    @DisplayName("every materialized intent_ relation is a registration or a stated hand-written one")
    void nothingMaterializesOutsideTheMechanism() {
        withStore(dsl -> {
            var targets = Materializations.registrations(dsl).stream()
                .map(Materializations.Registration::targetTableName)
                .collect(java.util.stream.Collectors.toSet());
            var unaccounted = baseTables(dsl).stream()
                .filter(relation -> relation.startsWith("intent_"))
                .filter(relation -> !targets.contains(relation))
                .filter(relation -> !HAND_WRITTEN.contains(relation))
                .toList();
            assertThat(unaccounted)
                .as("stored intent_ relations that are neither registered nor a stated hand-written"
                    + " derivation; register it rather than writing a second bespoke writer")
                .isEmpty();
            assertThat(HAND_WRITTEN).allSatisfy(relation ->
                assertThat(baseTables(dsl)).as("hand-written derivation the DDL no longer declares")
                    .contains(relation));
        });
    }

    /**
     * Every registered target either carries an index or has a row in {@link #NO_INDEX} saying
     * why not, and the roster holds nothing else.
     *
     * <p>The claim this adds to the four above is that a target is a table in a keyed schema and
     * not a heap. Where the other tables in this file declare a primary key, most of the grains
     * here include a meaningfully nullable column and H2 refuses a primary key over one, so
     * the index cannot come from the key and has to be declared. What its absence costs lands
     * inside the derivations that read the target rather than on any reader's own predicate, which
     * is why no reader's own budget would catch it and why the claim belongs beside the register.
     *
     * <p>Read from {@code INFORMATION_SCHEMA} over a booted store like the rest of this class, and
     * counting only indexes the DDL declares: H2 backs each foreign key with an index of its own,
     * and every one of these targets has a {@code graph_name} key, so a scan that counted those
     * would report every target as indexed and assert nothing.
     */
    @Test
    @DisplayName("every registered target carries an index or a roster row saying why not")
    void everyTargetIsIndexedOrStatesWhyNot() {
        withStore(dsl -> {
            var unindexed = new java.util.TreeSet<String>();
            for (var registration : Materializations.registrations(dsl)) {
                if (declaredIndexesOf(dsl, registration.targetTableName()).isEmpty()) {
                    unindexed.add(registration.targetTableName());
                }
            }
            assertThat(unindexed)
                .as("registered targets with no declared index. Equality both ways: a target that"
                    + " has lost its index is a reader nobody measured, and a target that has"
                    + " gained one is a roster row to delete")
                .containsExactlyInAnyOrderElementsOf(NO_INDEX);
        });
    }

    /**
     * Every declared index on a registered target states which reader it serves, in its own
     * {@code COMMENT ON INDEX}. The same discipline the tables and columns in this file are held
     * to, and it matters more here than on a column: an index nothing names is an index nobody can
     * decide is still wanted, and the refresh goes on paying for it.
     */
    @Test
    @DisplayName("every index on a registered target names the reader that justifies it")
    void everyIndexOnATargetStatesItsReader() {
        withStore(dsl -> {
            var silent = new ArrayList<String>();
            for (var registration : Materializations.registrations(dsl)) {
                for (String index : declaredIndexesOf(dsl, registration.targetTableName())) {
                    String remark = remarkOn(dsl, index);
                    if (remark == null || remark.isBlank()) {
                        silent.add(index + " on " + registration.targetTableName());
                    }
                }
            }
            assertThat(silent)
                .as("indexes on a materialized target carrying no comment naming their reader")
                .isEmpty();
        });
    }

    /**
     * Analysing a healthy store analyses every registered target. The assertion that keeps
     * {@link Materializations#analyse}'s best-effort catch from meaning unobserved: it swallows a
     * database refusal because a target may be held by another writer and statistics are not worth
     * a failed build, and the failure that would abuse that tolerance is a statement malformed for
     * every target of every store, which would otherwise present as silence.
     */
    @Test
    @DisplayName("analysing a store nothing else holds analyses every registered target")
    void analysingAHealthyStoreReachesEveryTarget() {
        withStore(dsl -> assertThat(Materializations.analyse(dsl))
            .as("targets analysed on a store with no competing writer; fewer than all of them"
                + " means the statement was refused rather than merely unlucky")
            .isEqualTo(Materializations.registrations(dsl).size()));
    }

    // ===== Reading the observed schema =====

    /**
     * The indexes the DDL declares on {@code relationName}: {@code IS_GENERATED} is what separates
     * an authored {@code CREATE INDEX} from the one H2 raises to back a constraint, and the
     * distinction is load-bearing rather than tidy. Every target here carries a {@code graph_name}
     * foreign key, whose backing index H2 reports under the same {@code INDEX} type as an authored
     * one, so a scan without this predicate reports every target as indexed and asserts nothing.
     */
    private static List<String> declaredIndexesOf(DSLContext dsl, String relationName) {
        return dsl.selectDistinct(field(name("INDEX_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "INDEXES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(fold(relationName)))
            .and(field(name("IS_GENERATED"), Boolean.class).isFalse())
            .orderBy(field(name("INDEX_NAME")))
            .fetch(0, String.class);
    }

    private static String remarkOn(DSLContext dsl, String indexName) {
        return dsl.select(field(name("REMARKS"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "INDEXES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("INDEX_NAME"), String.class).eq(indexName))
            .fetchAny(0, String.class);
    }

    private static void withStore(java.util.function.Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }

    private static String kindOf(DSLContext dsl, String relationName) {
        return dsl.select(field(name("TABLE_TYPE"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(fold(relationName)))
            .fetchOne(0, String.class);
    }

    private static String describe(String kind) {
        return kind == null ? "not declared at all" : "a " + kind.toLowerCase(Locale.ROOT);
    }

    private static List<String> columnsOf(DSLContext dsl, String relationName) {
        return dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(fold(relationName)))
            .orderBy(field(name("ORDINAL_POSITION")))
            .fetch(0, String.class);
    }

    private static List<String> baseTables(DSLContext dsl) {
        return dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_TYPE"), String.class).eq("BASE TABLE"))
            .fetch(0, String.class).stream()
            .map(relation -> relation.toLowerCase(Locale.ROOT))
            .toList();
    }

    private static String fold(String relationName) {
        return relationName.toUpperCase(Locale.ROOT);
    }
}
