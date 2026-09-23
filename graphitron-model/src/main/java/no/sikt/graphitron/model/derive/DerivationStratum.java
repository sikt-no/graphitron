package no.sikt.graphitron.model.derive;

import graphql.schema.GraphQLSchema;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The graphitron gatherer's derivation stratum as data: every step the capture pass runs after the
 * gatherers have flushed, in the one order they may run, and what each writes.
 *
 * <p>A step is a stage or a producer. A stage is one {@code INSERT} over the stored view stating its
 * rule, or a jOOQ statement where the rule is a walk; a producer is hand-written jOOQ whose rule no
 * view could state. Both reconcile their own partition, clearing it and deriving it again, which is
 * what lets a second capture of one graph be the ordinary case.
 *
 * <p><b>The order is the one invariant here: no step reads a table a later step writes</b>, because
 * it would read the previous capture's rows. Statement order in a list is a hand-kept ordering, and
 * what keeps it from being one with no derivable source is {@code StageOrderGateTest}: it parses each
 * stage's rule for what it reads and fails the build on a step placed ahead of what it depends on.
 * The write sets below are the other half of that check, declared because a producer's reads are
 * jOOQ code no stored definition exposes.
 *
 * <p><b>Two cadences, chosen by the store's state rather than by the caller.</b> On a store any stage
 * table holds a row in, the stratum runs inside one transaction, so no reader ever observes an
 * emptied partition; H2's {@code ANALYZE} commits, so nothing inside can be analysed, and the
 * statistics the steps plan against are the ones the capture stated before it began. On a store no
 * stage table holds a row in, every table the stratum writes is empty when the capture's statistics
 * are taken, so a step reading an earlier step's table would plan against a relation the planner has
 * been told is empty; the steps then commit one at a time and each analyses what it wrote before the
 * next plans. Committing between two steps there empties nothing anybody committed, every partition
 * being empty to begin with, which is the whole safety argument and the reason the choice is keyed to
 * the store rather than offered.
 */
public final class DerivationStratum {

    /**
     * One step: its name, the base tables it writes, and the statements that write them.
     *
     * @param writes the tables the step empties and refills for a graph, lower case as declared
     */
    public record Step(String name, Set<String> writes, Body body) {
        public Step {
            writes = Set.copyOf(writes);
        }
    }

    /** What a step does to one graph's partitions. */
    @FunctionalInterface
    public interface Body {
        void run(DSLContext dsl, String graphName);
    }

    private DerivationStratum() {}

    /**
     * The steps in the order they run.
     *
     * <p>The stages whose rules reach no producer's table run first, then the producers, then every
     * stage whose rule reaches a table a producer writes, in the order their rules read each other.
     * The one producer at the tail renders a view over the field-site scope table, so it runs once
     * that table is written.
     *
     * @param schema the corpus as it assembled, for the one producer that reads an executable schema
     *               rather than rows; null where the assembly was rejected, which it states
     */
    public static List<Step> steps(GraphQLSchema schema) {
        return List.of(
            step("FieldColumnScopes", "graphitron_field_column_scope", FieldColumnScopes::derive),
            new Step("ClassificationDomainCapture", Set.of("intent_type_domain"),
                (dsl, graph) -> ClassificationDomainCapture.derive(dsl, graph, schema)),
            new Step("InputOccurrencePaths",
                Set.of("intent_input_occurrence_path", "intent_input_occurrence_path_step"),
                InputOccurrencePaths::derive),
            step("ArgMappingCandidates", "graphitron_argmapping_candidate",
                ArgMappingCandidates::derive),
            step("TypeBackingRows", "intent_type_backing_class", TypeBackingRows::derive),
            step("AuthoredClaimRejectionRows", "intent_authored_claim_rejection",
                AuthoredClaimRejectionRows::derive),
            step("CarrierDataFields", "graphitron_carrier_data_field", CarrierDataFields::derive),
            step("FieldScopeTables", "graphitron_field_scope_table", FieldScopeTables::derive),
            step("ArgumentScopeTables", "graphitron_argument_scope_table",
                ArgumentScopeTables::derive),
            step("InputFieldResolvingTables", "graphitron_input_field_resolving_table",
                InputFieldResolvingTables::derive),
            new Step("ArgumentReferenceStepTargets",
                Set.of("graphitron_argument_reference_step_target_keyed",
                    "graphitron_argument_reference_step_target_keyless"),
                ArgumentReferenceStepTargets::derive),
            new Step("InputFieldReferenceStepTargets",
                Set.of("graphitron_input_field_reference_step_target_keyed",
                    "graphitron_input_field_reference_step_target_keyless"),
                InputFieldReferenceStepTargets::derive),
            step("ArgumentColumnScopes", "graphitron_argument_column_scope",
                ArgumentColumnScopes::derive),
            step("ArgumentColumnMatches", "graphitron_argument_column_match",
                ArgumentColumnMatches::derive),
            step("MutationWritePayloads", "graphitron_mutation_write_payload",
                MutationWritePayloads::derive),
            step("NodeIdInstructions", "graphitron_node_id_instruction",
                NodeIdInstructions::derive),
            step("NodeIdDecodeHops", "graphitron_node_id_decode_hop", NodeIdDecodeHops::derive),
            step("NodeIdDecodeHopColumns", "graphitron_node_id_decode_hop_column",
                NodeIdDecodeHopColumns::derive),
            step("NodeIdDecodeColumns", "graphitron_node_id_decode_column",
                NodeIdDecodeColumns::derive),
            step("InputFieldColumnMatches", "graphitron_input_field_column_match",
                InputFieldColumnMatches::derive),
            step("InputFieldFilterRoles", "graphitron_input_field_filter_role",
                InputFieldFilterRoles::derive),
            step("InputFieldCarrierRoles", "graphitron_input_field_carrier_role",
                InputFieldCarrierRoles::derive),
            step("MutationPayloadRefusals", "graphitron_mutation_payload_refusal",
                MutationPayloadRefusals::derive),
            step("MutationPayloadColumns", "graphitron_mutation_payload_column",
                MutationPayloadColumns::derive),
            step("MutationPayloadKeyMemberships", "graphitron_mutation_payload_key_membership",
                MutationPayloadKeyMemberships::derive),
            step("MutationWriteDestinations", "graphitron_mutation_write_destination",
                MutationWriteDestinations::derive),
            step("UnlowerableOrderingRejectionRows", "intent_field_unlowerable_ordering_rejection",
                UnlowerableOrderingRejectionRows::derive));
    }

    /**
     * Runs the stratum for one graph on the cadence the store's state calls for, reporting to
     * {@code progress}.
     */
    public static void run(DSLContext dsl, String graphName, GraphQLSchema schema,
                           StageProgress progress) {
        List<Step> steps = steps(schema);
        if (analysingCadenceApplies(dsl, steps)) {
            runAnalysing(dsl, graphName, steps, progress);
        } else {
            dsl.transaction(tx -> runInOne(tx.dsl(), graphName, steps, progress));
        }
    }

    /**
     * Whether no table the stratum writes holds a row, which is the store the analysing cadence is
     * for. Asked of the tables themselves rather than inferred from the anchor row a capture has
     * already written by the time this runs.
     */
    public static boolean analysingCadenceApplies(DSLContext dsl, List<Step> steps) {
        return steps.stream()
            .flatMap(step -> step.writes().stream())
            .noneMatch(written -> dsl.fetchExists(table(relation(written))));
    }

    /**
     * Every step on the caller's {@link DSLContext}, which is how a warm capture keeps the stratum
     * inside one transaction: no reader observes an emptied partition.
     */
    public static void runInOne(DSLContext dsl, String graphName, List<Step> steps,
                                StageProgress progress) {
        progress.observe(new StageProgress.Event.PassStarted(steps.size(), graphName, false));
        long startedAt = System.nanoTime();
        for (int position = 0; position < steps.size(); position++) {
            runOne(dsl, graphName, steps, position, progress);
        }
        progress.observe(new StageProgress.Event.PassFinished(System.nanoTime() - startedAt));
    }

    /**
     * Each step in a committed transaction of its own, the tables it wrote analysed before the next
     * step plans. The cadence for a store no stage table holds a row in, and only for that store; the
     * class states why.
     */
    public static void runAnalysing(DSLContext dsl, String graphName, List<Step> steps,
                                    StageProgress progress) {
        progress.observe(new StageProgress.Event.PassStarted(steps.size(), graphName, true));
        long startedAt = System.nanoTime();
        for (int position = 0; position < steps.size(); position++) {
            int at = position;
            dsl.transaction(tx -> {
                // The anchor row, held for the step's own transaction so a second writer of one
                // graph waits for the step in flight instead of interleaving its delete with this
                // one's insert.
                tx.dsl().select(field(name("GRAPH_NAME")))
                    .from(table(name("STORE_GRAPH")))
                    .where(field(name("GRAPH_NAME"), String.class).eq(graphName))
                    .forUpdate()
                    .fetch();
                runOne(tx.dsl(), graphName, steps, at, progress);
            });
            steps.get(position).writes().forEach(written -> analyse(dsl, written));
        }
        progress.observe(new StageProgress.Event.PassFinished(System.nanoTime() - startedAt));
    }

    private static void runOne(DSLContext dsl, String graphName, List<Step> steps, int position,
                               StageProgress progress) {
        Step step = steps.get(position);
        // Named before a statement is issued, so a step that never returns has already said which
        // it is; StageProgress states why that order is the instrument.
        progress.observe(new StageProgress.Event.StageStarted(step, position + 1, steps.size()));
        long startedAt = System.nanoTime();
        step.body().run(dsl, graphName);
        long nanos = System.nanoTime() - startedAt;
        int rows = step.writes().stream()
            .mapToInt(written -> dsl.select(count())
                .from(table(relation(written)))
                .where(field(name("GRAPH_NAME"), String.class).eq(graphName))
                .fetchOne(0, int.class))
            .sum();
        progress.observe(new StageProgress.Event.StageFinished(step, nanos, rows));
    }

    /**
     * One table's statistics, best effort: a refusal leaves the next step planning against whatever
     * the table already carried, which is a slower plan and never a wrong answer.
     */
    private static void analyse(DSLContext dsl, String relationName) {
        try {
            dsl.execute("ANALYZE TABLE " + dsl.render(table(relation(relationName))));
        } catch (DataAccessException refused) {
            // Deliberately swallowed, per the javadoc above.
        }
    }

    private static Step step(String name, String writes, Body body) {
        return new Step(name, Set.of(writes), body);
    }

    /** Relation names are declared lower case; H2's catalog holds the folded spelling. */
    private static org.jooq.Name relation(String relationName) {
        return name(relationName.toUpperCase(Locale.ROOT));
    }
}
