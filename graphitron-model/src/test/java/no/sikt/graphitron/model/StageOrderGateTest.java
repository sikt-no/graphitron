package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.ArgumentReferenceStepTargets;
import no.sikt.graphitron.model.derive.InputFieldReferenceStepTargets;
import no.sikt.graphitron.model.derive.DerivationStratum;
import no.sikt.graphitron.model.derive.ViewReferences;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The derivation stratum's order, against what each step of it reads and writes.
 *
 * <p><b>The invariant.</b> No step of the pass may read a table a <em>later</em> step writes,
 * because it would read the previous capture's rows. It is what a stage order has to satisfy by
 * construction, statement order in one list being the whole of the ordering.
 *
 * <p><b>Why statement order is enough here, where the fact model rules out hand-kept orderings.</b>
 * The objection to a hand-kept ordering is that it has no derivable source. This one has one, and
 * it has two shapes. A stage that inserts from a stored rule view has its read set in the catalog,
 * where {@link ViewReferences} parses it. A stage whose rule is jOOQ has no stored definition, so
 * the deriver exposes the statements it runs beside the method that runs them, and this gate reads
 * its read set off the same objects the capture executes. That is the stronger of the two: there is
 * no transcription between what is checked and what runs. What is left undeclared is the
 * hand-written producers whose rule is a Java loop rather than a statement, whose write sets are
 * declared in {@link DerivationStratum} beside the steps that run them.
 *
 * <p><b>What the roster below is, and what holds it.</b> {@link #STRATUM} carries what the stratum
 * does not: which view states each stage's rule, or which statements a jOOQ stage runs. Its names
 * and write sets are the stratum's own, and {@link #theRosterIsTheStratumItChecks} holds them equal
 * in order, so a step added to {@link DerivationStratum} and not here fails the build rather than
 * running unchecked, and {@link #everyDeclaredWriteIsAnObservedTable} keeps both from rotting
 * against a rename.
 */
class StageOrderGateTest {

    /**
     * One step of the derivation stratum: what it is called, the view stating its rule where it has
     * one, and the base tables it writes.
     *
     * @param ruleView the stored view a stage inserts from, or null for a step whose rule is jOOQ
     *                 code
     */
    private record Step(String name, String ruleView,
                        BiFunction<DSLContext, String, List<Query>> statements,
                        Set<String> writes) {

        /** A step whose rule is a stored view, or one the gate cannot read at all. */
        Step(String name, String ruleView, Set<String> writes) {
            this(name, ruleView, null, writes);
        }

        /** Whether this step states a read set the order can be checked against. */
        boolean readable() {
            return ruleView != null || statements != null;
        }
    }

    /** The graph a statement is built for; the gate renders the query rather than running it. */
    private static final String GRAPH = "the stage order gate's graph";

    /**
     * The stratum in the order {@code DerivationStratum.steps} lists it.
     *
     * <p>The stages go ahead of the producers where their rules allow it and after them where they
     * do not, which is a property of each rule's read set rather than a convention: a rule reaching
     * {@code intent_input_occurrence_path}, {@code intent_type_backing_class} or the other tables
     * the producers write has to run after the producer that writes it, and a rule bottoming out in
     * captured facts alone may run first, where later steps can read its rows. The gate below is
     * what says which of those each stage actually is.
     *
     * <p>{@code UnlowerableOrderingRejectionRows} sits last because it renders a view reading the
     * field-site scope table, so it runs once that table's stage has written it; this producer
     * being jOOQ with no parsed read set, that placement is a rationale the gate cannot check, and
     * it is stated here so a later move has to answer it.
     */
    private static final List<Step> STRATUM = List.of(
        new Step("FieldColumnScopes", "graphitron_field_column_scope_rule",
            Set.of("graphitron_field_column_scope")),
        new Step("ClassificationDomainCapture", null,
            Set.of("intent_type_domain")),
        new Step("InputOccurrencePaths", null,
            Set.of("intent_input_occurrence_path", "intent_input_occurrence_path_step")),
        new Step("ArgMappingCandidates", null,
            Set.of("graphitron_argmapping_candidate")),
        new Step("TypeBackingRows", null,
            Set.of("intent_type_backing_class")),
        new Step("AuthoredClaimRejectionRows", null,
            Set.of("intent_authored_claim_rejection")),
        new Step("CarrierDataFields", "graphitron_carrier_data_field_rule",
            Set.of("graphitron_carrier_data_field")),
        new Step("FieldScopeTables", "graphitron_field_scope_table_rule",
            Set.of("graphitron_field_scope_table")),
        new Step("ArgumentScopeTables", "graphitron_argument_scope_table_rule",
            Set.of("graphitron_argument_scope_table")),
        new Step("InputFieldResolvingTables", "graphitron_input_field_resolving_table_rule",
            Set.of("graphitron_input_field_resolving_table")),
        new Step("ArgumentReferenceStepTargets", null, ArgumentReferenceStepTargets::statements,
            Set.of("graphitron_argument_reference_step_target_keyed",
                "graphitron_argument_reference_step_target_keyless")),
        new Step("InputFieldReferenceStepTargets", null,
            InputFieldReferenceStepTargets::statements,
            Set.of("graphitron_input_field_reference_step_target_keyed",
                "graphitron_input_field_reference_step_target_keyless")),
        new Step("ArgumentColumnScopes", "graphitron_argument_column_scope_rule",
            Set.of("graphitron_argument_column_scope")),
        new Step("ArgumentColumnMatches", "graphitron_argument_column_match_rule",
            Set.of("graphitron_argument_column_match")),
        new Step("MutationWritePayloads", "graphitron_mutation_write_payload_rule",
            Set.of("graphitron_mutation_write_payload")),
        new Step("NodeIdInstructions", "graphitron_node_id_instruction_rule",
            Set.of("graphitron_node_id_instruction")),
        new Step("NodeIdDecodeHops", "graphitron_node_id_decode_hop_rule",
            Set.of("graphitron_node_id_decode_hop")),
        new Step("NodeIdDecodeHopColumns", "graphitron_node_id_decode_hop_column_rule",
            Set.of("graphitron_node_id_decode_hop_column")),
        new Step("NodeIdDecodeColumns", "graphitron_node_id_decode_column_rule",
            Set.of("graphitron_node_id_decode_column")),
        new Step("InputFieldColumnMatches", "graphitron_input_field_column_match_rule",
            Set.of("graphitron_input_field_column_match")),
        new Step("InputFieldFilterRoles", "graphitron_input_field_filter_role_rule",
            Set.of("graphitron_input_field_filter_role")),
        new Step("InputFieldCarrierRoles", "graphitron_input_field_carrier_role_rule",
            Set.of("graphitron_input_field_carrier_role")),
        new Step("MutationPayloadRefusals", "graphitron_mutation_payload_refusal_rule",
            Set.of("graphitron_mutation_payload_refusal")),
        new Step("MutationPayloadColumns", "graphitron_mutation_payload_column_rule",
            Set.of("graphitron_mutation_payload_column")),
        new Step("MutationPayloadKeyMemberships", "graphitron_mutation_payload_key_membership_rule",
            Set.of("graphitron_mutation_payload_key_membership")),
        new Step("MutationWriteDestinations", "graphitron_mutation_write_destination_rule",
            Set.of("graphitron_mutation_write_destination")),
        new Step("UnlowerableOrderingRejectionRows", null,
            Set.of("intent_field_unlowerable_ordering_rejection")));

    /**
     * The roster is the stratum, name for name and write set for write set, in order. Without this
     * the roster would be a transcription the gate trusted, and a step added to the stratum and not
     * here would run with nothing checking where it sits.
     */
    @Test
    @DisplayName("the roster this gate checks is the stratum the capture runs, in order")
    void theRosterIsTheStratumItChecks() {
        assertThat(STRATUM.stream().map(step -> step.name() + " " + new java.util.TreeSet<>(step.writes()))
                .toList())
            .as("the gate's roster against DerivationStratum.steps; a step missing here is a step"
                + " no order check reaches")
            .containsExactlyElementsOf(DerivationStratum.steps(null).stream()
                .map(step -> step.name() + " " + new java.util.TreeSet<>(step.writes()))
                .toList());
    }

    @Test
    @DisplayName("no stage reads a table a later step of the pass writes")
    void noStageReadsWhatALaterStepWrites() {
        withStore(dsl -> assertThat(tooEarly(dsl, resolved(dsl)))
            .as("stages reading a table the pass has not written yet. Move the stage after the"
                + " step that writes what it reads, or convert that step's relation first")
            .isEmpty());
    }

    /**
     * Every stage's rule reads something. Without this the case above passes on a parse that
     * returned nothing, which is how a gate over a read set stops being a gate: the offender list
     * is empty either way, and the reading that made it empty is the one nobody wants.
     */
    @Test
    @DisplayName("every stage's rule reaches stored relations the order can be checked against")
    void everyStageRuleReachesStoredRelations() {
        withStore(dsl -> STRATUM.stream().filter(Step::readable).forEach(step ->
            assertThat(readSetOf(dsl, step))
                .as(step.name() + " reaches no stored relation, so the order above was checked"
                    + " against nothing")
                .isNotEmpty()));
    }

    /**
     * The jOOQ half of the read set, shown reading a real dependency rather than merely being
     * non-empty. A stage stated as jOOQ is where the seam could fail silently: a walk that named no
     * relation this gate recognised would give an empty read set, and the case above and the order
     * check would both pass on it. So one statement's read set is asserted to hold the relation its
     * seed joins, which is exactly the edge that decides where the stage may sit.
     */
    @Test
    @DisplayName("a jOOQ stage's read set holds the relation its seed departs from")
    void aJooqStageReadsWhatItsSeedJoins() {
        withStore(dsl -> {
            assertThat(readSetOf(dsl, stepNamed("ArgumentReferenceStepTargets")))
                .as("the argument-site walk seeds on the table an argument's own content binds"
                    + " against, and the read set derived from its statements has to say so")
                .contains("graphitron_argument_scope_table");
            assertThat(readSetOf(dsl, stepNamed("InputFieldReferenceStepTargets")))
                .as("the input-field walk seeds on the table an input field is classified against")
                .contains("graphitron_input_field_resolving_table");
        });
    }

    private static Step stepNamed(String name) {
        return STRATUM.stream().filter(step -> step.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * The gate firing, shown rather than argued. The real stratum with its deepest stage moved to
     * the front: the write destination's rule reads the payload column and the key membership, both
     * stage-written, so ahead of both it would read the previous capture's rows, and the check above
     * names it. A real ordering rather than a fixture, so the case keeps meaning something as stages
     * are added: it fails the day the write destination stops reading anything a stage writes, which
     * is a change to the rule this gate would want a reader to notice.
     */
    @Test
    @DisplayName("a stage placed ahead of the step that writes what it reads is caught")
    void aStageAheadOfItsPrerequisiteIsCaught() {
        withStore(dsl -> {
            var steps = new ArrayList<>(resolved(dsl));
            var deepest = steps.stream()
                .filter(step -> step.writes().contains("graphitron_mutation_write_destination"))
                .findFirst().orElseThrow();
            steps.remove(deepest);
            steps.addFirst(deepest);

            assertThat(tooEarly(dsl, steps))
                .as("the gate says nothing about a stage reading rows a later stage writes")
                .isNotEmpty();
        });
    }

    /** Stages whose rule reaches a relation a later step of {@code steps} writes. */
    private static List<String> tooEarly(DSLContext dsl, List<Step> steps) {
        var offenders = new ArrayList<String>();
        for (int position = 0; position < steps.size(); position++) {
            Step step = steps.get(position);
            if (!step.readable()) {
                continue;
            }
            Set<String> later = steps.subList(position + 1, steps.size()).stream()
                .flatMap(s -> s.writes().stream())
                .collect(Collectors.toSet());
            for (String read : readSetOf(dsl, step)) {
                if (later.contains(read)) {
                    offenders.add(step.name() + " reads " + read + ", written by a later step of"
                        + " the same pass; it would read the previous capture's rows");
                }
            }
        }
        return offenders;
    }

    /**
     * Every stored relation a step reads, whichever way it states its rule: through the catalog for
     * a stage inserting from a stored view, and off the rendered statements for one stated as jOOQ.
     *
     * <p>A writing statement names its own target, an {@code INSERT INTO t SELECT ...} visiting
     * {@code t} like any other relation, so the step's own writes come back out. That subtraction
     * is the gate's to make rather than the walk's: what a statement reads is a question about the
     * statement, and what a step writes is a declaration this file holds.
     */
    private static Set<String> readSetOf(DSLContext dsl, Step step) {
        if (step.ruleView() != null) {
            return storedRelationsReachedBy(dsl, step.ruleView());
        }
        if (step.statements() == null) {
            return Set.of();
        }
        var named = new LinkedHashSet<String>();
        for (Query query : step.statements().apply(dsl, GRAPH)) {
            named.addAll(ViewReferences.relationsReadBy(dsl, query));
        }
        named.removeAll(step.writes());
        return storedRelationsReachedBy(dsl, named);
    }

    /**
     * A stage's insert is {@code INSERT INTO target SELECT * FROM rule}, so the two shapes have to
     * agree name for name in order: a mismatch would fill the target with the right rows
     * under the wrong columns, which is the one failure nobody reads.
     */
    @Test
    @DisplayName("every stage's target is shaped like the rule view that fills it")
    void everyStageTargetIsShapedLikeItsRule() {
        withStore(dsl -> {
            var offenders = new ArrayList<String>();
            for (Step step : STRATUM) {
                if (step.ruleView() == null) {
                    continue;
                }
                assertThat(step.writes())
                    .as(step.name() + " inserts from one rule view, so it writes one table")
                    .hasSize(1);
                String target = step.writes().iterator().next();
                var rule = columnsOf(dsl, step.ruleView());
                var written = columnsOf(dsl, target);
                if (!rule.equals(written)) {
                    offenders.add(target + " has columns " + written + " but is filled from "
                        + step.ruleView() + ", whose columns are " + rule);
                }
            }
            assertThat(offenders)
                .as("stages whose target shape would make INSERT .. SELECT * write the wrong"
                    + " columns")
                .isEmpty();
        });
    }

    /**
     * The transcription's own guard. Every relation a step declares it writes is an observed base
     * table, and every rule view an observed view, so a rename that moves a step's relation out
     * from under this roster fails here rather than quietly narrowing what the order is checked
     * against.
     */
    @Test
    @DisplayName("every declared write names an observed table and every rule an observed view")
    void everyDeclaredWriteIsAnObservedTable() {
        withStore(dsl -> {
            var kinds = relationKinds(dsl);
            var offenders = new ArrayList<String>();
            for (Step step : resolved(dsl)) {
                for (String written : step.writes()) {
                    if (!"BASE TABLE".equals(kinds.get(written))) {
                        offenders.add(step.name() + " declares it writes " + written + ", which is "
                            + describe(kinds.get(written)));
                    }
                }
                if (step.ruleView() != null && !"VIEW".equals(kinds.get(step.ruleView()))) {
                    offenders.add(step.name() + " inserts from " + step.ruleView() + ", which is "
                        + describe(kinds.get(step.ruleView())));
                }
            }
            assertThat(offenders).as("stratum rows naming a relation of the wrong kind").isEmpty();
        });
    }

    /**
     * Every stored relation under the derived family's prefix is written by a step this stratum
     * names, so a base table no step writes fails here rather than sitting in the schema filled by
     * nobody or by a writer standing outside the order: a derived table is a step's, or it is a
     * defect.
     */
    @Test
    @DisplayName("every stored intent_ relation is written by a step the stratum names")
    void everyDerivedTableHasAWriterInTheStratum() {
        withStore(dsl -> {
            var written = resolved(dsl).stream()
                .flatMap(step -> step.writes().stream())
                .collect(Collectors.toSet());
            var orphans = relationKinds(dsl).entrySet().stream()
                .filter(e -> "BASE TABLE".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .filter(relation -> relation.startsWith("intent_"))
                .filter(relation -> !written.contains(relation))
                .sorted()
                .toList();
            assertThat(orphans)
                .as("stored intent_ relations no step of the derivation stratum writes")
                .isEmpty();
        });
    }

    /**
     * No two steps write the same relation. A relation two steps fill is one whose second filler
     * overwrites or doubles the first's rows depending on its key, and neither is a thing a reader
     * of the order could see.
     */
    @Test
    @DisplayName("no relation is written by two steps of the stratum")
    void everyRelationHasOneWriterInTheStratum() {
        withStore(dsl -> {
            var seen = new HashSet<String>();
            var twice = new ArrayList<String>();
            resolved(dsl).forEach(step -> step.writes().forEach(written -> {
                if (!seen.add(written)) {
                    twice.add(written);
                }
            }));
            assertThat(twice)
                .as("relations two steps of the derivation stratum both fill")
                .isEmpty();
        });
    }

    // ===== Reading the observed schema =====

    /** The roster the cases read. */
    private static List<Step> resolved(DSLContext dsl) {
        return STRATUM;
    }

    /**
     * Every stored relation the named view reaches, expanding through views and stopping at base
     * tables, which is {@link ViewReferences#tablesReachedBy(DSLContext, String)}: the ordering
     * question is about tables, a view in between holding no rows of its own.
     */
    private static Set<String> storedRelationsReachedBy(DSLContext dsl, String viewName) {
        return ViewReferences.tablesReachedBy(dsl, viewName);
    }

    /** The same walk from the relations a statement named directly. */
    private static Set<String> storedRelationsReachedBy(DSLContext dsl, Collection<String> seeds) {
        return ViewReferences.tablesReachedBy(dsl, seeds);
    }

    private static Map<String, String> relationKinds(DSLContext dsl) {
        var kinds = new LinkedHashMap<String, String>();
        dsl.select(field(name("TABLE_NAME"), String.class), field(name("TABLE_TYPE"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .fetch()
            .forEach(row -> kinds.put(row.value1().toLowerCase(Locale.ROOT), row.value2()));
        return kinds;
    }

    private static List<String> columnsOf(DSLContext dsl, String relationName) {
        return dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(relationName.toUpperCase(Locale.ROOT)))
            .orderBy(field(name("ORDINAL_POSITION")))
            .fetch(0, String.class);
    }

    private static String describe(String kind) {
        return kind == null ? "not declared at all" : "a " + kind.toLowerCase(Locale.ROOT);
    }

    private static void withStore(Consumer<DSLContext> body) {
        withSeededStore(body);
    }
}
