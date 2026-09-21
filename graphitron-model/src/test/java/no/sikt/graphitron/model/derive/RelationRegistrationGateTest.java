package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.test.AgreementCorpus;
import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every relation the model declares has a registered agreement source, and every coordinate anchor
 * pairs with the attribute relation beside it.
 *
 * <p>Here rather than beside the arms that compare capture against {@code GraphitronSchema}, which
 * is where both of these used to sit. Neither asks the generator anything: one enumerates the
 * generated relations and fails on one nobody registered, the other compares two relations of the
 * store against each other. They were filed with the comparison arms because the registration map
 * they read was declared there, which is a fact about where a field was written rather than about
 * what the case is of.
 *
 * <p>What that cost is the reason for moving them. Both are gates over the schema, and a schema
 * change that trips one is a change someone is making right now; run from the generator's module
 * they answer twenty minutes into a build, where the model's own gates answer in four. The
 * registration map travels with the case that reads it, the comparison arms keeping the fixtures
 * that are genuinely the generator's.
 */
class RelationRegistrationGateTest {

    private static final Map<String, Arm> REGISTRATIONS = registrations();


    /** How a relation's contents are pinned to the model it shadows. */
    private enum Arm { CONTAINMENT, EQUALITY, DERIVED, ORACLE, UNSHADOWED }
    private static Map<String, Arm> registrations() {
        var registrations = new LinkedHashMap<String, Arm>();
        for (String relation : List.of(
            // The element anchors ride their attribute siblings' arm: one walk claims a
            // coordinate and writes both rows, so the containment claim below transfers, and
            // coordinatesAgreeWithTheirAttributeRelations makes the transfer a fact. The supertype
            // rides the same arm one level up, written by the same claim as the anchor under it.
            "graphql_element",
            "graphql_type_element", "graphql_field_element",
            "graphql_argument_element", "graphql_enum_value_element",
            // The two at-sign productions of the coordinate grammar, on the same terms. Their
            // attribute siblings are graphql_directive and graphql_directive_argument, which are
            // already on this list, and the pairing is asserted beside the other four.
            "graphql_directive_element", "graphql_directive_argument_element",
            "graphql_type", "graphql_type_declaration", "graphql_field", "graphql_argument",
            "graphql_enum_value", "graphql_poly_member",
            // The two arms the poly view unions, which the walk writes directly now. Their
            // agreement is the view's: what GraphitronSchema states is one population, and
            // reading it through graphql_poly_member is reading both.
            "graphql_implements_interface", "graphql_union_member",
            "graphql_root_operation", "graphql_directive",
            "graphql_directive_location", "graphql_directive_argument", "graphql_schema_directive",
            "graphql_schema_directive_arg", "graphql_type_directive", "graphql_type_directive_arg",
            "graphql_field_directive", "graphql_field_directive_arg", "graphql_argument_directive",
            "graphql_argument_directive_arg", "graphql_enum_value_directive",
            "graphql_enum_value_directive_arg",
            "graphitron_table_entry", "graphitron_field_binding_entry",
            "graphitron_argument_binding_entry", "graphitron_enum_value_binding_entry", "graphitron_scalar_type_entry",
            "graphitron_enum_entry", "graphitron_field_condition_entry", "graphitron_field_condition_context_arg_entry",
            "graphitron_argument_condition_entry", "graphitron_argument_condition_context_arg_entry",
            "graphitron_field_reference_entry", "graphitron_field_reference_step_entry",
            "graphitron_argument_reference_entry", "graphitron_argument_reference_step_entry",
            "graphitron_reference_for_entry", "graphitron_reference_for_step_entry",
            "graphitron_argument_reference_for_entry", "graphitron_argument_reference_for_step_entry",
            "graphitron_service_entry", "graphitron_service_context_arg_entry",
            "graphitron_external_field_entry", "graphitron_connection_entry", "graphitron_facet_entry",
            "graphitron_order_by_entry", "graphitron_order_entry", "graphitron_order_field_entry", "graphitron_index_entry",
            "graphitron_default_order_entry", "graphitron_default_order_field_entry", "graphitron_mutation_entry",
            "graphitron_error_entry", "graphitron_error_handler_entry", "graphitron_node_entry", "graphitron_node_keycolumn_entry",
            "graphitron_field_node_id_entry", "graphitron_argument_node_id_entry", "graphitron_argument_lookup_key_entry",
            "graphitron_field_lookup_key_entry", "graphitron_split_query_entry", "graphitron_tenant_fan_out_entry",
            "graphitron_pivot_entry", "graphitron_routine_entry", "graphitron_routine_column_mapping_pair_entry",
            "graphitron_discriminate_entry", "graphitron_discriminator_entry",
            "graphitron_federation_key_entry", "graphitron_federation_key_field_entry",
            "graphitron_federation_key_field_segment_entry", "graphitron_link_entry",
            "graphitron_link_import_entry", "graphitron_multitable_reference_entry", "graphitron_record_entry",
            "graphitron_undecoded_argument_entry", "graphitron_minted_type",
            "graphitron_minted_field", "graphitron_minted_argument",
            // The three supertypes ride the arm of the sites that spell them. The spelled reference
            // and the method reference are written in the same walk as the per-site row they sit
            // beside, so a coordinate the walk claims contributes to both; the pair relation has no
            // per-site row left to sit beside, having absorbed the eight, and the method reference
            // has none at the source-row site for the same reason, both being written in that same
            // walk regardless.
            "graphitron_spelled_reference_entry", "graphitron_argmapping_entry",
            "graphitron_method_reference_entry",
            // Written beside the field row in the same walk, so the field coordinate's claim
            // covers it exactly as it covers the field's own attributes.
            "graphitron_field_navigation")) {
            registrations.put(relation, Arm.CONTAINMENT);
        }
        for (String relation : List.of(
            "sql_schema", "sql_table", "sql_column", "sql_enum_binding",
            "sql_constraint", "sql_constraint_column",
            "sql_primary_key", "sql_referential_constraint", "sql_index",
            "sql_index_column", "sql_routine", "sql_routine_parameter",
            "sql_node_metadata", "sql_node_key_column", "sql_table_record_supertype",
            "jvm_class", "jvm_class_supertype", "jvm_method",
            "jvm_method_parameter", "jvm_record_component", "jvm_declared_type_ref",
            "store_source", "store_stamp",
            "store_graph", "store_graph_schema_input", "store_graph_schema_extension",
            "store_graph_supergraph", "store_graph_output", "store_graph_tenant_column",
            "store_graph_lint_disabled_rule", "store_graph_lint_excluded_type",
            "store_graph_session_mount", "store_graph_session_unmount",
            "store_graph_source",
            // The java_ family: the store's rows and the walker's declarations are one parse
            // reduced two ways, pinned in JavaSourceFactsTest beside the source-partitioned
            // lifecycle anchor. Nothing in this class's fixtures reaches them, capture never
            // writing a .java file's declarations.
            "java_file", "java_class_declaration", "java_method_declaration",
            "java_field_declaration")) {
            registrations.put(relation, Arm.EQUALITY);
        }
        // The SDL node entries: one relation per node kind and site, read per document and keyed by the
        // position the node was written at. GraphitronSchema has no counterpart, holding a merged schema where these hold
        // each document as it was parsed, so there is nothing here to agree with. What pins them is
        // GraphQLAstEntriesTest, over a corpus that does not merge: two files declaring one type are two
        // rows, a child names its parent by the position that parent was written at, and the
        // engine's built-in scalars land nowhere.
        for (String relation : List.of(
            "graphql_ast_type_declaration_entry", "graphql_ast_field_definition_entry",
            "graphql_ast_field_argument_entry", "graphql_ast_input_field_entry",
            "graphql_ast_directive_argument_entry",
            "graphql_ast_enum_value_definition_entry", "graphql_ast_implements_entry",
            "graphql_ast_union_member_entry", "graphql_ast_directive_definition_entry",
            "graphql_ast_directive_location_entry", "graphql_ast_schema_definition_entry",
            "graphql_ast_operation_type_definition_entry", "graphql_ast_type_directive_entry",
            "graphql_ast_field_directive_entry", "graphql_ast_input_value_directive_entry",
            "graphql_ast_enum_value_directive_entry", "graphql_ast_schema_directive_entry",
            "graphql_ast_applied_argument_entry", "graphql_ast_value_entry",
            // The decode of a type-site directive application, keyed by the application's own
            // position. GraphitronSchema holds one binding per type where these hold one per
            // application, so two documents binding one type have nothing to agree about here.
            // What pins them is GraphitronAstEntriesTest, over a corpus that binds one type twice.
            "graphitron_ast_table_entry", "graphitron_ast_scalar_type_entry",
            "graphitron_ast_enum_entry", "graphitron_ast_record_entry",
            "graphitron_ast_node_entry", "graphitron_ast_node_keycolumn_entry",
            "graphitron_ast_discriminate_entry", "graphitron_ast_discriminator_entry",
            "graphitron_ast_federation_key_entry",
            "graphitron_ast_federation_key_selection_entry",
            "graphitron_ast_federation_key_segment_entry",
            "graphitron_ast_error_generic_handler_entry",
            "graphitron_ast_error_database_handler_entry",
            "graphitron_ast_error_validation_handler_entry",
            // The decode of a field-site application, on the same terms: keyed by the position of
            // the @ token, so an application repeated on one field is repeated rows here where
            // GraphitronSchema holds one binding per field under an ordinal it assigned.
            "graphitron_ast_field_binding_entry", "graphitron_ast_field_condition_entry",
            "graphitron_ast_field_condition_context_arg_entry",
            "graphitron_ast_field_reference_table_step_entry",
            "graphitron_ast_field_reference_key_step_entry",
            "graphitron_ast_field_reference_condition_step_entry",
            "graphitron_ast_field_reference_for_entry",
            "graphitron_ast_field_reference_for_table_step_entry",
            "graphitron_ast_field_reference_for_key_step_entry",
            "graphitron_ast_field_reference_for_condition_step_entry",
            "graphitron_ast_service_entry", "graphitron_ast_service_context_arg_entry",
            "graphitron_ast_external_field_entry", "graphitron_ast_source_row_entry",
            "graphitron_ast_connection_entry", "graphitron_ast_field_node_id_entry",
            "graphitron_ast_mutation_entry", "graphitron_ast_pivot_entry",
            "graphitron_ast_default_order_entry", "graphitron_ast_default_order_field_entry",
            "graphitron_ast_routine_entry",
            // Its columnMapping pairs, decoded where the string was written. The oracle it
            // would be compared against is the same one its parent has none of, and a pair
            // carries less besides: the walk that wrote the resolved relation is gone, so
            // there is no second reading of the string left to differ from this one. What
            // pins it is GraphitronSiteAnchorAgreementTest, over a corpus writing a
            // two-pair mapping on one of a field's two routines: both strata are asserted
            // there, because the grammar's index is the number a derivation could get wrong
            // while still producing the right rows.
            "graphitron_ast_routine_column_mapping_pair_entry",
            // Deprecation, which the walk this replaces never captured at all: it recognised the
            // two markers on a parsed registry at the moment a reader asked. GraphitronSchema has
            // no counterpart because there was nothing to shadow, and the docstring convention is
            // graphitron's own, so neither arm of a differential has an oracle to be compared
            // against. What pins them is GraphitronAnchorTest, over a corpus carrying both markers
            // and over the bundled vocabulary, which declares two of its own.
            "graphitron_ast_input_value_deprecated_entry",
            "graphitron_deprecated",
            // And of an input-value application, where the two paths do not even agree on which
            // relation the row belongs to: GraphitronSchema routes an input object's field down its
            // field path, and the parser calls it an input value, so there is no population here to
            // compare. GraphitronInputValueEntriesTest pins them, over a corpus writing both parents
            // a consumer schema writes and repeating a repeatable directive on one input value.
            "graphitron_ast_input_value_binding_entry",
            "graphitron_ast_input_value_condition_entry",
            "graphitron_ast_input_value_condition_context_arg_entry",
            "graphitron_ast_input_value_reference_key_step_entry",
            "graphitron_ast_input_value_reference_table_step_entry",
            "graphitron_ast_input_value_reference_condition_step_entry",
            "graphitron_ast_input_value_reference_for_entry",
            "graphitron_ast_input_value_reference_for_key_step_entry",
            "graphitron_ast_input_value_reference_for_table_step_entry",
            "graphitron_ast_input_value_reference_for_condition_step_entry",
            "graphitron_ast_input_value_node_id_entry",
            // And of an enum-value application, the sorting vocabulary read where it was written.
            // Keyed by the position of the @ token like every entry above it, where GraphitronSchema
            // holds one sort specification per enum value, so a value carrying two applications is
            // two rows here and one reading there. GraphitronEnumValueEntriesTest pins them, over a
            // corpus writing each of the three surfaces @order admits and a field list whose
            // elements do not all decode.
            "graphitron_ast_enum_value_binding_entry", "graphitron_ast_index_entry",
            "graphitron_ast_order_entry", "graphitron_ast_order_field_entry",
            // And of a schema-site application, the one site whose subject is the corpus rather
            // than an element of it. GraphitronSchema holds federation's opt-in as a parsed
            // specification list where these hold what was written, so there is nothing to compare.
            // GraphitronSchemaEntriesTest pins them, over a corpus writing both import spellings
            // and repeating the directive on one schema.
            "graphitron_ast_link_entry", "graphitron_ast_link_import_entry",
            // What went wrong making a schema out of the documents, as graphql-java stated it,
            // at whichever of the three reading stages said so. GraphitronSchema is only ever
            // built from a corpus that made a schema, so it has nothing to say about one that did
            // not. The family's only verdict relation, and unshadowed for that reason rather than
            // by exemption: the walk's own two verdict relations are retired, so there is no
            // oracle arm left here to agree with. GraphQLSchemaProblemsTest pins it, over a corpus
            // that provokes each stage in turn.
            "graphql_schema_problem")) {
            registrations.put(relation, Arm.UNSHADOWED);
        }
        // The classfile census: what the compiled classes on the classpath declare, read with no
        // vocabulary from any schema: what an author may name at one directive, admitted by that
        // arm's own rule rather than by a census's. GraphitronSchema has no counterpart because the
        // walk it came from resolved a reference only once an author had written one, where an arm
        // states the candidates whether or not anybody has. What pins it is CodeCaptureTest.
        registrations.put("code_scalar_constant", Arm.UNSHADOWED);
        registrations.put("code_method", Arm.UNSHADOWED);
        registrations.put("code_type", Arm.UNSHADOWED);
        registrations.put("code_type_element", Arm.UNSHADOWED);
        registrations.put("code_type_slot", Arm.UNSHADOWED);
        registrations.put("code_method_parameter", Arm.UNSHADOWED);
        registrations.put("code_method_exception", Arm.UNSHADOWED);
        registrations.put("code_condition_method", Arm.UNSHADOWED);
        registrations.put("code_condition_method_parameter_table", Arm.UNSHADOWED);
        registrations.put("code_external_field_method", Arm.UNSHADOWED);
        registrations.put("code_service_method", Arm.UNSHADOWED);
        registrations.put("code_throwable", Arm.UNSHADOWED);
        registrations.put("code_throwable_supertype", Arm.UNSHADOWED);
        // The entry index: one row per written position the nineteen entry relations hold,
        // unioned by the pass that derives the anchors. Nothing of GraphitronSchema's to agree
        // with, the walk having no notion of a position that is not a declaration, and its own
        // claims are pinned in AstEntryIndexTest: every arm's positions present, the enclosing
        // element resolved at each depth, and a retired position swept.
        registrations.put("graphql_ast_entry", Arm.DERIVED);
        registrations.put("graphql_ast_element_entry", Arm.DERIVED);
        registrations.put("graphql_ast_directive_application_entry", Arm.DERIVED);
        registrations.put("graphql_directive_site", Arm.DERIVED);
        registrations.put("graphql_element_field", Arm.DERIVED);
        registrations.put("graphitron_tabletype", Arm.DERIVED);
        registrations.put("graphitron_node", Arm.DERIVED);
        registrations.put("graphitron_node_keycolumn", Arm.DERIVED);
        registrations.put("graphitron_field_table", Arm.DERIVED);
        registrations.put("graphitron_field_routine", Arm.DERIVED);
        registrations.put("graphitron_field_chain_link", Arm.DERIVED);
        registrations.put("graphitron_field_table_link", Arm.DERIVED);
        registrations.put("graphitron_element", Arm.DERIVED);
        registrations.put("graphitron_type", Arm.DERIVED);
        registrations.put("graphitron_field", Arm.DERIVED);
        registrations.put("graphitron_argument", Arm.DERIVED);
        registrations.put("graphitron_minted_conflict", Arm.DERIVED);
        registrations.put("graphitron_field_chain_application", Arm.DERIVED);
        registrations.put("intent_authored_field_claim", Arm.DERIVED);
        registrations.put("intent_authored_type_claim", Arm.DERIVED);
        registrations.put("intent_bound_table", Arm.DERIVED);
        registrations.put("graphitron_spelled_table", Arm.DERIVED);
        registrations.put("graphitron_field_reference_step_hop", Arm.DERIVED);
        registrations.put("graphitron_field_reference_step_hop_keyed", Arm.DERIVED);
        registrations.put("graphitron_field_reference_step_hop_keyless", Arm.DERIVED);
        registrations.put("sql_name_matched_key_column", Arm.DERIVED);
        registrations.put("sql_table_reference", Arm.DERIVED);
        registrations.put("intent_condition_method_route", Arm.DERIVED);
        registrations.put("intent_condition_method_route_defect", Arm.DERIVED);
        registrations.put("intent_java_enum_class", Arm.DERIVED);
        registrations.put("intent_condition_param_decode", Arm.DERIVED);
        registrations.put("intent_jvm_ancestor", Arm.DERIVED);
        registrations.put("intent_condition_slot", Arm.DERIVED);
        registrations.put("intent_scalar_java_type", Arm.DERIVED);
        registrations.put("intent_condition_context_parameter", Arm.DERIVED);
        registrations.put("intent_table_key_candidate", Arm.DERIVED);
        registrations.put("intent_node_metadata_defect", Arm.DERIVED);
        registrations.put("graphitron_node_type", Arm.DERIVED);
        registrations.put("intent_synthesized_federation_key", Arm.DERIVED);
        registrations.put("intent_federation_key", Arm.DERIVED);
        registrations.put("graphitron_field_reference_step_target", Arm.DERIVED);
        registrations.put("graphitron_field_reference_step_target_keyed", Arm.DERIVED);
        registrations.put("graphitron_field_reference_step_target_keyless", Arm.DERIVED);
        registrations.put("intent_field_reference_step_fanout", Arm.DERIVED);
        registrations.put("intent_argument_reference_step_hop", Arm.DERIVED);
        registrations.put("intent_argument_reference_step_target", Arm.DERIVED);
        registrations.put("intent_field_chain_start", Arm.DERIVED);
        registrations.put("intent_field_chain_node", Arm.DERIVED);
        registrations.put("intent_field_chain_terminus", Arm.DERIVED);
        registrations.put("intent_field_reference_discovery", Arm.DERIVED);
        registrations.put("graphitron_minted_type", Arm.DERIVED);
        registrations.put("graphitron_minted_field", Arm.DERIVED);
        registrations.put("graphitron_connection_element_type", Arm.DERIVED);
        registrations.put("intent_field_navigated_type", Arm.DERIVED);
        registrations.put("intent_routine_return_binding", Arm.DERIVED);
        registrations.put("graphitron_resolved_type_binding", Arm.DERIVED);
        registrations.put("intent_resolved_node_key_shape", Arm.DERIVED);
        registrations.put("intent_field_participant_scope_table", Arm.DERIVED);
        registrations.put("intent_field_scope_table", Arm.DERIVED);
        registrations.put("intent_argument_scope_table", Arm.DERIVED);
        registrations.put("intent_input_field_resolving_table", Arm.DERIVED);
        registrations.put("intent_input_field_resolving_table_live", Arm.DERIVED);
        registrations.put("intent_input_field_reference_step_target", Arm.DERIVED);
        registrations.put("intent_input_field_column_scope", Arm.DERIVED);
        registrations.put("intent_input_field_column_match", Arm.DERIVED);
        registrations.put("intent_input_field_column_match_live", Arm.DERIVED);
        registrations.put("intent_argument_reference_step_target_live", Arm.DERIVED);
        registrations.put("intent_input_field_filter_role", Arm.DERIVED);
        registrations.put("intent_input_field_carrier_role", Arm.DERIVED);
        registrations.put("intent_condition_membership", Arm.DERIVED);
        registrations.put("intent_field_scope_table_live", Arm.DERIVED);
        registrations.put("intent_mutation_write_payload", Arm.DERIVED);
        registrations.put("intent_mutation_write_payload_live", Arm.DERIVED);
        registrations.put("intent_mutation_payload_refusal", Arm.DERIVED);
        registrations.put("intent_mutation_payload_refusal_live", Arm.DERIVED);
        registrations.put("intent_mutation_payload_column", Arm.DERIVED);
        registrations.put("intent_mutation_payload_column_live", Arm.DERIVED);
        registrations.put("intent_mutation_matched_key", Arm.DERIVED);
        registrations.put("intent_mutation_payload_key_membership", Arm.DERIVED);
        registrations.put("intent_mutation_payload_key_membership_live", Arm.DERIVED);
        registrations.put("intent_mutation_write_refusal", Arm.DERIVED);
        registrations.put("intent_mutation_write_destination", Arm.DERIVED);
        registrations.put("intent_mutation_write_destination_live", Arm.DERIVED);
        registrations.put("intent_mutation_write_agreement", Arm.DERIVED);
        registrations.put("intent_node_id_instruction", Arm.DERIVED);
        registrations.put("intent_node_id_decode_endpoint", Arm.DERIVED);
        registrations.put("intent_node_id_decode_hop", Arm.DERIVED);
        registrations.put("intent_node_id_decode_hop_column", Arm.DERIVED);
        registrations.put("intent_node_id_decode_column", Arm.DERIVED);
        registrations.put("intent_node_id_decode_slot", Arm.DERIVED);
        registrations.put("intent_record_slot_assignable", Arm.DERIVED);
        registrations.put("intent_node_container_member", Arm.DERIVED);
        registrations.put("intent_node_id_candidate_node_type", Arm.DERIVED);
        registrations.put("intent_node_id_polymorphic_decode_defect", Arm.DERIVED);
        registrations.put("intent_node_id_decode", Arm.DERIVED);
        registrations.put("intent_node_id_decode_defect", Arm.DERIVED);
        registrations.put("intent_node_id_decode_landing_defect", Arm.DERIVED);
        registrations.put("intent_reference_for_application", Arm.DERIVED);
        registrations.put("intent_foreign_key_column_pair", Arm.DERIVED);
        registrations.put("intent_node_id_encode", Arm.DERIVED);
        registrations.put("intent_argument_scope_table_live", Arm.DERIVED);
        registrations.put("intent_node_id_decode_hop_live", Arm.DERIVED);
        registrations.put("intent_node_id_decode_hop_column_live", Arm.DERIVED);
        registrations.put("intent_node_id_decode_column_live", Arm.DERIVED);
        registrations.put("intent_input_field_carrier_role_live", Arm.DERIVED);
        registrations.put("intent_field_column_scope_live", Arm.DERIVED);
        registrations.put("intent_node_id_instruction_live", Arm.DERIVED);
        registrations.put("graphitron_argmapping_match", Arm.DERIVED);
        registrations.put("intent_argmapping_bound_parameter_type", Arm.DERIVED);
        registrations.put("intent_argmapping_key_column_candidate", Arm.DERIVED);
        registrations.put("intent_resolved_node_key_projection", Arm.DERIVED);
        registrations.put("intent_argmapping_projection_defect", Arm.DERIVED);
        registrations.put("intent_field_payload_producer", Arm.DERIVED);
        registrations.put("intent_poly_member", Arm.DERIVED);
        registrations.put("intent_errors_field", Arm.DERIVED);
        registrations.put("intent_errors_field_member", Arm.DERIVED);
        registrations.put("intent_field_error_channel", Arm.DERIVED);
        registrations.put("intent_carrier_data_field", Arm.DERIVED);
        registrations.put("intent_carrier_data_field_live", Arm.DERIVED);
        registrations.put("intent_carrier_routine_hop", Arm.DERIVED);
        registrations.put("intent_mutation_routine_seat", Arm.DERIVED);
        registrations.put("intent_column_match_claim", Arm.DERIVED);
        registrations.put("intent_field_column_scope", Arm.DERIVED);
        registrations.put("intent_argument_column_scope", Arm.DERIVED);
        registrations.put("intent_argument_column_scope_live", Arm.DERIVED);
        registrations.put("intent_argument_column_match", Arm.DERIVED);
        registrations.put("intent_argument_column_match_live", Arm.DERIVED);
        registrations.put("intent_input_field_filter_role_live", Arm.DERIVED);
        registrations.put("intent_argument_filter_role", Arm.DERIVED);
        registrations.put("intent_facet_binding", Arm.DERIVED);
        registrations.put("intent_connection_facet", Arm.DERIVED);
        registrations.put("intent_field_column_table", Arm.DERIVED);
        registrations.put("intent_field_separate_fetch", Arm.DERIVED);
        registrations.put("intent_field_producer_reference", Arm.DERIVED);
        registrations.put("intent_field_producer_method", Arm.DERIVED);
        registrations.put("intent_field_routine_method", Arm.DERIVED);
        registrations.put("intent_delivery_container", Arm.DERIVED);
                registrations.put("intent_declared_type_element", Arm.DERIVED);
        registrations.put("intent_field_accessor_hop", Arm.DERIVED);
        registrations.put("intent_type_backing_seed", Arm.DERIVED);
        registrations.put("intent_type_backing_class", Arm.DERIVED);
        registrations.put("intent_type_backing", Arm.DERIVED);
        registrations.put("intent_type_backing_conflict", Arm.DERIVED);
        registrations.put("intent_producer_cardinality_conflict", Arm.DERIVED);
        registrations.put("intent_external_field_contract_defect", Arm.DERIVED);
        registrations.put("intent_resolved_field_claim", Arm.DERIVED);
        registrations.put("intent_type_domain", Arm.DERIVED);
        registrations.put("intent_field_demand_rule", Arm.DERIVED);
        registrations.put("intent_field_exemption_rule", Arm.DERIVED);
        registrations.put("intent_type_demand", Arm.DERIVED);
        registrations.put("intent_type_exemption", Arm.DERIVED);
        registrations.put("intent_resolved_field_demand", Arm.DERIVED);
        registrations.put("intent_resolved_type_demand", Arm.DERIVED);
        registrations.put("graphitron_argmapping_candidate", Arm.DERIVED);
        registrations.put("intent_input_occurrence_path", Arm.DERIVED);
        registrations.put("intent_input_occurrence_path_step", Arm.DERIVED);
        registrations.put("intent_input_occurrence_descent_order", Arm.DERIVED);
        registrations.put("intent_input_occurrence_override", Arm.DERIVED);
        registrations.put("intent_authored_claim_conflict", Arm.DERIVED);
        registrations.put("intent_authored_claim_rejection", Arm.DERIVED);
        // The never-unsorted honesty rule and its post-capture mint, on the claim-conflict pair's
        // terms exactly: there is no walk-side answer to agree with, the walk having no rule that
        // compares an available ordering against the ordering a read shape delivers, and what the
        // rule returns given rows is the relation's own algebra, stated in the module whose DDL
        // declares it (FieldUnlowerableOrderingTest) with the decode's own report pinned above it
        // (no.sikt.graphitron.rewrite.derive.UnlowerableOrderingsTest).
        registrations.put("intent_field_unlowerable_ordering", Arm.DERIVED);
        registrations.put("intent_field_unlowerable_ordering_rejection", Arm.DERIVED);
        // The diagnostics union view is a pure re-projection of its five arms, so its agreement
        // is vacuous by construction on the graphql_directive_site precedent; the arm-specific
        // derived columns are pinned by DiagnosticFactsTest against their Java spellings.
        registrations.put("diagnostic", Arm.DERIVED);
        // The schema self-description stratum: views over row values authored in the DDL itself,
        // so capture never writes them and agreement with the walk is vacuous by construction.
        // Their anchors are the roster gates in FactSchemaGateTest, which close the family rows
        // against the observed relations in both directions on every build, and the roster gates
        // in no.sikt.graphitron.model.FamilyRosterGateTest, which close the two page rosters the
        // same way. meta_relation_reference is derived in the narrower sense as well: its rows
        // are the engine's own declared foreign keys, so nothing authored can disagree with them.
        registrations.put("meta_family", Arm.DERIVED);
        registrations.put("meta_family_headline", Arm.DERIVED);
        registrations.put("meta_family_bridge", Arm.DERIVED);
        registrations.put("meta_prefixless_relation", Arm.DERIVED);
        registrations.put("meta_relation_family", Arm.DERIVED);
        registrations.put("meta_relation_reference", Arm.DERIVED);
        registrations.put("meta_materialize", Arm.DERIVED);
        registrations.put("meta_materialize_dependency", Arm.DERIVED);
        // The declaration rosters sit in the same stratum as tables whose rows an INSERT in the
        // DDL supplies, so capture never writes them either. Their anchors are the DDL's own
        // keys, foreign keys and CHECK constraints, the declaration gates in
        // no.sikt.graphitron.model.MetaDeclarationGateTest (which close the undeclared roster,
        // the comment echo, the corpus agreement, the key-shape match and the view-ownership
        // rule against the observed schema), and the gatherer-class gate in FactSchemaGateTest,
        // which holds every meta_gatherer row to a class that exists.
        registrations.put("meta_corpus", Arm.DERIVED);
        registrations.put("meta_gatherer", Arm.DERIVED);
        registrations.put("meta_gatherer_corpus", Arm.DERIVED);
        registrations.put("meta_gatherer_dependency", Arm.DERIVED);
        registrations.put("meta_grain", Arm.DERIVED);
        registrations.put("meta_relation", Arm.DERIVED);
        registrations.put("meta_stated_relation", Arm.DERIVED);
        // The lint vocabulary, on the meta_ rosters' terms: rows this file supplies, so there
        // is no gatherer whose reading could be compared against anything. What pins it is
        // LintRuleCatalogueTest, against the enum the rows state.
        registrations.put("lint_rule", Arm.DERIVED);
        // The findings, derived from the transcription and the decode beside it. Nothing of
        // GraphitronSchema's to agree with: the walk produced a list per run and kept no row,
        // so there is no second population to compare against. What pins it is the two shadow
        // tests, which read one corpus through the view and through the walk.
        registrations.put("lint_violation", Arm.DERIVED);
        registrations.put("javac_diagnostic", Arm.ORACLE);
        registrations.put("rejection_validation_error", Arm.ORACLE);
        registrations.put("rejection_validation_error_directive", Arm.ORACLE);
        registrations.put("lint_finding", Arm.ORACLE);
        registrations.put("lint_finding_fix", Arm.ORACLE);
        registrations.put("lint_finding_fix_edit", Arm.ORACLE);
        registrations.put("build_warning_no_rule", Arm.ORACLE);
        return Map.copyOf(registrations);
    }
    @Test
    @DisplayName("every generated relation has a registered agreement source")
    void everyRelationIsRegistered() {
        var unregistered = new ArrayList<String>();
        for (var table : Public.PUBLIC.getTables()) {
            String relation = table.getName().toLowerCase(Locale.ROOT);
            if (!REGISTRATIONS.containsKey(relation)) {
                unregistered.add(relation);
            }
        }
        assertThat(unregistered)
            .as("relations with no registered agreement source; register one rather than skipping")
            .isEmpty();
        assertThat(REGISTRATIONS.keySet())
            .as("registrations for relations the DDL no longer declares")
            .allSatisfy(relation -> assertThat(Public.PUBLIC.getTables().stream()
                .anyMatch(t -> t.getName().equalsIgnoreCase(relation))).isTrue());
    }
    /**
     * The split the element family introduced, stated as the invariant it rests on: an anchor
     * exists exactly where its attribute sibling has a row, in both directions. The equality is
     * what lets the containment arm above cover four relations with one claim, and it is the thing
     * a producer can break without any constraint noticing: a foreign key stops an attribute row
     * with no anchor, and nothing at all stops an anchor with no attributes. Both directions of
     * every pair, so a coordinate the walk claims and then declines to describe is caught here
     * rather than read downstream as a type or field with no properties.
     *
     * <p>Written as one query per pair rather than a join over the DDL, because the pairing is a
     * modelling decision and a reader should be able to see which relations it names.
     */
    @Test
    @DisplayName("every coordinate anchor pairs with its attribute relation, both ways")
    void coordinatesAgreeWithTheirAttributeRelations(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, AgreementCorpus.SDL)) {
            var dsl = store.dsl();
            assertThat(dsl.select(GRAPHQL_TYPE_ELEMENT.TYPE_NAME).from(GRAPHQL_TYPE_ELEMENT).fetch())
                .as("type anchors against type attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE).fetch());
            assertThat(dsl.select(GRAPHQL_FIELD_ELEMENT.TYPE_NAME, GRAPHQL_FIELD_ELEMENT.FIELD_NAME)
                .from(GRAPHQL_FIELD_ELEMENT).fetch())
                .as("field anchors against field attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME).from(GRAPHQL_FIELD).fetch());
            assertThat(dsl.select(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME,
                    GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME, GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME)
                .from(GRAPHQL_ARGUMENT_ELEMENT).fetch())
                .as("argument anchors against argument attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_ARGUMENT.TYPE_NAME, GRAPHQL_ARGUMENT.FIELD_NAME,
                        GRAPHQL_ARGUMENT.ARGUMENT_NAME).from(GRAPHQL_ARGUMENT).fetch());
            assertThat(dsl.select(GRAPHQL_ENUM_VALUE_ELEMENT.TYPE_NAME,
                    GRAPHQL_ENUM_VALUE_ELEMENT.VALUE_NAME).from(GRAPHQL_ENUM_VALUE_ELEMENT).fetch())
                .as("enum value anchors against enum value attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_ENUM_VALUE.TYPE_NAME, GRAPHQL_ENUM_VALUE.VALUE_NAME)
                        .from(GRAPHQL_ENUM_VALUE).fetch());
            assertThat(dsl.select(GRAPHQL_DIRECTIVE_ELEMENT.DIRECTIVE_NAME)
                .from(GRAPHQL_DIRECTIVE_ELEMENT).fetch())
                .as("directive anchors against directive attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME).from(GRAPHQL_DIRECTIVE).fetch());
            assertThat(dsl.select(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT.DIRECTIVE_NAME,
                    GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT.ARGUMENT_NAME)
                .from(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT).fetch())
                .as("directive argument anchors against directive argument attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME,
                        GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME)
                        .from(GRAPHQL_DIRECTIVE_ARGUMENT).fetch());
            // The supertype against the six it generalises. This direction is the one no
            // constraint reaches: a foreign key refuses an anchor with no supertype row and
            // nothing refuses a supertype row no anchor claimed, so the equality is stated here.
            // The field arm restates the rule the stored kind rests on rather than repeating the
            // literal: a field and an input field share a coordinate form and an anchor relation,
            // and the declaring type's kind is the only thing that tells them apart. Writing the
            // join here is what makes the kind falsifiable, where a literal would agree with
            // whatever capture happened to write.
            assertThat(dsl.select(GRAPHQL_ELEMENT.COORDINATE, GRAPHQL_ELEMENT.ELEMENT_KIND)
                .from(GRAPHQL_ELEMENT).fetch())
                .as("the element supertype against the union of the six anchors")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_TYPE_ELEMENT.COORDINATE, val("NAMED_TYPE"))
                        .from(GRAPHQL_TYPE_ELEMENT)
                    .unionAll(dsl.select(GRAPHQL_FIELD_ELEMENT.COORDINATE,
                            when(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT"), val("INPUT_FIELD"))
                                .otherwise(val("FIELD")))
                        .from(GRAPHQL_FIELD_ELEMENT)
                        .join(GRAPHQL_TYPE)
                        .on(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME))
                        .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD_ELEMENT.TYPE_NAME)))
                    .unionAll(dsl.select(GRAPHQL_ARGUMENT_ELEMENT.COORDINATE, val("FIELD_ARGUMENT"))
                        .from(GRAPHQL_ARGUMENT_ELEMENT))
                    .unionAll(dsl.select(GRAPHQL_ENUM_VALUE_ELEMENT.COORDINATE, val("ENUM_VALUE"))
                        .from(GRAPHQL_ENUM_VALUE_ELEMENT))
                    .unionAll(dsl.select(GRAPHQL_DIRECTIVE_ELEMENT.COORDINATE, val("DIRECTIVE"))
                        .from(GRAPHQL_DIRECTIVE_ELEMENT))
                    .unionAll(dsl.select(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT.COORDINATE,
                            val("DIRECTIVE_ARGUMENT"))
                        .from(GRAPHQL_DIRECTIVE_ARGUMENT_ELEMENT))
                    .fetch());
            // The equality above is only as strong as the kinds the fixture reaches. Without an
            // input object in it the field arm's two branches collapse to one and the split it
            // states cannot fail, which is the shape of vacuous assertion this family has already
            // shipped once.
            assertThat(dsl.select(GRAPHQL_ELEMENT.ELEMENT_KIND).from(GRAPHQL_ELEMENT)
                .fetchSet(GRAPHQL_ELEMENT.ELEMENT_KIND))
                .as("the fixture has to reach every kind, or the equality above is partly vacuous")
                .contains("NAMED_TYPE", "FIELD", "INPUT_FIELD", "ENUM_VALUE", "FIELD_ARGUMENT",
                    "DIRECTIVE", "DIRECTIVE_ARGUMENT");
        }
    }
}
