package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.MaterializeDependencies;
import no.sikt.graphitron.model.derive.StoreDetections;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the detection pass evaluates on every read, pinned per component by equality.
 *
 * <p>The pass runs once per generator pass, at a dev round's boot and again after every save, so
 * every view body in these sets is a rule the engine expands each time. A relation entering one of
 * them is therefore a cost a consumer pays per round, and this gate exists because that cost is
 * invisible where it is incurred: a reader names one relation, and what the naming expands to is a
 * property of the schema several levels down from the statement.
 *
 * <p><b>What a delta means, and what to do about it.</b> A build failing here is a relation
 * arriving in, or leaving, some component's read-cadence reach. Before editing the number, price
 * the relation on a populated store per the {@code store-performance} skill, and either register or
 * restate it so it leaves the set again, or record beside its row why its cost at read cadence is
 * acceptable. The pin is the ratchet {@code DerivedReadCostTest} uses for its own sets: a figure
 * edited deliberately, in the commit that argues for it, and never a ceiling to be raised because
 * the build is red.
 *
 * <p><b>Why the sets are stated over tables rather than over the register.</b> The walk stops at
 * every table: a registered target, a base relation, and a relation some future owner decides to
 * store under its own name alike. Stated that way the pin keeps its meaning when the register
 * dissolves, because what a reader evaluates is a property of the schema it meets and not of the
 * mechanism that filled it.
 *
 * <p><b>The shape this gate is aimed at.</b> A relation can be paid once per capture through a
 * registration's refresh and still be expanded per read by a reader that names it directly, and
 * that pairing reads as safe from both ends: the registration's own reason says the rule is priced,
 * and the reader's statement names one relation. It is not safe, and a dev round on a consumer
 * schema is where the difference shows. Rows below carry a note naming the registration that pays
 * the same relation at refresh cadence, so the pairing is legible on the page rather than
 * reconstructible from two files.
 *
 * <p><b>The disclosed gap</b>, on the precedent {@link CollectionValuedColumnGateTest} sets for a
 * gate that states its own. The roots come from each component's authored {@code READS} set, so a
 * component that names a relation without putting that relation in its set is a read this gate
 * cannot see. Having each component's statements stand beside its declared set is what makes the
 * omission visible in review, the bypass being a static import beside a set that does not carry
 * it. Closing it mechanically would be a lexical scan of the components for a relation constant
 * outside their sets; that scan has caught nothing yet and is deliberately not here.
 *
 * <p>Scope is the detection pass. The diagnostic surface the language server reads has its own
 * ceilings, in {@code graphitron-lsp}, and no wall-clock claim is made anywhere here: the gate
 * asserts a set of names over a booted schema with no captured rows in it, and takes no timing.
 * Structural in that sense, and on the funnel rather than a store of its own because the funnel's
 * cleared store <em>is</em> a booted schema with no captured rows: what the walk reads is
 * {@code INFORMATION_SCHEMA} and the register, neither of which a capture writes.
 */
class DetectionReadReachGateTest {

    /**
     * Every view body each component of the detection pass expands on one read, the component's own
     * root relations included where those are views, with the walk stopping at every table.
     *
     * <p>The order is {@link StoreDetections#reads()}'s, which is the record's family order.
     */
    private static final Map<String, Set<String>> REACH = new LinkedHashMap<>();

    static {
        REACH.put("AuthoredClaimConflicts", Set.of(
            "intent_authored_claim_conflict",
            // Also refreshed through intent_field_column_scope_live, and expanded here per violated
            // field coordinate by claimsAt.
            "intent_authored_field_claim",
            "intent_authored_type_claim"));

        REACH.put("ArgmappingProjectionDefects", Set.of(
            "graphitron_argmapping_match",
            "graphql_element_field",
            "intent_argmapping_bound_parameter_type",
            "intent_argmapping_key_column_candidate",
            "intent_argmapping_projection_defect",
            "intent_field_routine_method",
            "intent_resolved_node_key_projection",
            "intent_resolved_node_key_shape"));

        REACH.put("NodeIdDecodeDefects", Set.of(
            "graphitron_argmapping_match",
            "graphql_element_field",
            "intent_argmapping_bound_parameter_type",
            "intent_field_producer_method",
            "intent_field_producer_reference",
            "intent_field_routine_method",
            "intent_node_id_decode_defect",
            "intent_node_id_decode_slot",
            "intent_resolved_node_key_shape"));

        REACH.put("NodeIdPolymorphicDecodeDefects", Set.of(
            "graphitron_argmapping_match",
            "graphql_element_field",
            "intent_argmapping_bound_parameter_type",
            "intent_field_producer_method",
            "intent_field_producer_reference",
            "intent_field_routine_method",
            // Also refreshed through intent_input_field_filter_role_live and
            // intent_node_id_instruction_live.
            "intent_inferred_node_type",
            // Expanded here once per container by memberNames.
            "intent_node_container_member",
            "intent_node_id_decode_slot",
            "intent_node_id_polymorphic_decode_defect",
            "intent_node_metadata_defect",
            "intent_node_type",
            // Also refreshed through intent_carrier_data_field_live and
            // intent_field_scope_table_live; expanded here once per container by memberNames.
            "intent_poly_member",
            "intent_record_slot_assignable",
            "intent_resolved_node_key_shape"));

        // Twelve relations until intent_node_id_decode_hop was registered, and six of them were
        // reached only through it: the two argument-site reference-target relations, the
        // input-field one, intent_condition_method_route and intent_name_matched_key_pair. The
        // walk stops at the target table now, so this reader evaluates none of their bodies. That
        // is the lever this gate was written for landing, and the delta is the diff a reviewer
        // reads the outcome off.
        //
        // intent_node_id_decode_endpoint stays, read live by the verdict's own judged CTE rather
        // than through the hop, and it is known rather than unpriced: 14 ms on the population that
        // exposed the defect, whose whole subtree answers in single-digit milliseconds.
        REACH.put("NodeIdLandingDefects", Set.of(
            "intent_field_navigated_type",
            "intent_field_participant_scope_table",
            "intent_node_id_decode_endpoint",
            "intent_node_id_decode_landing_defect",
            "intent_poly_member",
            "intent_reference_for_application"));

        REACH.put("ReferenceForParticipantDefects", Set.of(
            "intent_field_navigated_type",
            // Also refreshed through intent_field_scope_table_live, and expanded here once per
            // consumer of an application row by participantsOf.
            "intent_field_participant_scope_table",
            "intent_poly_member",
            "intent_reference_for_application"));

        REACH.put("UnlowerableOrderings", Set.of(
            "intent_bound_table",
            "intent_carrier_routine_hop",
            "intent_connection_element_type",
            "intent_field_chain_node",
            "intent_field_chain_start",
            "intent_field_chain_terminus",
            "intent_field_navigated_type",
            "intent_field_participant_scope_table",
            "intent_field_unlowerable_ordering",
            "intent_mutation_routine_seat",
            "intent_name_matched_key_pair",
            "intent_poly_member"));

        REACH.put("ResolvedKeyProjections", Set.of(
            "graphitron_argmapping_match",
            "graphql_element_field",
            "intent_argmapping_bound_parameter_type",
            "intent_argmapping_key_column_candidate",
            "intent_field_routine_method",
            "intent_inferred_node_type",
            "intent_node_metadata_defect",
            "intent_node_type",
            "intent_resolved_node_key_projection",
            "intent_resolved_node_key_shape",
            // Reached through StoreNodeTables, which read calls unconditionally rather than through
            // this component's own statement.
            "intent_resolved_node_type_id"));
    }

    @Test
    @DisplayName("every component of the pass evaluates exactly the view bodies pinned for it")
    void everyComponentEvaluatesExactlyThePinnedViewBodies() {
        withSeededStore(dsl -> {
            var observed = observedReach(dsl);
            // The floor against a vacuous pass: a walk that answered nothing for every component
            // would satisfy an equality against sets nobody had filled in.
            assertThat(observed.values().stream().mapToInt(Set::size).sum())
                .as("view bodies the detection pass expands, across every component")
                .isGreaterThan(50);
            assertThat(observed)
                .as("what the detection pass evaluates on every read, per component. A relation "
                    + "arriving here is a rule a dev round re-expands at boot and after every "
                    + "save, so price it on a populated store and register or restate it before "
                    + "editing this pin; a relation leaving is a lever that landed.")
                .isEqualTo(REACH);
        });
    }

    @Test
    @DisplayName("the roster carries every component the pass runs")
    void theRosterCarriesEveryComponentThePassRuns() {
        var onTheRecord = new TreeSet<String>();
        for (var component : StoreDetections.class.getRecordComponents()) {
            onTheRecord.add(component.getType().getEnclosingClass().getSimpleName());
        }
        assertThat(new TreeSet<>(StoreDetections.reads().keySet()))
            .as("components on the reads roster against the components the pass yields a product "
                + "for. A detection joining the record owes its reads to the roster, or its whole "
                + "reach sits outside this gate while the gate reports a clean equality.")
            .isEqualTo(onTheRecord);
        assertThat(new TreeSet<>(REACH.keySet()))
            .as("components pinned here against the roster")
            .isEqualTo(onTheRecord);
    }

    @Test
    @DisplayName("the walk stops at tables and passes table roots through")
    void theWalkStopsAtTablesAndPassesTableRootsThrough() {
        withSeededStore(dsl -> {
            // A base table as a root yields nothing rather than failing, which is what lets a
            // component hand the walk every relation its statements name without curating them.
            assertThat(MaterializeDependencies.viewsEvaluatedBy(dsl, List.of("intent_type_domain")))
                .isEmpty();
            // A registered target is a table too, so a reader meeting one evaluates no body.
            assertThat(MaterializeDependencies.viewsEvaluatedBy(dsl,
                List.of("intent_field_scope_table"))).isEmpty();
            // The source view behind that same target is a view, so a reader naming it evaluates
            // the rule the target holds the rows of. That difference is the whole subject.
            assertThat(MaterializeDependencies.viewsEvaluatedBy(dsl,
                List.of("intent_field_scope_table_live")))
                .contains("intent_field_scope_table_live");
            // A name the catalog does not hold is ignored on the same footing as a table.
            assertThat(MaterializeDependencies.viewsEvaluatedBy(dsl,
                List.of("intent_no_such_relation"))).isEmpty();
        });
    }

    /** The reach the booted store reports, per component, off the roster's own sets. */
    private static Map<String, Set<String>> observedReach(DSLContext dsl) {
        var observed = new LinkedHashMap<String, Set<String>>();
        StoreDetections.reads().forEach((component, reads) ->
            observed.put(component, MaterializeDependencies.viewsEvaluatedBy(dsl, names(reads))));
        return observed;
    }

    /** A component's declared reads as the lowercased relation names the walk takes. */
    private static Set<String> names(Set<Table<?>> reads) {
        var names = new TreeSet<String>();
        reads.forEach(table -> names.add(table.getName().toLowerCase(Locale.ROOT)));
        return names;
    }
}
