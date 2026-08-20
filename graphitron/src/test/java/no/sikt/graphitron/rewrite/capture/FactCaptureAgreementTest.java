package no.sikt.graphitron.rewrite.capture;

import graphql.language.Directive;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.language.TypeDefinition;
import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ConnectionSynthesis;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.compile.CompileDiagnostic;
import no.sikt.graphitron.rewrite.compile.CompileFacts;
import no.sikt.graphitron.rewrite.compile.CompileRound;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DECLARATION_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.INTENT_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.INTENT_SYNTHESIZED_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_SITE;
import static no.sikt.graphitron.model.Tables.JAVAC_DIAGNOSTIC;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_DISABLED_RULE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_OUTPUT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_MOUNT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_UNMOUNT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SUPERGRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_TENANT_COLUMN;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_METADATA_DEFECT;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.BUILD_WARNING_NO_RULE;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SYNTAX_ERROR;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_ERROR;
import static no.sikt.graphitron.model.Tables.LINT_FINDING;
import static no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR;
import static no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_STAMP;
import static no.sikt.graphitron.model.Tables.WALK_TYPE_BACKING_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_RETURN_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT_TYPE_REF;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_COORDINATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow period's honesty check: the store is filled beside the live pipeline and nobody reads
 * it, so nothing but a test can notice capture drifting from the model it shadows.
 *
 * <p>The driver is mechanical. It enumerates the generated jOOQ relations and fails on any one
 * without a registered agreement source, so a relation added to the DDL cannot arrive unchecked.
 * Registrations form a closed set of four arms, which is why there is no skip list:
 * <ul>
 *   <li>{@link Arm#CONTAINMENT} for the SDL side. Capture is total and {@code GraphitronSchema} is
 *       reachability-pruned, so the honest relation is that the store contains the model. The claim
 *       is now about authored SDL throughout: {@code graphitron_federation_key} held
 *       macro-synthesized rows under it until federation's key rule became a derivation, so a
 *       containment arm here says the store transcribes what the document declares and nothing
 *       else.</li>
 *   <li>{@link Arm#EQUALITY} for the catalog and scanner censuses, which are the same walk reduced
 *       two ways. The {@code java_} family joins them with its anchors elsewhere, in
 *       {@code JavaSourceFactsTest}: it is written by neither capture nor a graph, so its
 *       lifecycle anchor is partitioned by source file where the oracle families' are partitioned
 *       by graph, and there is no fixture here that would fill it.</li>
 *   <li>{@link Arm#DERIVED} for shipped derivations: views, the materialized capture-cadence
 *       derivations, and the authored rows describing the schema itself. A hand-written
 *       materialized derivation ({@code intent_type_domain}) has a writer that re-derives it
 *       inside every capture, materialized only because H2 has no safe recursive view form for a
 *       cyclic type graph; its cadence and clearing follow the derivation, not an oracle, and the
 *       warm/cold census anchors its lifecycle like any capture-written rows. A registered
 *       materialization is two relations under one rule and both are derived: the {@code _live}
 *       view stating it, and the canonically named target the materializer refills from that view
 *       on the same cadence. The {@code meta_} rows are derived in the widest sense of the arm,
 *       being rows the DDL alone determines rather than anything a run reads, which is why
 *       {@code meta_materialize} sits here beside the three rosters and why
 *       {@code meta_materialize_dependency}, whose rows the bootstrap derives from the stored
 *       view definitions, sits beside it. A pure re-projection
 *       ({@code graphql_directive_site}) registers the base relations it projects and its
 *       agreement is vacuous by construction; a semantic derivation (the {@code intent_} claim
 *       views and the demand stratum) registers with its own anchor instead, which lives with
 *       its reader's test
 *       (the authored-claim views carry their reason in two places, because a rule reading what an
 *       author wrote is asked both what it makes of a set of applications and whether real SDL
 *       reaches it in that shape: {@code no.sikt.graphitron.rewrite.derive.AuthoredClaimConflictsTest}
 *       binds the lookup arm to {@code LookupFacts.triggersFor} over a real capture, while
 *       {@code no.sikt.graphitron.model.intent.AuthoredClaimTest} binds each arm pair, its position
 *       mask and its ordinal collapse to a census stated row by row in the module whose DDL
 *       declares them; the column-match view carries its reason in two places for the same reason,
 *       though the two questions asked of it have different lifetimes.
 *       {@code no.sikt.graphitron.rewrite.derive.ColumnMatchShadowTest} binds the view and the
 *       reduction over it to the classification walk's fall-through arm over the spec-by-example
 *       corpus, which is the half that retires when the walk does, while
 *       {@code no.sikt.graphitron.model.intent.ColumnMatchClaimTest} binds what the view returns
 *       given rows, and the table resolution it stands on ({@code intent_bound_table}) through the
 *       claim's own table witness, to a catalog stated table by table in the module whose DDL
 *       declares them: the two-tier name match with the tiers deliberately disagreeing, three
 *       decoy tables each differing from the bound one in one part of the witness key, and the
 *       bindings a column claim cannot speak for;
 *       {@code no.sikt.graphitron.model.intent.ReferenceStepTargetTest} binds the spelling
 *       resolution the bindings themselves stand on ({@code intent_spelled_table}) and the
 *       {@code @reference} chain over it ({@code intent_field_reference_step_hop} and
 *       {@code intent_field_reference_step_target}, the hop view pinned through the chain that
 *       reaches or refuses it rather than twice), to a catalog stated table by table in the module
 *       whose DDL declares them, the shapes each case turns on (two foreign keys between one pair
 *       of tables, a key pointing back at its own table, a constraint name two schemas both
 *       declare, a table name two schemas both declare) being ones no one real catalog offers side
 *       by side;
 *       {@code no.sikt.graphitron.model.intent.ArgumentReferenceStepTargetTest} binds the
 *       argument-site pair ({@code intent_argument_reference_step_hop} and
 *       {@code intent_argument_reference_step_target}, the hop view pinned through the chain for the
 *       same reason) against that same catalog, and additionally binds the two views to each other:
 *       the arms are textually parallel, so one case seeds one path shape at both sites and asserts
 *       the shared columns come back equal, which is what keeps a change to one from silently
 *       leaving the other behind;
 *       {@code no.sikt.graphitron.model.intent.ArgumentScopeTableTest} binds
 *       {@code intent_argument_scope_table}, which table an argument's column-shaped content binds
 *       against, one case per rung plus the cases where the rungs compete, the precedence being the
 *       claim rather than the table that came out;
 *       {@code no.sikt.graphitron.model.intent.NodeIdInstructionTest} binds
 *       {@code intent_node_id_instruction}, which slots carry the {@code @nodeId} instruction and
 *       which node type each names, with one case per basis because the basis is the claim: a
 *       coordinate answered by the wrong rule resolves the wrong node type wherever several rules
 *       could answer, so the assertion is which rule answered and not merely that something did;
 *       {@code no.sikt.graphitron.model.intent.NodeIdDecodeReachTest} binds the pair a decode
 *       navigates on ({@code intent_node_id_decode_endpoint} and
 *       {@code intent_node_id_decode_hop}, the hop child pinned through the endpoint relation's
 *       navigation column rather than twice), one case per navigation and one per shape that
 *       contributes no hop, the two silences meaning different things being the claim;
 *       {@code no.sikt.graphitron.model.intent.NodeIdDecodeColumnTest} binds where the decoded
 *       values land ({@code intent_node_id_decode_column}, and with it the foreign-key pairing
 *       ({@code intent_foreign_key_column_pair}) and its walk-oriented reading
 *       ({@code intent_node_id_decode_hop_column}) that the lift walks over), one case per shape
 *       the lift either carries or drops, the absent local column being the claim rather than the
 *       present one;
 *       {@code no.sikt.graphitron.model.intent.NodeIdEncodeTest} binds
 *       {@code intent_node_id_encode}, where an encoding field's tuple comes from, one case per
 *       source and one per condition that routes a field to the read family, plus the arity
 *       precondition that applies to one source and not the other;
 *       {@code no.sikt.graphitron.rewrite.derive.ChainTerminusTest} binds
 *       {@code intent_field_chain_terminus}, where a {@code @routine} chain lands and what kind of
 *       table that is, and with it the hop view's name-matched arm, which only a chain departing a
 *       function result reaches;
 *       {@code no.sikt.graphitron.rewrite.derive.ReferenceDiscoveryTest} binds
 *       {@code intent_field_reference_discovery}, what an omitted path finds between a field's two
 *       endpoints, over captured SDL against that same catalog for the same reason and because each
 *       shape it has to answer for is a shape the catalog already declares, half its cases pinning
 *       the coordinates that discover nothing because the boundary of a discovery is what the
 *       relation claims;
 *       {@code no.sikt.graphitron.rewrite.derive.RoutineReturnBindingTest} binds
 *       {@code intent_routine_return_binding}, the table a type is bound to by being what a chain
 *       returns, and {@code intent_resolved_type_binding}, where that population meets the
 *       {@code @table} one, including the seat the derivation excludes and the arity the
 *       coalescing recount produces;
 *       {@code no.sikt.graphitron.rewrite.derive.NameMatchedKeyPairTest} binds the pair behind a hop
 *       out of a table-valued function's result, {@code intent_name_matched_key_pair} stating how
 *       such a hop is keyed and {@code intent_carrier_routine_hop} the population that reaches that
 *       rule from a coordinate no author wrote a path at; the pairing half asserts against the test
 *       catalog's own functions without scoping by graph, the relation carrying no graph partition,
 *       and half of both halves pins what the relations refuse to hold, a rule pairing every
 *       function against every keyed table being mostly boundary;
 *       {@code no.sikt.graphitron.rewrite.derive.CarrierDataFieldTest} binds
 *       {@code intent_carrier_data_field}, where a mutation payload's data arrives, over captured
 *       SDL against that same catalog and the test classes' own census, half its cases pinning the
 *       payload shapes that name nothing because a carrier's boundary is most of what the relation
 *       claims;
 *       {@code no.sikt.graphitron.model.intent.FieldColumnTableTest} binds
 *       {@code intent_field_column_table}, the override a column name's site resolves through, half
 *       its cases pinning the coordinates that produce no row because the boundary of an override
 *       is what the relation claims, together with {@code intent_field_column_scope}, the
 *       navigation the override reads and the column-match classifier reads with it, whose own
 *       cases are the third rule the override drops and the precedence between the three; it reads
 *       both seeded in the module whose DDL declares them, several of the states a boundary case
 *       needs (a macro-rewritten type expression, a contested coordinate, a spelling two schemas
 *       both answer) being ones a fixture states in a line and a capture arranges in a schema;
 *       {@code intent_class_member_slot}, the member names a backing class offers, carries its
 *       reason in two places, because a rule reading a class's declared form is asked both what it
 *       makes of a declaration and whether a compiler's own declarations reach it in that shape.
 *       {@code no.sikt.graphitron.rewrite.derive.ClassMemberSlotScanTest} binds the second, in one
 *       case, over a real classfile scan of three fixture classes whose declared form the test did
 *       not write, while {@code no.sikt.graphitron.model.intent.ClassMemberSlotTest} binds the
 *       first to a census stated row by row in the module whose DDL declares it: the two arms' own
 *       owner key, through one class name declared by two classpath entries, the parameter
 *       anti-join's descriptor, through a bean accessor overloaded with a parameterised twin, and
 *       the record arm's gate, through a component under a class no compiler would pair it with;
 *       {@code no.sikt.graphitron.model.intent.ClassAssignableTest} binds
 *       {@code intent_class_assignable}, the closure over those declarations, to a census stated
 *       row by row, the chains a closure has to get right (one crossing classpath entries, one
 *       ending at a name no entry declares, one reaching a type two ways) being ones a scan of
 *       compiled fixtures cannot arrange; it reads the relation seeded in the module whose DDL
 *       declares it, the walk having no side to bring to a question about a closure over rows;
 *       {@code no.sikt.graphitron.model.intent.FieldProducerMethodTest} binds the producer pair,
 *       {@code intent_field_producer_reference} naming the method an authored Java reference spells
 *       and {@code intent_field_producer_method} the census method it resolves to, to rows stated on
 *       both sides in the module whose DDL declares them, a reference and the census it is matched
 *       against being names and nothing else; half its cases pin what the resolution refuses to
 *       decide (an overloaded name is rows and an arity, never a pick) and two pin what survives it,
 *       a reference the census matched nothing for still being a reference;
 *       {@code no.sikt.graphitron.model.intent.FieldRoutineMethodTest} binds
 *       {@code intent_field_routine_method}, the generated call surface a {@code @routine}
 *       application resolves to, in the same module and on the same terms: a spelling and a census
 *       are names, so a fixture states both sides and half its cases pin the three causes of absence
 *       apart (a name matching no catalog object, one matching a stored table rather than a
 *       callable, and a callable the generated model exposes no call surface for);
 *       {@code no.sikt.graphitron.model.intent.AccessorHopTest} binds the five relations an
 *       accessor hop is built from ({@code intent_delivery_container}, {@code intent_declared_type_ref},
 *       {@code intent_declared_type_element}, {@code intent_class_member_element} and
 *       {@code intent_field_accessor_hop}) to a census stated as rows in the module whose DDL
 *       declares them, one accessor per delivery shape beside the arrangements no scan of compiled
 *       fixtures offers side by side (one class name declared on two classpath entries, one slot
 *       name offered by two classes, a nesting one position deeper than the descent goes), with the
 *       two directions in which the hop differs from the reflective walk pinned as pins rather than
 *       expectations;
 *       {@code no.sikt.graphitron.rewrite.derive.TypeBackingClassTest} binds
 *       {@code intent_type_backing_class}, the closure over those hops, to captured SDL over a
 *       hand-built census, which is the only way its rows can be read at all: the closure runs over
 *       a cyclic type graph, so the relation is materialized and a writer's run is what puts rows
 *       there, its cases pinning how far the frontier goes, that a cycle terminates, the one
 *       condition that stops a hop, and each population the closure deliberately does not reach;
 *       with {@code no.sikt.graphitron.model.intent.TypeBackingSeedTest} carrying the two axes that
 *       ground it and {@code no.sikt.graphitron.model.intent.TypeBackingTest} carrying what
 *       coalescing those rows with the table-bound population makes of them, both against a store
 *       seeded row by row in the module whose DDL declares them;
 *       {@code no.sikt.graphitron.model.intent.ProducerCardinalityTest} binds
 *       {@code intent_producer_cardinality_conflict}, where a field and its producer disagree
 *       about how many, to seeded rows in the module whose DDL declares it, both sides of the
 *       comparison being a cardinality a row can state; every case pairs a disagreement with an
 *       agreement over the same producer so the detection's silence is asserted and not assumed;
 *       with
 *       {@code no.sikt.graphitron.rewrite.derive.TypeBackingShadowTest} beside it running the
 *       differential against {@code walk_type_backing_class} on both axes, over public fixture
 *       classes both sides can see;
 *       {@code no.sikt.graphitron.rewrite.capture.ClassificationDomainTest} states
 *       {@code intent_type_domain}'s membership as hand-written expectations over a real capture,
 *       whole sets where the schema is small enough to write one out, with the two node-seed
 *       widenings and the assembly cliff each carrying their own case, so the relation is pinned to
 *       what its seed rule promises rather than to what the retiring walk reached;
 *       {@code no.sikt.graphitron.rewrite.derive.DemandShadowTest} binds the
 *       resolved reductions to the walked registries via {@code ClaimDomain} over a real capture
 *       of every corpus example, residues named and disagreement directions pinned, with
 *       {@code no.sikt.graphitron.model.intent.DemandRuleTest} carrying the other half of that
 *       reason: each rule arm, each position mask, the machinery arm's structural recognition and
 *       both reductions' precedence, pinned against a census stated row by row in the module
 *       whose DDL declares them;
 *       {@code no.sikt.graphitron.rewrite.derive.InputOccurrenceShadowTest} binds the input
 *       occurrence-path pair to a structural reference enumeration and to the walk's use-keyed
 *       cascade verdicts, and pins the authored override flag reaching the view at all, with
 *       {@code no.sikt.graphitron.model.intent.InputOccurrenceOverrideTest} carrying what the view
 *       makes of it: the three enclosing sites as their own arms, the boundary a step draws around
 *       what is below it, and the order the nearest witness is picked in;
 *       {@code no.sikt.graphitron.rewrite.derive.SeparateFetchTest} binds
 *       {@code intent_field_separate_fetch}'s two marker arms to the walk's own gathered delivery
 *       relation over a real capture, and pins the record-handed arm reaching a closure a
 *       derivation writer materialized from a captured census, with
 *       {@code no.sikt.graphitron.model.intent.SeparateFetchRuleTest} carrying what the view makes
 *       of those rows: each of the five rules as its own arm, the two boundaries the record-handed
 *       one draws, and the arity a coordinate several rules reach carries against the single row a
 *       rule reaching one coordinate twice still answers with;
 *       {@code no.sikt.graphitron.rewrite.derive.AuthoredClaimConflictsTest} binds the
 *       {@code intent_authored_claim_conflict} view to hand-written per-fixture expectations
 *       the view does not produce, the re-aimed anchor left standing after the corpus shadow
 *       proved the cutover and retired with the Java reduction it shadowed, and the two claim
 *       relations it reads are pinned given rows by
 *       {@code no.sikt.graphitron.model.intent.AuthoredClaimTest}). Later derivation
 *       strata land as registrations here, not as exemptions.</li>
 *   <li>{@link Arm#ORACLE} for relations an oracle writer owns, at the oracle's own cadence
 *       (javac writes after capture; the legacy classification walk's reach writes inside the
 *       capture-and-detect pass), where no independent second walk can re-derive the oracle's
 *       verdict without re-running the oracle. Two anchors, both non-vacuous: a two-graph
 *       lifecycle anchor (seeded rows, so "cleared" is distinguishable from "never written",
 *       under two graphs, so "cleared what it owns" is distinguishable from "cleared
 *       everything") and a write-read content anchor (the same round reduced two ways, at the
 *       oracle's cadence). The one thing genuinely unpinned is the oracle's verdict itself.</li>
 * </ul>
 *
 * <p>These tests retire as consumers migrate off {@code GraphitronSchema} piece by piece; they pin
 * a shadow copy, and a shadow with a reader does not need one.
 */
@PipelineTier
class FactCaptureAgreementTest {

    /** How a relation's contents are pinned to the model it shadows. */
    private enum Arm { CONTAINMENT, EQUALITY, DERIVED, ORACLE }

    private static final Map<String, Arm> REGISTRATIONS = registrations();

    private static Map<String, Arm> registrations() {
        var registrations = new LinkedHashMap<String, Arm>();
        for (String relation : List.of(
            // The coordinate anchors ride their attribute siblings' arm: one walk claims a
            // coordinate and writes both rows, so the containment claim below transfers, and
            // coordinatesAgreeWithTheirAttributeRelations makes the transfer a fact.
            "graphql_type_coordinate", "graphql_field_coordinate",
            "graphql_argument_coordinate", "graphql_enum_value_coordinate",
            "graphql_type", "graphql_type_declaration", "graphql_field", "graphql_argument",
            "graphql_enum_value", "graphql_union_member", "graphql_implements",
            "graphql_root_operation", "graphql_duplicate_declaration", "graphql_directive",
            "graphql_directive_location", "graphql_directive_argument", "graphql_schema_directive",
            "graphql_schema_directive_arg", "graphql_type_directive", "graphql_type_directive_arg",
            "graphql_field_directive", "graphql_field_directive_arg", "graphql_argument_directive",
            "graphql_argument_directive_arg", "graphql_enum_value_directive",
            "graphql_enum_value_directive_arg", "graphitron_argument_path_segment",
            "graphitron_table", "graphitron_field_binding",
            "graphitron_argument_binding", "graphitron_enum_value_binding", "graphitron_scalar_type",
            "graphitron_enum", "graphitron_field_condition", "graphitron_field_condition_context_arg",
            "graphitron_field_condition_arg_mapping_pair", "graphitron_argument_condition",
            "graphitron_argument_condition_context_arg", "graphitron_argument_condition_arg_mapping_pair",
            "graphitron_field_reference", "graphitron_field_reference_step",
            "graphitron_field_reference_step_arg_mapping_pair", "graphitron_argument_reference",
            "graphitron_argument_reference_step", "graphitron_argument_reference_step_arg_mapping_pair",
            "graphitron_reference_for", "graphitron_reference_for_step",
            "graphitron_reference_for_step_arg_mapping_pair", "graphitron_service",
            "graphitron_service_context_arg", "graphitron_service_arg_mapping_pair",
            "graphitron_service_arg_mapping_sigil",
            "graphitron_external_field", "graphitron_source_row", "graphitron_connection", "graphitron_facet",
            "graphitron_order_by", "graphitron_order", "graphitron_order_field", "graphitron_index",
            "graphitron_default_order", "graphitron_default_order_field", "graphitron_mutation",
            "graphitron_error", "graphitron_error_handler", "graphitron_node", "graphitron_node_key_column",
            "graphitron_field_node_id", "graphitron_argument_node_id", "graphitron_argument_lookup_key",
            "graphitron_field_lookup_key", "graphitron_split_query", "graphitron_tenant_fan_out",
            "graphitron_pivot", "graphitron_routine", "graphitron_routine_arg_mapping_pair",
            "graphitron_routine_column_mapping_pair", "graphitron_discriminate", "graphitron_discriminator",
            "graphitron_federation_key", "graphitron_federation_key_field",
            "graphitron_federation_key_field_segment", "graphitron_link",
            "graphitron_link_import", "graphitron_multitable_reference", "graphitron_record",
            "graphitron_undecoded_argument", "graphitron_type_declaration_synthesis",
            "graphitron_field_synthesis")) {
            registrations.put(relation, Arm.CONTAINMENT);
        }
        for (String relation : List.of(
            "sql_schema", "sql_table", "sql_column", "sql_constraint", "sql_constraint_column",
            "sql_primary_key", "sql_referential_constraint", "sql_index",
            "sql_index_column", "sql_routine", "sql_routine_parameter",
            "sql_node_metadata", "sql_node_key_column",
            "jvm_class", "jvm_class_supertype", "jvm_method",
            "jvm_method_return_type_ref", "jvm_method_parameter",
            "jvm_method_parameter_type_ref", "jvm_record_component",
            "jvm_record_component_type_ref",
            "jvm_scalar_type_field", "store_source", "store_stamp",
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
        registrations.put("graphql_directive_site", Arm.DERIVED);
        registrations.put("intent_authored_field_claim", Arm.DERIVED);
        registrations.put("intent_authored_type_claim", Arm.DERIVED);
        registrations.put("intent_bound_table", Arm.DERIVED);
        registrations.put("intent_spelled_table", Arm.DERIVED);
        registrations.put("intent_field_reference_step_hop", Arm.DERIVED);
        registrations.put("intent_name_matched_key_pair", Arm.DERIVED);
        registrations.put("intent_node_metadata_defect", Arm.DERIVED);
        registrations.put("intent_inferred_node_type", Arm.DERIVED);
        registrations.put("intent_node_type", Arm.DERIVED);
        registrations.put("intent_synthesized_federation_key", Arm.DERIVED);
        registrations.put("intent_federation_key", Arm.DERIVED);
        registrations.put("intent_field_reference_step_target", Arm.DERIVED);
        registrations.put("intent_argument_reference_step_hop", Arm.DERIVED);
        registrations.put("intent_argument_reference_step_target", Arm.DERIVED);
        registrations.put("intent_field_chain_terminus", Arm.DERIVED);
        registrations.put("intent_field_reference_discovery", Arm.DERIVED);
        registrations.put("intent_routine_return_binding", Arm.DERIVED);
        registrations.put("intent_resolved_type_binding", Arm.DERIVED);
        registrations.put("intent_resolved_node_key_column", Arm.DERIVED);
        registrations.put("intent_resolved_node_type_id", Arm.DERIVED);
        registrations.put("intent_argument_scope_table", Arm.DERIVED);
        registrations.put("intent_node_id_instruction", Arm.DERIVED);
        registrations.put("intent_node_id_decode_endpoint", Arm.DERIVED);
        registrations.put("intent_node_id_decode_hop", Arm.DERIVED);
        registrations.put("intent_node_id_decode_hop_column", Arm.DERIVED);
        registrations.put("intent_node_id_decode_column", Arm.DERIVED);
        registrations.put("intent_foreign_key_column_pair", Arm.DERIVED);
        registrations.put("intent_node_id_encode", Arm.DERIVED);
        registrations.put("intent_argmapping_pair", Arm.DERIVED);
        registrations.put("intent_argmapping_pair_live", Arm.DERIVED);
        registrations.put("intent_spelled_table_live", Arm.DERIVED);
        registrations.put("intent_argmapping_segment_binding", Arm.DERIVED);
        registrations.put("intent_argmapping_binding_leaf", Arm.DERIVED);
        registrations.put("intent_argmapping_bound_parameter_type", Arm.DERIVED);
        registrations.put("intent_argmapping_key_column_candidate", Arm.DERIVED);
        registrations.put("intent_resolved_node_key_projection", Arm.DERIVED);
        registrations.put("intent_argmapping_projection_defect", Arm.DERIVED);
        registrations.put("intent_carrier_data_field", Arm.DERIVED);
        registrations.put("intent_carrier_routine_hop", Arm.DERIVED);
        registrations.put("intent_column_match_claim", Arm.DERIVED);
        registrations.put("intent_field_column_scope", Arm.DERIVED);
        registrations.put("intent_field_column_table", Arm.DERIVED);
        registrations.put("intent_field_separate_fetch", Arm.DERIVED);
        registrations.put("intent_class_member_slot", Arm.DERIVED);
        registrations.put("intent_class_assignable", Arm.DERIVED);
        registrations.put("intent_field_producer_reference", Arm.DERIVED);
        registrations.put("intent_field_producer_method", Arm.DERIVED);
        registrations.put("intent_field_routine_method", Arm.DERIVED);
        registrations.put("intent_delivery_container", Arm.DERIVED);
        registrations.put("intent_declared_type_ref", Arm.DERIVED);
        registrations.put("intent_declared_type_element", Arm.DERIVED);
        registrations.put("intent_class_member_element", Arm.DERIVED);
        registrations.put("intent_field_accessor_hop", Arm.DERIVED);
        registrations.put("intent_type_backing_seed", Arm.DERIVED);
        registrations.put("intent_type_backing_class", Arm.DERIVED);
        registrations.put("intent_type_backing", Arm.DERIVED);
        registrations.put("intent_type_backing_conflict", Arm.DERIVED);
        registrations.put("intent_producer_cardinality_conflict", Arm.DERIVED);
        registrations.put("intent_resolved_field_claim", Arm.DERIVED);
        registrations.put("intent_type_domain", Arm.DERIVED);
        registrations.put("intent_field_demand_rule", Arm.DERIVED);
        registrations.put("intent_field_exemption_rule", Arm.DERIVED);
        registrations.put("intent_type_demand", Arm.DERIVED);
        registrations.put("intent_type_exemption", Arm.DERIVED);
        registrations.put("intent_resolved_field_demand", Arm.DERIVED);
        registrations.put("intent_resolved_type_demand", Arm.DERIVED);
        registrations.put("intent_input_occurrence_path", Arm.DERIVED);
        registrations.put("intent_input_occurrence_path_step", Arm.DERIVED);
        registrations.put("intent_input_occurrence_override", Arm.DERIVED);
        registrations.put("intent_authored_claim_conflict", Arm.DERIVED);
        // The diagnostics union view is a pure re-projection of its five arms, so its agreement
        // is vacuous by construction on the graphql_directive_site precedent; the arm-specific
        // derived columns are pinned by DiagnosticFactsTest against their Java spellings.
        registrations.put("diagnostic", Arm.DERIVED);
        // The schema self-description stratum: views over row values authored in the DDL itself,
        // so capture never writes them and agreement with the walk is vacuous by construction.
        // Their anchors are the roster gates in FactSchemaGateTest, which close the family rows
        // against the observed relations in both directions on every build.
        registrations.put("meta_family", Arm.DERIVED);
        registrations.put("meta_prefixless_relation", Arm.DERIVED);
        registrations.put("meta_relation_family", Arm.DERIVED);
        registrations.put("meta_materialize", Arm.DERIVED);
        registrations.put("meta_materialize_dependency", Arm.DERIVED);
        registrations.put("javac_diagnostic", Arm.ORACLE);
        registrations.put("walk_type_backing_class", Arm.ORACLE);
        registrations.put("rejection_validation_error", Arm.ORACLE);
        registrations.put("rejection_validation_error_directive", Arm.ORACLE);
        registrations.put("lint_finding", Arm.ORACLE);
        registrations.put("lint_finding_fix", Arm.ORACLE);
        registrations.put("lint_finding_fix_edit", Arm.ORACLE);
        registrations.put("build_warning_no_rule", Arm.ORACLE);
        registrations.put("graphql_syntax_error", Arm.ORACLE);
        registrations.put("graphql_schema_error", Arm.ORACLE);
        return Map.copyOf(registrations);
    }

    private static final String FIXTURE = """
        directive @audit(note: String) repeatable on OBJECT | FIELD_DEFINITION

        type Query {
          films(title: String): [Film!]!
          film(id: ID!): Film
        }

        type Film @table(name: "film") @audit(note: "one") @audit(note: "two") {
          id: ID! @field(name: "film_id")
          title: String
          rating: Rating
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") {
          name: String @field(name: "name")
        }

        enum Rating { G PG @field(name: "PG") }

        input FilmFilter { title: String = "any" }
        """;

    /** A directive-driven carrier and a plain list, so the expansion has something to skip. */
    private static final String CONNECTION_FIXTURE = """
        type Query {
          films: [Film!]! @asConnection
          languages: [Language!]!
        }

        type Film @table(name: "film") {
          id: ID! @field(name: "film_id")
          title: String
        }

        type Language @table(name: "language") {
          name: String @field(name: "name")
        }
        """;

    /**
     * A federated slice with one of each outcome: an authored key alternative that the derivation
     * still fires beside, a node with no key at all, and an authored id key that stands the
     * derivation down. The {@code @link} is the author's; the {@code @key} declaration arrives
     * through the pipeline's own federation import, which is the point of running this fixture
     * through the pipeline.
     */
    private static final String FEDERATED_FIXTURE = """
        extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

        type Query { film: Film, language: Language, actor: Actor }

        interface Node { id: ID! }

        type Film implements Node @node @key(fields: "title") {
          id: ID!
          title: String
        }

        type Language implements Node @node {
          id: ID!
          name: String
        }

        type Actor implements Node @node @key(fields: "id", resolvable: false) {
          id: ID!
          name: String
        }
        """;

    /**
     * One carrier per sampled payload kind, over the sakila catalog the test context resolves
     * against: a scalar reference ({@code @table(name:)}, {@code @node(typeId:)}), a list-valued
     * argument ({@code @node(keyColumns:)}), a flattened code reference ({@code @service}), and an
     * author-spelled enum literal ({@code @mutation(typeName:)}).
     */
    private static final String SEMANTIC_FIXTURE = """
        interface Node { id: ID! }

        type Query {
          films: [Film!]!
          actors: [Actor!]!
          filmActors: [FilmActor!]!
        }

        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
          deleteFilm(in: FilmKeyInput!): ID @mutation(typeName: DELETE, table: "film")
        }

        type Film @table(name: "film") {
          title: String
          languages: [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Actor implements Node @table(name: "actor") @node(typeId: "ACT", keyColumns: ["actor_id"]) {
          id: ID!
          actorId: Int @field(name: "actor_id")
        }

        type FilmActor implements Node @table(name: "film_actor") @node(typeId: "FA", keyColumns: ["actor_id", "film_id"]) {
          id: ID!
        }

        type Language @table(name: "language") {
          name: String
          films: [Film!]! @service(
            service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"})
        }

        input FilmInput { title: String }
        input FilmKeyInput { filmId: Int! @field(name: "film_id") }
        """;

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
     * The split the coordinate family introduced, stated as the invariant it rests on: an anchor
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
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var dsl = store.dsl();
            assertThat(dsl.select(GRAPHQL_TYPE_COORDINATE.TYPE_NAME).from(GRAPHQL_TYPE_COORDINATE).fetch())
                .as("type anchors against type attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE).fetch());
            assertThat(dsl.select(GRAPHQL_FIELD_COORDINATE.TYPE_NAME, GRAPHQL_FIELD_COORDINATE.FIELD_NAME)
                .from(GRAPHQL_FIELD_COORDINATE).fetch())
                .as("field anchors against field attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME).from(GRAPHQL_FIELD).fetch());
            assertThat(dsl.select(GRAPHQL_ARGUMENT_COORDINATE.TYPE_NAME,
                    GRAPHQL_ARGUMENT_COORDINATE.FIELD_NAME, GRAPHQL_ARGUMENT_COORDINATE.ARGUMENT_NAME)
                .from(GRAPHQL_ARGUMENT_COORDINATE).fetch())
                .as("argument anchors against argument attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_ARGUMENT.TYPE_NAME, GRAPHQL_ARGUMENT.FIELD_NAME,
                        GRAPHQL_ARGUMENT.ARGUMENT_NAME).from(GRAPHQL_ARGUMENT).fetch());
            assertThat(dsl.select(GRAPHQL_ENUM_VALUE_COORDINATE.TYPE_NAME,
                    GRAPHQL_ENUM_VALUE_COORDINATE.VALUE_NAME).from(GRAPHQL_ENUM_VALUE_COORDINATE).fetch())
                .as("enum value anchors against enum value attributes")
                .containsExactlyInAnyOrderElementsOf(
                    dsl.select(GRAPHQL_ENUM_VALUE.TYPE_NAME, GRAPHQL_ENUM_VALUE.VALUE_NAME)
                        .from(GRAPHQL_ENUM_VALUE).fetch());
        }
    }

    @Test
    @DisplayName("the type census contains the classified model")
    void typeCensusContainsTheModel(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var captured = Set.copyOf(store.dsl()
                .select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE).fetch(0, String.class));
            var classified = GraphitronSchemaBuilder.build(store.registry(), testContext()).types().keySet();
            // Containment, not equality: capture is total while the classified model is pruned to
            // what the roots reach, so the store legitimately holds types the model dropped.
            assertThat(captured).containsAll(classified);
        }
    }

    /**
     * Federation's key synthesis has two live implementations: the registry rewrite the legacy
     * pipeline's assembly runs, and the derivation over the captured facts. They answer at different
     * stages over different representations, and neither can call the other without inverting the
     * pipeline's ordering, so this anchor is what keeps them from drifting. It retires with the
     * rewrite's last consumer.
     *
     * <p>Both sides come out of one pipeline run rather than out of two registries the test
     * assembles: the expectation is the registry the rewrite mutated, and the comparison is
     * {@code intent_federation_key} over the store filled from the handle production hands capture.
     * One relation rather than a union the test assembles, which is what makes the comparison a
     * reading of the schema's own composition instead of a second spelling of it.
     *
     * <p>The reading position is part of what this pins, and it matters more after the move than
     * before: a synthesized key transcribed as an authored one now lands wrong in stratum one
     * outright. What catches it is the derived membership. A capture that read the rewritten registry
     * would land Film's and Language's {@code id} keys in {@code graphitron_federation_key} as
     * authored rows; the derivation's no-authored-id-key condition would then decline on both, so the
     * expected synthesized membership comes up empty while the first assertion still agrees. The
     * second assertion is therefore what keeps the agreement from being reached by capture reading
     * the wrong registry.
     */
    @Test
    @DisplayName("derived federation keys agree with the registry rewrite's, off one pipeline run")
    void federationKeySynthesisAgreesWithTheRewrite(@TempDir Path tmp) {
        try (var store = CapturedStore.ofPipeline(tmp, FEDERATED_FIXTURE)) {
            var composed = new LinkedHashSet<String>();
            store.dsl()
                .select(INTENT_FEDERATION_KEY.TYPE_NAME, INTENT_FEDERATION_KEY.FIELDS_SDL)
                .from(INTENT_FEDERATION_KEY)
                .fetch()
                .forEach(row -> composed.add(row.value1() + "|" + row.value2()));

            var expected = new LinkedHashSet<String>();
            for (TypeDefinition<?> definition : store.attributed().registry().types().values()) {
                if (!(definition instanceof ObjectTypeDefinition object)) {
                    continue;
                }
                for (Directive key : object.getDirectives("key")) {
                    var fields = key.getArgument("fields");
                    if (fields != null && fields.getValue() instanceof StringValue value) {
                        expected.add(object.getName() + "|" + value.getValue());
                    }
                }
            }
            assertThat(expected).as("the fixture federates nodes, so this pins something").isNotEmpty();
            assertThat(composed).isEqualTo(expected);

            // Film carries an alternative and Language carries nothing, so the derivation fires on
            // both; Actor's authored id key stands it down on both implementations. A capture that
            // read the post-rewrite registry would leave this set empty, the authored rows it landed
            // declining the derivation on every type the rewrite had already keyed.
            assertThat(store.dsl()
                .select(INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME)
                .from(INTENT_SYNTHESIZED_FEDERATION_KEY)
                .fetch(INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME))
                .containsExactlyInAnyOrder("Film", "Language");
        }
    }

    /**
     * The walk's connection expansion against the classified model's record of the same synthesis.
     * Scoped to the arms capture mints: the facet arms are an aggregate over the whole schema and
     * belong to a derived stratum, so their absence here is the design rather than a gap, and the
     * assertion says so by naming the arms it compares.
     */
    @Test
    @DisplayName("connection synthesis provenance agrees with the classified model's record")
    void synthesisProvenanceAgreesWithConnectionSynthesis(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, CONNECTION_FIXTURE)) {
            var relation = GraphitronSchemaBuilder.build(store.registry(), testContext())
                .connectionSynthesis();

            var expected = new LinkedHashSet<String>();
            for (var row : relation.rows().values()) {
                if (!(row instanceof ConnectionSynthesis.DirectiveDriven)) {
                    // A structural carrier references a declared Connection and mints nothing, so
                    // capture rewrites nothing and has no provenance to show.
                    continue;
                }
                for (var minted : row.mintedNames()) {
                    if (minted.declaredArm() == GraphitronType.ConnectionType.class
                        || minted.declaredArm() == GraphitronType.EdgeType.class) {
                        expected.add(minted.name() + "<-" + row.parentTypeName() + "." + row.fieldName());
                    }
                }
            }
            assertThat(expected).as("the fixture carries @asConnection, so this pins something")
                .isNotEmpty();

            var captured = new LinkedHashSet<String>();
            store.dsl()
                .select(GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.TYPE_NAME,
                    GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.CARRIER_TYPE_NAME,
                    GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.CARRIER_FIELD_NAME)
                .from(GRAPHITRON_TYPE_DECLARATION_SYNTHESIS)
                .where(GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.TYPE_NAME.ne("PageInfo"))
                .fetch()
                .forEach(row -> captured.add(row.value1() + "<-" + row.value2() + "." + row.value3()));
            assertThat(captured).isEqualTo(expected);

            // PageInfo is schema-grain on the model and per-carrier in the store, so the agreement
            // is that both know it was minted and the store's site count is the carrier count.
            boolean modelMintedPageInfo = relation.sharedMinted().stream()
                .anyMatch(minted -> minted.declaredArm() == GraphitronType.PageInfoType.class);
            long carriers = relation.rows().values().stream()
                .filter(ConnectionSynthesis.DirectiveDriven.class::isInstance).count();
            assertThat(store.dsl().fetchCount(GRAPHITRON_TYPE_DECLARATION_SYNTHESIS,
                GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.TYPE_NAME.eq("PageInfo")))
                .isEqualTo(modelMintedPageInfo ? (int) carriers : 0);
        }
    }

    /**
     * The other half of the same expansion: the carrier's own field. The model records that the
     * return type was rewritten; the store keeps the expression the field was written with, and the
     * two have to be talking about the same set of carriers.
     */
    @Test
    @DisplayName("rewritten carriers agree with the model's directive-driven rows")
    void rewrittenCarriersAgreeWithTheModel(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, CONNECTION_FIXTURE)) {
            var expected = GraphitronSchemaBuilder.build(store.registry(), testContext())
                .connectionSynthesis().rows().values().stream()
                .filter(ConnectionSynthesis.DirectiveDriven.class::isInstance)
                .map(row -> row.parentTypeName() + "." + row.fieldName())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            var captured = new LinkedHashSet<>(store.dsl()
                .select(GRAPHITRON_FIELD_SYNTHESIS.TYPE_NAME.concat(".")
                    .concat(GRAPHITRON_FIELD_SYNTHESIS.FIELD_NAME))
                .from(GRAPHITRON_FIELD_SYNTHESIS)
                .fetch(0, String.class));
            assertThat(captured).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("per-coordinate applied-directive counts match the SDL")
    void appliedDirectiveCountsMatchTheSdl(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var captured = new LinkedHashMap<String, Integer>();
            store.dsl()
                .select(GRAPHQL_DIRECTIVE_SITE.SITE_KIND, GRAPHQL_DIRECTIVE_SITE.TYPE_NAME,
                    GRAPHQL_DIRECTIVE_SITE.MEMBER_NAME, GRAPHQL_DIRECTIVE_SITE.ARGUMENT_NAME,
                    GRAPHQL_DIRECTIVE_SITE.DIRECTIVE_NAME)
                .from(GRAPHQL_DIRECTIVE_SITE)
                .fetch()
                .forEach(row -> captured.merge(
                    String.join("|", String.valueOf(row.value1()), String.valueOf(row.value2()),
                        String.valueOf(row.value3()), String.valueOf(row.value4()),
                        String.valueOf(row.value5())),
                    1, Integer::sum));
            assertThat(captured).as("the fixture applies foreign directives, so this pins something")
                .isNotEmpty();
            assertThat(captured).containsExactlyInAnyOrderEntriesOf(sdlApplicationCounts(store));
        }
    }

    /**
     * The decode's payload against the model's resolved value, sampled by payload kind.
     *
     * <p>A scalar reference: the type site's {@code @table(name:)} and the {@code @node(typeId:)}
     * beside it. The comparison is conditional in one direction and containment in the other,
     * both for stated reasons. Conditional, because capture stores what the author wrote and the
     * model stores what resolution made of it: where the argument is omitted the store holds NULL
     * and the model holds the fallback, and a fallback is a derivation with nothing to agree with.
     * Containment, because the model is reachability-pruned. Case is compared loosely: the store
     * keeps the author's spelling and the model's value came back through catalog resolution.
     */
    @Test
    @DisplayName("type-site scalar payloads agree with the model's resolved values")
    void typeSiteScalarPayloadsAgreeWithTheModel(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SEMANTIC_FIXTURE)) {
            var schema = GraphitronSchemaBuilder.build(store.registry(), testContext());

            var tables = new LinkedHashMap<String, String>();
            store.dsl().select(GRAPHITRON_TABLE.TYPE_NAME, GRAPHITRON_TABLE.TABLE_REF)
                .from(GRAPHITRON_TABLE).where(GRAPHITRON_TABLE.TABLE_REF.isNotNull()).fetch()
                .forEach(row -> tables.put(row.value1(), row.value2()));
            assertThat(tables).as("the fixture writes @table(name:), so this pins something").isNotEmpty();

            var typeIds = new LinkedHashMap<String, String>();
            store.dsl().select(GRAPHITRON_NODE.TYPE_NAME, GRAPHITRON_NODE.TYPE_ID)
                .from(GRAPHITRON_NODE).where(GRAPHITRON_NODE.TYPE_ID.isNotNull()).fetch()
                .forEach(row -> typeIds.put(row.value1(), row.value2()));
            assertThat(typeIds).as("the fixture writes @node(typeId:), so this pins something").isNotEmpty();

            var compared = new LinkedHashSet<String>();
            for (var type : schema.types().values()) {
                if (type instanceof GraphitronType.TableBackedType backed) {
                    String authored = tables.get(type.name());
                    if (authored != null) {
                        assertThat(backed.table().tableName())
                            .as("@table(name:) on %s", type.name()).isEqualToIgnoringCase(authored);
                        compared.add(type.name() + ".@table");
                    }
                }
                if (type instanceof GraphitronType.NodeType node) {
                    String authored = typeIds.get(type.name());
                    if (authored != null) {
                        assertThat(node.typeId()).as("@node(typeId:) on %s", type.name()).isEqualTo(authored);
                        compared.add(type.name() + ".@node");
                    }
                }
            }
            assertThat(compared).as("the model reaches the fixture's carriers, so the loop compared something")
                .isNotEmpty();
        }
    }

    /**
     * A list-valued argument decoded to positioned child rows, against the list the model resolved
     * from it: {@code @node(keyColumns:)}. Position carries the meaning here, so the comparison is
     * ordered, which is what separates this kind from the scalar one.
     */
    @Test
    @DisplayName("ordered child rows agree with the model's resolved list, in order")
    void orderedChildRowsAgreeWithTheModel(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SEMANTIC_FIXTURE)) {
            var schema = GraphitronSchemaBuilder.build(store.registry(), testContext());

            var captured = new LinkedHashMap<String, List<String>>();
            store.dsl()
                .select(GRAPHITRON_NODE_KEY_COLUMN.TYPE_NAME, GRAPHITRON_NODE_KEY_COLUMN.COLUMN_REF)
                .from(GRAPHITRON_NODE_KEY_COLUMN)
                .orderBy(GRAPHITRON_NODE_KEY_COLUMN.TYPE_NAME, GRAPHITRON_NODE_KEY_COLUMN.POSITION)
                .fetch()
                .forEach(row -> captured.computeIfAbsent(row.value1(), ignored -> new ArrayList<>())
                    .add(row.value2()));
            assertThat(captured).as("the fixture writes @node(keyColumns:), so this pins something")
                .isNotEmpty();

            int compared = 0;
            for (var type : schema.types().values()) {
                if (!(type instanceof GraphitronType.NodeType node)) {
                    continue;
                }
                List<String> authored = captured.get(type.name());
                if (authored == null) {
                    continue;  // omitted: the model's list came from the catalog's primary key
                }
                assertThat(node.nodeKeyColumns().stream().map(column -> column.sqlName().toLowerCase(Locale.ROOT)))
                    .as("@node(keyColumns:) on %s", type.name())
                    .containsExactlyElementsOf(authored.stream().map(name -> name.toLowerCase(Locale.ROOT)).toList());
                compared++;
            }
            assertThat(compared).as("the model reaches a keyColumns carrier").isPositive();
        }
    }

    /**
     * The flattened {@code ExternalCodeReference}: {@code @service}'s object-valued argument spread
     * across columns, against the {@link MethodBackedField} the model classified the same
     * coordinate into. One decode helper writes every relation of this kind, so a representative
     * exercises the flattening the rest share.
     */
    @Test
    @DisplayName("flattened code references agree with the model's method refs")
    void flattenedCodeReferencesAgreeWithTheModel(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SEMANTIC_FIXTURE)) {
            var schema = GraphitronSchemaBuilder.build(store.registry(), testContext());

            var rows = store.dsl()
                .select(GRAPHITRON_SERVICE.TYPE_NAME, GRAPHITRON_SERVICE.FIELD_NAME,
                    GRAPHITRON_SERVICE.CLASS_NAME, GRAPHITRON_SERVICE.METHOD)
                .from(GRAPHITRON_SERVICE).fetch();
            assertThat(rows).as("the fixture applies @service, so this pins something").isNotEmpty();

            int compared = 0;
            for (var row : rows) {
                var field = schema.field(row.value1(), row.value2());
                if (field == null) {
                    continue;  // pruned out of the model, so there is nothing to disagree with
                }
                assertThat(field)
                    .as("the model classifies %s.%s as method-backed", row.value1(), row.value2())
                    .isInstanceOf(MethodBackedField.class);
                var method = ((MethodBackedField) field).method();
                assertThat(method.className() + "#" + method.methodName())
                    .as("@service(service:) on %s.%s", row.value1(), row.value2())
                    .isEqualTo(row.value3() + "#" + row.value4());
                compared++;
            }
            assertThat(compared).as("the model reaches a @service carrier").isPositive();
        }
    }

    /**
     * The author-spelled enum literal, stored as an open column per the conventions, against the
     * arm the model lifted it into. The lift is total and injective, so the arm's own name is the
     * literal and the agreement can be read off it.
     */
    @Test
    @DisplayName("enum literals agree with the arm the model lifted them into")
    void enumLiteralsAgreeWithTheModelsLiftedArm(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SEMANTIC_FIXTURE)) {
            var schema = GraphitronSchemaBuilder.build(store.registry(), testContext());

            var rows = store.dsl()
                .select(GRAPHITRON_MUTATION.TYPE_NAME, GRAPHITRON_MUTATION.FIELD_NAME,
                    GRAPHITRON_MUTATION.OPERATION)
                .from(GRAPHITRON_MUTATION).fetch();
            assertThat(rows).as("the fixture applies @mutation, so this pins something").isNotEmpty();

            int compared = 0;
            for (var row : rows) {
                var lifted = schema.operationMembersOf(row.value1(), row.value2()).stream()
                    .filter(OperationMember.Write.Dml.class::isInstance)
                    .map(member -> member.getClass().getSimpleName().toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (lifted.isEmpty()) {
                    continue;  // pruned, or classified into a shape that mints no DML member
                }
                assertThat(lifted).as("@mutation(typeName:) on %s.%s", row.value1(), row.value2())
                    .containsExactly(row.value3());
                compared++;
            }
            assertThat(compared).as("the model reaches a @mutation carrier").isPositive();
        }
    }

    @Test
    @DisplayName("the table and column census equals the catalog's")
    void catalogCensusEqualsTheCatalog(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expectedTables = new LinkedHashMap<String, String>();
            int expectedColumns = 0;
            for (var entry : jooq.allTableEntries()) {
                var table = entry.table();
                expectedTables.put(table.getSchema().getName() + "." + table.getName(),
                    entry.javaFieldName());
                expectedColumns += table.fields().length;
            }

            var capturedTables = store.dsl()
                .select(SQL_TABLE.TABLE_SCHEMA.concat(".").concat(SQL_TABLE.TABLE_NAME),
                    SQL_TABLE.JOOQ_NAME)
                .from(SQL_TABLE)
                .fetch()
                .intoMap(r -> r.value1(), r -> r.value2());
            assertThat(capturedTables).isEqualTo(expectedTables);
            assertThat(store.dsl().fetchCount(SQL_COLUMN)).isEqualTo(expectedColumns);
        }
    }

    /**
     * The kind of table-like object each row describes. Pinned against {@code getTableType()} as
     * the oracle rather than against a hand-listed expectation, and asserted non-vacuous in both
     * directions that matter: a catalog whose every row came back {@code TABLE} would pass an
     * equality check while telling the store nothing, and {@code FUNCTION} is the value the
     * routine-backed read surface stands on.
     */
    @Test
    @DisplayName("every table carries the kind the catalog gives it")
    void tableTypesEqualTheCatalogs(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expected = new LinkedHashMap<String, String>();
            for (var entry : jooq.allTableEntries()) {
                var table = entry.table();
                expected.put(table.getSchema().getName() + "." + table.getName(),
                    table.getTableType().name());
            }
            assertThat(expected.values())
                .as("the test catalog declares both plain tables and table-valued functions")
                .contains("TABLE", "FUNCTION");

            var captured = store.dsl()
                .select(SQL_TABLE.TABLE_SCHEMA.concat(".").concat(SQL_TABLE.TABLE_NAME),
                    SQL_TABLE.TABLE_TYPE)
                .from(SQL_TABLE)
                .fetch()
                .intoMap(r -> r.value1(), r -> r.value2());
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * The store's verdict about a table's node-identity metadata against the live reflection
     * probe's, over the fixture catalog. This is the reader that makes the as-stated rows and the
     * defect derivation checkable on the day they land, rather than on the day a diagnostic arrives
     * to consume them: capture writes the forms and the entries, the view judges them, and the path
     * the view will eventually replace is still standing beside it to disagree.
     *
     * <p>The probe is called with the schema-qualified spelling, not the bare table name. Its
     * unqualified path answers absent for a name two schemas share exactly as it does for one no
     * catalog holds, so a bare name would let this gate agree by luck; the qualified path never
     * surfaces an ambiguity by construction. That the fixture catalog has no such collision today is
     * hand-kept rather than structural, the metadata map being keyed on the bare table name across
     * every codegen execution, which is the sort of property a gate should not rest on.
     *
     * <p>The store legitimately says more than the probe in one place, and the assertion states it
     * rather than papering over it: a class declaring only one of the two constants is a row here
     * with the other half's absent form, where the probe folds it into the same silence a class
     * declaring neither gets.
     */
    @Test
    @DisplayName("the stored node metadata's verdict equals the reflection probe's, per table")
    void nodeMetadataVerdictEqualsTheProbes(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var metadata = store.dsl()
                .select(SQL_NODE_METADATA.TABLE_SCHEMA, SQL_NODE_METADATA.TABLE_NAME,
                    SQL_NODE_METADATA.TYPE_ID_FORM, SQL_NODE_METADATA.TYPE_ID,
                    SQL_NODE_METADATA.KEY_COLUMNS_FORM)
                .from(SQL_NODE_METADATA)
                .fetch()
                .intoMap(r -> r.value1() + "." + r.value2());
            var defectCounts = store.dsl()
                .select(INTENT_NODE_METADATA_DEFECT.TABLE_SCHEMA,
                    INTENT_NODE_METADATA_DEFECT.TABLE_NAME, org.jooq.impl.DSL.count())
                .from(INTENT_NODE_METADATA_DEFECT)
                .groupBy(INTENT_NODE_METADATA_DEFECT.TABLE_SCHEMA,
                    INTENT_NODE_METADATA_DEFECT.TABLE_NAME)
                .fetch()
                .intoMap(r -> r.value1() + "." + r.value2(), r -> r.value3());
            var entryNames = new LinkedHashMap<String, List<String>>();
            store.dsl()
                .select(SQL_NODE_KEY_COLUMN.TABLE_SCHEMA, SQL_NODE_KEY_COLUMN.TABLE_NAME,
                    SQL_NODE_KEY_COLUMN.COLUMN_NAME)
                .from(SQL_NODE_KEY_COLUMN)
                .orderBy(SQL_NODE_KEY_COLUMN.POSITION)
                .forEach(r -> entryNames
                    .computeIfAbsent(r.value1() + "." + r.value2(), k -> new java.util.ArrayList<>())
                    .add(r.value3()));

            var storeVerdicts = new LinkedHashMap<String, String>();
            var probeVerdicts = new LinkedHashMap<String, String>();
            for (String qualified : store.dsl()
                    .select(SQL_TABLE.TABLE_SCHEMA.concat(".").concat(SQL_TABLE.TABLE_NAME))
                    .from(SQL_TABLE).fetch(0, String.class)) {
                var row = metadata.get(qualified);
                String stored;
                if (row == null
                    || "ABSENT".equals(row.get(SQL_NODE_METADATA.TYPE_ID_FORM))
                    || "ABSENT".equals(row.get(SQL_NODE_METADATA.KEY_COLUMNS_FORM))) {
                    stored = "ABSENT";
                } else if (defectCounts.getOrDefault(qualified, 0) > 0) {
                    stored = "MALFORMED";
                } else {
                    stored = "PRESENT";
                }
                storeVerdicts.put(qualified, stored);

                var present = jooq.nodeIdMetadata(qualified);
                probeVerdicts.put(qualified, present.isPresent() ? "PRESENT"
                    : jooq.nodeIdMetadataDiagnostic(qualified).isPresent() ? "MALFORMED" : "ABSENT");
                if (present.isPresent()) {
                    assertThat(row.get(SQL_NODE_METADATA.TYPE_ID))
                        .as("stored type id of " + qualified)
                        .isEqualTo(present.get().typeId());
                    assertThat(entryNames.getOrDefault(qualified, List.of()))
                        .as("stored key-column entries of " + qualified)
                        .containsExactlyElementsOf(present.get().keyColumns().stream()
                            .map(ColumnRef::sqlName).toList());
                }
            }

            assertThat(storeVerdicts.values())
                .as("the fixture catalog declares both metadata-bearing and stock tables")
                .contains("PRESENT", "ABSENT");
            assertThat(storeVerdicts).isEqualTo(probeVerdicts);
        }
    }

    /**
     * The routine census and the call surface its parameters belong to. The oracle is the generated
     * {@code Routines} class read straight off the codegen loader, method filter and all, rather
     * than the reader capture used: the parameter list is a fact about one overload out of three
     * that jOOQ generates per routine, so an assertion that took capture's word for which method it
     * described would pin nothing about the choice.
     *
     * <p>Two properties beyond the equality. The population is a strict subset of the tables, which
     * is what makes this a relation about callables rather than a second copy of the table census.
     * And the parameter names are the database's own, camelCased, not {@code arg0}: the store
     * records a reflected name, so the fixture module compiling its jOOQ output with
     * {@code -parameters} is load-bearing and silently losing that flag should fail here.
     */
    @Test
    @DisplayName("the routine census is the catalog's function-typed tables, call surface included")
    void routineCensusEqualsTheCatalogsFunctions(@TempDir Path tmp) throws Exception {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expectedRoutines = new LinkedHashMap<String, String>();
            var expectedParameters = new LinkedHashSet<String>();
            int tables = 0;
            for (var entry : jooq.allTableEntries()) {
                tables++;
                var table = entry.table();
                if (!table.getTableType().isFunction()) {
                    continue;
                }
                String qualified = table.getSchema().getName() + "." + table.getName();
                var routines = Class.forName(
                    table.getSchema().getClass().getPackageName() + ".Routines", true,
                    ctx.codegenLoader());
                var method = java.util.Arrays.stream(routines.getMethods())
                    .filter(m -> m.getReturnType() == table.getClass())
                    .filter(m -> java.util.Arrays.stream(m.getParameterTypes())
                        .noneMatch(org.jooq.Field.class::isAssignableFrom))
                    .findFirst()
                    .orElseThrow();
                expectedRoutines.put(qualified, routines.getName() + "#" + method.getName());
                var parameters = method.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    expectedParameters.add(qualified + "|" + i + "|" + parameters[i].getName()
                        + "|" + parameters[i].getType().getName());
                }
            }
            assertThat(expectedRoutines).as("the test catalog declares table-valued functions")
                .isNotEmpty();
            assertThat(expectedRoutines.size())
                .as("callables are a strict subset of the tables, not a second copy of them")
                .isLessThan(tables);
            assertThat(expectedParameters)
                .as("a routine with parameters, so the parameter relation pins something")
                .isNotEmpty();
            assertThat(expectedParameters)
                .as("reflected parameter names, which the fixture's -parameters flag is what makes real")
                .noneMatch(p -> p.contains("|arg"));

            var capturedRoutines = store.dsl()
                .select(SQL_ROUTINE.TABLE_SCHEMA.concat(".").concat(SQL_ROUTINE.ROUTINE_NAME),
                    SQL_ROUTINE.ROUTINES_CLASS_FQN.concat("#").concat(SQL_ROUTINE.ROUTINES_METHOD_NAME))
                .from(SQL_ROUTINE)
                .fetch()
                .intoMap(r -> r.value1(), r -> r.value2());
            assertThat(capturedRoutines).isEqualTo(expectedRoutines);

            var capturedParameters = new LinkedHashSet<String>();
            store.dsl()
                .select(SQL_ROUTINE_PARAMETER.TABLE_SCHEMA, SQL_ROUTINE_PARAMETER.ROUTINE_NAME,
                    SQL_ROUTINE_PARAMETER.POSITION, SQL_ROUTINE_PARAMETER.JOOQ_NAME,
                    SQL_ROUTINE_PARAMETER.BINDING_TYPE)
                .from(SQL_ROUTINE_PARAMETER)
                .fetch()
                .forEach(row -> capturedParameters.add(row.value1() + "." + row.value2() + "|"
                    + row.value3() + "|" + row.value4() + "|" + row.value5()));
            assertThat(capturedParameters).isEqualTo(expectedParameters);

            assertThat(store.dsl().fetchValues(store.dsl()
                    .selectDistinct(SQL_ROUTINE.ROUTINE_TYPE).from(SQL_ROUTINE)))
                .as("the table census reaches functions and nothing else")
                .containsExactly("FUNCTION");
        }
    }

    /**
     * The captured column order is the table definition's, which is what the column's own comment
     * promises. The reflective field walk the jOOQ name comes from is documented to return its
     * results in no particular order, so an ordinal taken from it would make the store's answer a
     * JVM implementation detail rather than a fact about the database.
     */
    @Test
    @DisplayName("column ordinals are the table definition's order")
    void columnOrdinalsFollowTheTableDefinition(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expected = new LinkedHashSet<String>();
            for (var entry : jooq.allTableEntries()) {
                var table = entry.table();
                String qualified = table.getSchema().getName() + "." + table.getName();
                var fields = table.fields();
                for (int i = 0; i < fields.length; i++) {
                    expected.add(qualified + "|" + i + "|" + fields[i].getName());
                }
            }
            assertThat(expected).as("the catalog has columns, so this pins something").isNotEmpty();

            var captured = new LinkedHashSet<String>();
            store.dsl()
                .select(SQL_COLUMN.TABLE_SCHEMA, SQL_COLUMN.TABLE_NAME, SQL_COLUMN.ORDINAL,
                    SQL_COLUMN.COLUMN_NAME)
                .from(SQL_COLUMN)
                .fetch()
                .forEach(row -> captured.add(
                    row.value1() + "." + row.value2() + "|" + row.value3() + "|" + row.value4()));
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * A column carries two types and the store must hold both. The SQL type is what the database
     * declares; the binding type is the Java class jOOQ maps it to, which only a live
     * {@link org.jooq.Field} on the codegen classpath can answer. Nothing downstream can recover it
     * from the SQL type, because the mapping is the generator's configured binding rather than a
     * rule, so this pins it against the {@code Field} itself as the oracle.
     */
    @Test
    @DisplayName("every captured column carries the Java type jOOQ binds it to")
    void columnBindingTypesEqualTheCatalogs(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expected = new LinkedHashMap<String, String>();
            for (var entry : jooq.allTableEntries()) {
                var table = entry.table();
                String qualified = table.getSchema().getName() + "." + table.getName();
                for (var field : table.fields()) {
                    expected.put(qualified + "|" + field.getName(), field.getType().getName());
                }
            }
            assertThat(expected).as("the catalog has columns, so this pins something").isNotEmpty();
            assertThat(expected.values())
                .as("a catalog whose every column bound to Object would make the assertion vacuous")
                .contains("java.lang.String");

            var captured = new LinkedHashMap<String, String>();
            store.dsl()
                .select(SQL_COLUMN.TABLE_SCHEMA, SQL_COLUMN.TABLE_NAME, SQL_COLUMN.COLUMN_NAME,
                    SQL_COLUMN.BINDING_TYPE)
                .from(SQL_COLUMN)
                .fetch()
                .forEach(row -> captured.put(
                    row.value1() + "." + row.value2() + "|" + row.value3(), row.value4()));
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * The generated class names, each at its own grain. The table class and the record class are
     * both per table; the {@code Keys} class is per schema, which is why it sits on
     * {@code sql_schema} instead of repeating down every table row. All three are join keys that
     * reach generated sources, and {@code jvm_class} cannot supply any of them, since that family
     * excludes the generated jOOQ package by design.
     *
     * <p>The table class and the record class are pinned as two maps rather than one, because the
     * point of the second column is that it is not a function of the first: a codegen configuration
     * decides what a record is called, so an assertion that derived one name from the other would
     * pass against a capture that had made the same guess.
     */
    @Test
    @DisplayName("generated class names are captured at the grain the concept has")
    void generatedClassNamesAreCapturedAtTheirOwnGrain(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expectedTables = new LinkedHashMap<String, String>();
            var expectedRecords = new LinkedHashMap<String, String>();
            var expectedSchemas = new LinkedHashMap<String, String>();
            var expectedTablesClasses = new LinkedHashMap<String, String>();
            for (var entry : jooq.allTableEntries()) {
                var table = entry.table();
                var schema = table.getSchema();
                String schemaName = schema == null ? "" : schema.getName();
                expectedTables.put(schemaName + "." + table.getName(), table.getClass().getName());
                expectedRecords.put(schemaName + "." + table.getName(),
                    table.getRecordType().getName());
                expectedSchemas.putIfAbsent(schemaName, jooq.keysClassFqn(schema).orElse(null));
                expectedTablesClasses.putIfAbsent(schemaName,
                    jooq.tablesClassFqn(schema).orElse(null));
            }
            assertThat(expectedTables).as("the catalog has tables, so this pins something").isNotEmpty();
            assertThat(expectedRecords.values())
                .as("a catalog that generated no record classes would make the record column vacuous")
                .anyMatch(name -> !name.equals(org.jooq.Record.class.getName()));
            assertThat(expectedSchemas.values())
                .as("if no Keys class resolved, the classpath lookup is broken and this is vacuous")
                .anyMatch(java.util.Objects::nonNull);
            assertThat(expectedTablesClasses.values())
                .as("same for Tables: a null everywhere would make the column pin nothing")
                .anyMatch(java.util.Objects::nonNull);

            var capturedTables = store.dsl()
                .select(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME, SQL_TABLE.CLASS_FQN)
                .from(SQL_TABLE)
                .fetch()
                .intoMap(r -> r.value1() + "." + r.value2(), r -> r.value3());
            assertThat(capturedTables).isEqualTo(expectedTables);

            var capturedRecords = store.dsl()
                .select(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME, SQL_TABLE.RECORD_CLASS_FQN)
                .from(SQL_TABLE)
                .fetch()
                .intoMap(r -> r.value1() + "." + r.value2(), r -> r.value3());
            assertThat(capturedRecords)
                .as("the row type, which is not the table class and not derivable from it")
                .isEqualTo(expectedRecords);

            var capturedSchemas = store.dsl()
                .select(SQL_SCHEMA.TABLE_SCHEMA, SQL_SCHEMA.KEYS_CLASS_FQN)
                .from(SQL_SCHEMA)
                .fetch()
                .intoMap(r -> r.value1(), r -> r.value2());
            assertThat(capturedSchemas)
                .as("one row per schema, not one per table")
                .isEqualTo(expectedSchemas);

            var capturedTablesClasses = store.dsl()
                .select(SQL_SCHEMA.TABLE_SCHEMA, SQL_SCHEMA.TABLES_CLASS_FQN)
                .from(SQL_SCHEMA)
                .fetch()
                .intoMap(r -> r.value1(), r -> r.value2());
            assertThat(capturedTablesClasses)
                .as("the Tables class, resolved off the classpath like its Keys sibling rather than"
                    + " concatenated from a package, which is what a store-sourced table reference"
                    + " needs and could not otherwise have")
                .isEqualTo(expectedTablesClasses);
        }
    }

    /**
     * The multi-schema half of the same claim, and the one that would catch a concatenation. Each
     * schema in that fixture lives in its own generated package, so the two schemas must report two
     * different {@code Tables} classes; a capture that derived the name from a configured package
     * would report one name twice and pass every single-schema assertion above.
     */
    @Test
    @DisplayName("each schema's Tables class is its own, which is what a formula would get wrong")
    void perSchemaTablesClassesAreDistinct(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var byPackage = store.dsl()
                .select(SQL_SCHEMA.SOURCE_NAME, SQL_SCHEMA.TABLES_CLASS_FQN)
                .from(SQL_SCHEMA)
                .where(SQL_SCHEMA.TABLES_CLASS_FQN.isNotNull())
                .fetch();
            assertThat(byPackage).as("at least one schema resolved a Tables class").isNotEmpty();
            assertThat(byPackage)
                .as("a Tables class lives in its own schema's package, so the name is prefixed by"
                    + " the source package rather than by a single configured one")
                .allSatisfy(row -> assertThat(row.value2())
                    .isEqualTo(row.value1() + ".Tables"));
        }
    }

    /**
     * The {@code Keys}-class constant name is what an author types in {@code @reference(key:)}, and
     * it is resolved by reference identity rather than by any formula over the constraint name. The
     * oracle here is that same identity resolution, so what the test pins is that capture stored what
     * the resolver answered for every constraint, including the nulls where a key has no constant.
     * A formula-based oracle would agree with a formula-based capture and prove nothing.
     */
    @Test
    @DisplayName("every captured constraint carries its resolved Keys constant, nulls included")
    void constraintJooqNamesEqualTheResolvedKeysConstants(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expected = new LinkedHashMap<String, String>();
            for (var entry : jooq.allTableEntries()) {
                var table = entry.table();
                String qualified = table.getSchema().getName() + "." + table.getName();
                var keys = new LinkedHashSet<org.jooq.Key<?>>(table.getKeys());
                if (table.getPrimaryKey() != null) {
                    keys.add(table.getPrimaryKey());
                }
                keys.addAll(table.getReferences());
                for (var key : keys) {
                    expected.put(qualified + "|" + key.getName(),
                        jooq.keyJavaConstantName(key).orElse(null));
                }
            }
            assertThat(expected).as("the catalog has constraints, so this pins something").isNotEmpty();
            assertThat(expected.values())
                .as("if nothing resolved, identity matching is broken and the comparison is vacuous")
                .anyMatch(java.util.Objects::nonNull);

            var captured = new LinkedHashMap<String, String>();
            store.dsl()
                .select(SQL_CONSTRAINT.TABLE_SCHEMA, SQL_CONSTRAINT.TABLE_NAME,
                    SQL_CONSTRAINT.CONSTRAINT_NAME, SQL_CONSTRAINT.JOOQ_NAME)
                .from(SQL_CONSTRAINT)
                .fetch()
                .forEach(row -> captured.put(
                    row.value1() + "." + row.value2() + "|" + row.value3(), row.value4()));
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * The constraint census against the catalog itself rather than against a consumer's view of it.
     * No fold: the old comparison had to reduce the store to a discovery tool's {@code uniqueKeys}
     * shape, which excludes the primary key and drops a unique constraint the primary key's column
     * set already covers, and a fold that bridges a mismatch capture introduced is
     * indistinguishable from one bridging a real grain difference.
     */
    @Test
    @DisplayName("the constraint census equals the catalog's, compared without a fold")
    void constraintCensusEqualsTheCatalog(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expected = new LinkedHashSet<String>();
            for (var entry : jooq.allTableEntries()) {
                var table = entry.table();
                String qualified = table.getSchema().getName() + "." + table.getName();
                var primary = table.getPrimaryKey();
                for (var key : new LinkedHashSet<>(table.getKeys())) {
                    expected.add(qualified + "|" + key.getName()
                        + "|" + (key.equals(primary) ? "PRIMARY KEY" : "UNIQUE"));
                }
                for (var fk : jooq.foreignKeyFactsOf(table)) {
                    expected.add(qualified + "|" + fk.constraintName() + "|FOREIGN KEY");
                }
            }
            assertThat(expected).as("the catalog declares constraints, so this pins something")
                .isNotEmpty();

            var captured = new LinkedHashSet<String>();
            store.dsl()
                .select(SQL_CONSTRAINT.TABLE_SCHEMA, SQL_CONSTRAINT.TABLE_NAME,
                    SQL_CONSTRAINT.CONSTRAINT_NAME, SQL_CONSTRAINT.CONSTRAINT_TYPE)
                .from(SQL_CONSTRAINT)
                .fetch()
                .forEach(row -> captured.add(
                    row.value1() + "." + row.value2() + "|" + row.value3() + "|" + row.value4()));
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * A foreign key's target columns are the referenced constraint's own columns matched on
     * position, never copied onto the referencing row. That is the claim the supertype shape rests
     * on, and it is only true if the join reproduces what the catalog reports.
     */
    @Test
    @DisplayName("a foreign key's target columns are the referenced constraint's, matched on position")
    void foreignKeyTargetsResolveThroughTheReferencedConstraint(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expected = new LinkedHashSet<String>();
            for (var entry : jooq.allTableEntries()) {
                var table = entry.table();
                String qualified = table.getSchema().getName() + "." + table.getName();
                for (var fk : jooq.foreignKeyFactsOf(table)) {
                    for (int i = 0; i < fk.targetColumns().size(); i++) {
                        expected.add(qualified + "|" + fk.constraintName() + "|" + i
                            + "|" + fk.targetColumns().get(i));
                    }
                }
            }
            assertThat(expected).as("the catalog declares foreign keys, so this pins something")
                .isNotEmpty();

            var referencing = SQL_CONSTRAINT_COLUMN.as("referencing");
            var referenced = SQL_CONSTRAINT_COLUMN.as("referenced");
            var captured = new LinkedHashSet<String>();
            store.dsl()
                .select(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA, SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME,
                    SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME, referencing.POSITION,
                    referenced.COLUMN_NAME)
                .from(SQL_REFERENTIAL_CONSTRAINT)
                .join(referencing)
                .on(referencing.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))
                .and(referencing.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA))
                .and(referencing.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME))
                .and(referencing.CONSTRAINT_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME))
                .join(referenced)
                .on(referenced.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))
                .and(referenced.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA))
                .and(referenced.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE))
                .and(referenced.CONSTRAINT_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_CONSTRAINT_NAME))
                .and(referenced.POSITION.eq(referencing.POSITION))
                .fetch()
                .forEach(row -> captured.add(row.value1() + "." + row.value2() + "|" + row.value3()
                    + "|" + row.value4() + "|" + row.value5()));
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * The cardinality the old {@code is_primary} flag needed a gate query for. Keying the relation
     * by the table is what makes it structural, so what is left to check is that the row names a
     * constraint of the right form.
     */
    @Test
    @DisplayName("each table's primary key names a PRIMARY KEY constraint")
    void primaryKeyRowsNamePrimaryKeyConstraints(@TempDir Path tmp) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());
            assertThat(store.dsl().fetchCount(SQL_PRIMARY_KEY))
                .as("the catalog has primary keys, so this pins something")
                .isPositive();
            var wrongForm = store.dsl()
                .select(SQL_PRIMARY_KEY.TABLE_NAME, SQL_CONSTRAINT.CONSTRAINT_TYPE)
                .from(SQL_PRIMARY_KEY)
                .join(SQL_CONSTRAINT)
                .on(SQL_CONSTRAINT.SOURCE_NAME.eq(SQL_PRIMARY_KEY.SOURCE_NAME))
                .and(SQL_CONSTRAINT.TABLE_SCHEMA.eq(SQL_PRIMARY_KEY.TABLE_SCHEMA))
                .and(SQL_CONSTRAINT.TABLE_NAME.eq(SQL_PRIMARY_KEY.TABLE_NAME))
                .and(SQL_CONSTRAINT.CONSTRAINT_NAME.eq(SQL_PRIMARY_KEY.CONSTRAINT_NAME))
                .where(SQL_CONSTRAINT.CONSTRAINT_TYPE.ne("PRIMARY KEY"))
                .fetch();
            assertThat(wrongForm).as("primary-key rows naming a constraint of another form").isEmpty();
        }
    }

    /**
     * The method census against the scan, descriptor and all. The comparison used to erase the
     * descriptor because the scan's projection dropped it and capture rebuilt one from the erased
     * display types, which two methods taking same-named types from different packages share; the
     * fold hid a collision the store resolved by dropping a row. The scan carries the real
     * descriptor now, so the census compares as a mirror.
     */
    @Test
    @DisplayName("the JVM method census equals the scanner's, descriptor included")
    void jvmMethodCensusEqualsTheScanner(@TempDir Path tmp) {
        var ctx = testContext();
        List<CompletionData.ExternalReference> extensions = CatalogBuilder.buildExternalReferences(ctx);
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), null, extensions);

            var captured = new LinkedHashSet<String>();
            store.dsl().select(JVM_METHOD.CLASS_NAME, JVM_METHOD.METHOD_NAME, JVM_METHOD.DESCRIPTOR)
                .from(JVM_METHOD).fetch()
                .forEach(row -> captured.add(row.value1() + "#" + row.value2() + row.value3()));

            var expected = new LinkedHashSet<String>();
            extensions.forEach(reference -> reference.methods()
                .forEach(method -> expected.add(
                    reference.className() + "#" + method.name() + method.descriptor())));
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * The supertype census against the scan, clause included. Compared as a mirror rather than by
     * containment: the relation is the closure's only input, so a hop capture dropped is a
     * hierarchy the store reads as ending early, which is indistinguishable from a class that
     * genuinely declares nothing above it.
     *
     * <p>The reactor's own classes are the fixture, so this also pins the two things the projection
     * decides rather than copies: that {@code java.lang.Object} is nowhere in the census (every
     * plain class declares it and none should have a row), and that a supertype outside the census
     * is recorded anyway (the JDK interfaces the reactor implements are exactly those names).
     */
    @Test
    @DisplayName("the JVM supertype census equals the scanner's, declaring clause included")
    void jvmSupertypeCensusEqualsTheScanner(@TempDir Path tmp) {
        var ctx = testContext();
        List<CompletionData.ExternalReference> extensions = CatalogBuilder.buildExternalReferences(ctx);
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), null, extensions);

            var captured = new LinkedHashSet<String>();
            store.dsl().select(JVM_CLASS_SUPERTYPE.CLASS_NAME, JVM_CLASS_SUPERTYPE.DECLARED_VIA,
                    JVM_CLASS_SUPERTYPE.SUPERTYPE_NAME)
                .from(JVM_CLASS_SUPERTYPE).fetch()
                .forEach(row -> captured.add(row.value1() + " " + row.value2() + " " + row.value3()));

            var expected = new LinkedHashSet<String>();
            extensions.forEach(reference -> reference.supertypes()
                .forEach(supertype -> expected.add(reference.className() + " "
                    + supertype.declaredVia() + " " + supertype.className())));
            assertThat(expected).as("the reactor declares hierarchies, so this pins something")
                .isNotEmpty();
            assertThat(captured).isEqualTo(expected);
            assertThat(captured).noneMatch(row -> row.endsWith(" java.lang.Object"));
            assertThat(captured)
                .as("a supertype the scan never reached is still a row; that is what the closure"
                    + " joins on")
                .anyMatch(row -> row.contains(" java.") || row.contains(" org.jooq."));
        }
    }

    /**
     * The three type-reference relations against the scan. One test because they are one rule
     * applied at three coordinates, and a mirror rather than containment for the reason the
     * supertype census is one: a walk reads a dropped position as a type that names nothing there,
     * which is indistinguishable from a position that genuinely names no class.
     *
     * <p>The reactor's own classes are the fixture, which lets this pin what the decomposition
     * decides rather than copies. Every name is qualified, that being the entire reason the
     * relations exist beside the display columns. Some row sits at a non-root path, so a generic
     * type is descended into rather than recorded as its outer class alone. And a root row's class
     * agrees with the erased display column once the package is dropped, which is what says the
     * qualification names the same class the census already reported rather than some other one.
     */
    @Test
    @DisplayName("the JVM type-reference census equals the scanner's, at every position")
    void jvmTypeReferenceCensusEqualsTheScanner(@TempDir Path tmp) {
        var ctx = testContext();
        List<CompletionData.ExternalReference> extensions = CatalogBuilder.buildExternalReferences(ctx);
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), null, extensions);

            var expectedReturns = new LinkedHashSet<String>();
            var expectedParameters = new LinkedHashSet<String>();
            var expectedComponents = new LinkedHashSet<String>();
            for (var reference : extensions) {
                for (var method : reference.methods()) {
                    String owner = reference.className() + " " + method.name() + method.descriptor();
                    method.returnTypeRefs().forEach(ref -> expectedReturns.add(render(owner, ref)));
                    int position = 0;
                    for (var parameter : method.parameters()) {
                        String at = owner + " #" + position++;
                        parameter.typeRefs().forEach(ref -> expectedParameters.add(render(at, ref)));
                    }
                }
                for (var component : reference.recordComponents()) {
                    String owner = reference.className() + " " + component.name();
                    component.typeRefs().forEach(ref -> expectedComponents.add(render(owner, ref)));
                }
            }

            var capturedReturns = new LinkedHashSet<String>();
            store.dsl().selectFrom(JVM_METHOD_RETURN_TYPE_REF).fetch().forEach(row -> capturedReturns.add(
                render(row.getClassName() + " " + row.getMethodName() + row.getDescriptor(),
                    row.getTypePath(), row.getReferencedClass(), row.getVariance())));
            var capturedParameters = new LinkedHashSet<String>();
            store.dsl().selectFrom(JVM_METHOD_PARAMETER_TYPE_REF).fetch().forEach(row -> capturedParameters.add(
                render(row.getClassName() + " " + row.getMethodName() + row.getDescriptor()
                        + " #" + row.getPosition(),
                    row.getTypePath(), row.getReferencedClass(), row.getVariance())));
            var capturedComponents = new LinkedHashSet<String>();
            store.dsl().selectFrom(JVM_RECORD_COMPONENT_TYPE_REF).fetch().forEach(row -> capturedComponents.add(
                render(row.getClassName() + " " + row.getComponentName(),
                    row.getTypePath(), row.getReferencedClass(), row.getVariance())));

            assertThat(expectedReturns).as("the reactor declares return types, so this pins something")
                .isNotEmpty();
            assertThat(expectedParameters).as("and parameters").isNotEmpty();
            assertThat(expectedComponents).as("and record components").isNotEmpty();
            assertThat(capturedReturns).isEqualTo(expectedReturns);
            assertThat(capturedParameters).isEqualTo(expectedParameters);
            assertThat(capturedComponents).isEqualTo(expectedComponents);

            var everyClass = store.dsl()
                .select(JVM_METHOD_RETURN_TYPE_REF.REFERENCED_CLASS).from(JVM_METHOD_RETURN_TYPE_REF)
                .unionAll(store.dsl().select(JVM_METHOD_PARAMETER_TYPE_REF.REFERENCED_CLASS)
                    .from(JVM_METHOD_PARAMETER_TYPE_REF))
                .unionAll(store.dsl().select(JVM_RECORD_COMPONENT_TYPE_REF.REFERENCED_CLASS)
                    .from(JVM_RECORD_COMPONENT_TYPE_REF))
                .fetch(0, String.class);
            assertThat(everyClass)
                .as("a package-less name is what the display columns already carry; this relation"
                    + " exists to resolve one")
                .allMatch(name -> name.contains("."));

            assertThat(capturedReturns.stream().anyMatch(row -> !row.contains(" @= ")))
                .as("a generic return type is descended into, not recorded as its outer class alone")
                .isTrue();

            var rootDisagreements = store.dsl()
                .select(JVM_METHOD.CLASS_NAME, JVM_METHOD.METHOD_NAME, JVM_METHOD.RETURN_TYPE,
                    JVM_METHOD_RETURN_TYPE_REF.REFERENCED_CLASS)
                .from(JVM_METHOD_RETURN_TYPE_REF)
                .join(JVM_METHOD)
                .on(JVM_METHOD.SOURCE_NAME.eq(JVM_METHOD_RETURN_TYPE_REF.SOURCE_NAME))
                .and(JVM_METHOD.CLASS_NAME.eq(JVM_METHOD_RETURN_TYPE_REF.CLASS_NAME))
                .and(JVM_METHOD.METHOD_NAME.eq(JVM_METHOD_RETURN_TYPE_REF.METHOD_NAME))
                .and(JVM_METHOD.DESCRIPTOR.eq(JVM_METHOD_RETURN_TYPE_REF.DESCRIPTOR))
                .where(JVM_METHOD_RETURN_TYPE_REF.TYPE_PATH.eq(""))
                .fetch()
                .stream()
                .filter(row -> !row.value4().substring(row.value4().lastIndexOf('.') + 1)
                    .equals(row.value3()))
                .map(row -> row.value1() + "." + row.value2() + ": " + row.value3()
                    + " vs " + row.value4())
                .toList();
            assertThat(rootDisagreements)
                .as("the qualified root names the class the erased display column already reported")
                .isEmpty();
        }
    }

    /** One type reference as a comparable line; the root path renders as {@code @=}. */
    private static String render(String owner, CompletionData.TypeRef ref) {
        return render(owner, ref.path(), ref.referencedClass(), ref.variance());
    }

    private static String render(String owner, String path, String referencedClass, String variance) {
        return owner + " @" + path + "= " + variance + " " + referencedClass;
    }

    /**
     * The store's stamp names the DDL it was built from. Equality against the resource rather than
     * against a recorded constant, because the whole job of the stamp is to make a persisted file
     * unreadable the moment the schema moves: a stamp computed from anything but the DDL this store
     * ran would let an older file survive an edit and answer with relations that no longer mean what
     * they say.
     */
    @Test
    @DisplayName("the store stamp names the DDL the store was built from")
    void theStampNamesTheSchema() throws Exception {
        byte[] ddl;
        try (var in = GraphitronModelStore.class.getResourceAsStream(GraphitronModelStore.DDL_RESOURCE)) {
            ddl = in.readAllBytes();
        }
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ddl));

        try (var store = GraphitronModelStore.open()) {
            var stamps = store.dsl().selectFrom(STORE_STAMP).fetch();
            assertThat(stamps).as("at most one row, and a booted store has it").hasSize(1);
            assertThat(stamps.getFirst().getDdlHash()).isEqualTo(expected);
        }
    }

    /**
     * Every captured class is reachable from the entry it was read from, which is the partition a
     * refresh deletes and re-walks. A jar is stamped by content so an unchanged one is read once; a
     * directory is not, changing on every compile.
     */
    @Test
    @DisplayName("the class census is partitioned by the classpath entry it came from")
    void classCensusIsPartitionedBySource(@TempDir Path tmp) {
        var ctx = testContext();
        List<CompletionData.ExternalReference> extensions = CatalogBuilder.buildExternalReferences(ctx);
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), null, extensions);

            var expected = new LinkedHashSet<>(extensions.stream()
                .map(CompletionData.ExternalReference::sourceName).toList());
            assertThat(expected).as("the scan read something, so this pins something").isNotEmpty();

            var captured = new LinkedHashSet<>(store.dsl()
                .select(STORE_SOURCE.SOURCE_NAME)
                .from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_KIND.in("DIRECTORY", "JAR"))
                .fetch(STORE_SOURCE.SOURCE_NAME));
            assertThat(captured).isEqualTo(expected);

            var unstamped = store.dsl()
                .select(STORE_SOURCE.SOURCE_NAME)
                .from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_KIND.eq("JAR"))
                .and(STORE_SOURCE.STAMP.isNull())
                .fetch(STORE_SOURCE.SOURCE_NAME);
            assertThat(unstamped).as("a jar the scan read is a jar it can hash").isEmpty();

            var stamped = store.dsl().fetchCount(STORE_SOURCE,
                STORE_SOURCE.SOURCE_KIND.eq("DIRECTORY").and(STORE_SOURCE.STAMP.isNotNull()));
            assertThat(stamped).as("a directory changes on every compile, so it is never stamped")
                .isZero();
        }
    }

    /**
     * The catalog is partitioned by the generated package its schema lives in, which is the
     * granularity codegen rewrites. The classpath entry the classes came from would also be true of
     * these rows and is the wrong unit: one jar carries every schema a codegen run produced, so
     * invalidating the entry discards schemas nothing touched.
     */
    @Test
    @DisplayName("the table census is partitioned by its schema's generated package")
    void catalogIsPartitionedBySchemaPackage(@TempDir Path tmp) {
        // The multi-schema fixture, because the single-schema catalog cannot tell a per-schema
        // partition from a per-jar one: both fixtures ship in the same jar, and only this one
        // spreads its tables over more than one generated package.
        var jooq = new JooqCatalog("no.sikt.graphitron.rewrite.multischemafixture",
            testContext().codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                emptyRegistry(tmp), CapturedStore.attributionOf(tmp), jooq, List.of());

            var expected = new LinkedHashSet<String>();
            for (var entry : jooq.allTableEntries()) {
                expected.add(entry.table().getSchema().getClass().getPackageName());
            }
            assertThat(expected).as("the fixture spans schemas, so this pins the granularity")
                .hasSizeGreaterThan(1);

            var sources = new LinkedHashSet<>(store.dsl()
                .select(STORE_SOURCE.SOURCE_NAME)
                .from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA"))
                .fetch(STORE_SOURCE.SOURCE_NAME));
            assertThat(sources).isEqualTo(expected);

            // Every table reaches one, and reaches the one its own schema names.
            var mismatched = store.dsl()
                .select(SQL_TABLE.TABLE_NAME, SQL_TABLE.SOURCE_NAME)
                .from(SQL_TABLE)
                .where(SQL_TABLE.SOURCE_NAME.notIn(expected))
                .fetch();
            assertThat(mismatched).as("tables whose source is not their schema's package").isEmpty();
        }
    }

    /**
     * The SDL side is partitionable too. It declares no foreign key into {@code store_source}: a
     * schema-level row can carry a null source name, and the FK doctrine puts one only where the
     * walk writes the child while standing on the parent. Reachability is what the partition rule
     * asks for, and these rows are it.
     */
    @Test
    @DisplayName("every schema file the walk read has a source row")
    void schemaFilesAreRecordedAsSources(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var declared = new LinkedHashSet<>(store.dsl()
                .selectDistinct(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME)
                .from(GRAPHQL_TYPE_DECLARATION)
                .where(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME.isNotNull())
                .fetch(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME));
            assertThat(declared).as("the fixture declares types, so this pins something").isNotEmpty();

            var sources = new LinkedHashSet<>(store.dsl()
                .select(STORE_SOURCE.SOURCE_NAME)
                .from(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_KIND.eq("SCHEMA_FILE"))
                .fetch(STORE_SOURCE.SOURCE_NAME));
            assertThat(sources).containsAll(declared);
        }
    }

    /**
     * The membership relation's equality anchor: the run's read-set reduced two ways, once by
     * capture's own notes and once by re-enumerating the run's inputs here (the SDL registry's
     * source census, the catalog's schema packages, the scan's entry names). Two graphs in one
     * store, one catalog-bearing and one SDL-only, so "this graph's sources" is distinguishable
     * from "sources anyone read": the SDL-only graph must not inherit the sibling's catalog
     * membership, and capturing it must leave the sibling's rows standing.
     */
    @Test
    @DisplayName("graph-to-source membership equals the run's own read-set, per graph")
    void graphSourceMembershipEqualsTheRunsReadSet(@TempDir Path tmp) throws java.io.IOException {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        List<CompletionData.ExternalReference> extensions = CatalogBuilder.buildExternalReferences(ctx);
        Path aDir = java.nio.file.Files.createDirectories(tmp.resolve("a"));
        Path bDir = java.nio.file.Files.createDirectories(tmp.resolve("b"));
        try (var store = GraphitronModelStore.open()) {
            var aRegistry = CapturedStore.registryOf(aDir, FIXTURE);
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity("a", aDir),
                FactCapture.SubjectConfig.none(), aRegistry, CapturedStore.attributionOf(aDir),
                jooq, extensions);

            var expectedA = new LinkedHashSet<>(sdlSourceNames(aRegistry));
            for (var entry : jooq.allTableEntries()) {
                expectedA.add(entry.table().getSchema().getClass().getPackageName());
            }
            for (var reference : extensions) {
                expectedA.add(reference.sourceName() == null ? "" : reference.sourceName());
            }
            assertThat(membership(store, "a")).isEqualTo(expectedA);

            var bRegistry = CapturedStore.registryOf(bDir, "type Query { ping: String }");
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity("b", bDir),
                FactCapture.SubjectConfig.none(), bRegistry, CapturedStore.attributionOf(bDir));
            assertThat(membership(store, "b"))
                .as("an SDL-only capture's membership is its own file census, no inherited catalog")
                .isEqualTo(new LinkedHashSet<>(sdlSourceNames(bRegistry)));
            assertThat(membership(store, "a"))
                .as("a sibling's capture leaves this graph's membership standing")
                .isEqualTo(expectedA);
        }
    }

    /** The graph's membership rows, as a set for order-free comparison. */
    private static Set<String> membership(GraphitronModelStore store, String graphName) {
        return new LinkedHashSet<>(store.dsl()
            .select(STORE_GRAPH_SOURCE.SOURCE_NAME)
            .from(STORE_GRAPH_SOURCE)
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(graphName))
            .fetch(STORE_GRAPH_SOURCE.SOURCE_NAME));
    }

    /**
     * The registry's source-name census, mirroring the SDL walk's own enumeration (types, scalars,
     * directive definitions, the schema definition and its extensions, and every type-extension
     * map), which is what makes the membership comparison a second reduction of the same input.
     */
    private static Set<String> sdlSourceNames(graphql.schema.idl.TypeDefinitionRegistry registry) {
        var names = new LinkedHashSet<String>();
        registry.schemaDefinition().ifPresent(node -> addSourceName(names, node));
        registry.getSchemaExtensionDefinitions().forEach(node -> addSourceName(names, node));
        registry.getDirectiveDefinitions().values().forEach(node -> addSourceName(names, node));
        registry.types().values().forEach(node -> addSourceName(names, node));
        registry.scalars().values().forEach(node -> addSourceName(names, node));
        registry.objectTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.interfaceTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.unionTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.enumTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.scalarTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.inputObjectTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        return names;
    }

    private static void addSourceName(Set<String> names, graphql.language.Node<?> node) {
        if (node != null && node.getSourceLocation() != null
                && node.getSourceLocation().getSourceName() != null) {
            names.add(node.getSourceLocation().getSourceName());
        }
    }

    /**
     * The {@code ORACLE} arm's lifecycle anchor. Seeding is what distinguishes "cleared" from
     * "never written", since an unseeded emptiness check would pass identically with the writer
     * deleted; the second graph is what distinguishes "cleared what it owns" from "cleared
     * everything", which under a shared store is the difference between a correct refresh and one
     * that eats a sibling module's diagnostics. Asserted after a cold capture (trivially empty)
     * and after a warm one (seeded, then cleared scoped). The one thing genuinely unpinned is
     * javac's verdict itself: no independent second walk can re-derive it without re-running
     * javac.
     */
    @Test
    @DisplayName("a capture empties its own graph's javac partition and no other's")
    void oracleLifecycleClearsTheOwnedJavacPartitionOnly(@TempDir Path tmp) throws java.io.IOException {
        Path ownDir = java.nio.file.Files.createDirectories(tmp.resolve("own"));
        Path siblingDir = java.nio.file.Files.createDirectories(tmp.resolve("sibling"));
        try (var store = GraphitronModelStore.open()) {
            var own = new FactCapture.GraphIdentity("own", ownDir);
            var sibling = new FactCapture.GraphIdentity("sibling", siblingDir);

            // Cold: capture never writes the oracle family, so a fresh graph's partition is empty.
            FactCapture.capture(store.dsl(), own, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(ownDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(ownDir));
            assertThat(javacPartition(store, "own")).isEmpty();

            // Rounds land after capture, under both graphs.
            var round = new CompileRound(false, List.of(
                new CompileDiagnostic("file:///gen/A.java", 3, 1, "ERROR", "compiler.err.cant.resolve",
                    "cannot find symbol")));
            new CompileFacts(store.dsl(), own).write(round);
            new CompileFacts(store.dsl(), sibling).write(round);
            assertThat(javacPartition(store, "own")).isNotEmpty();
            var siblingBefore = javacPartition(store, "sibling");
            assertThat(siblingBefore).isNotEmpty();

            // Warm: the next capture of `own` empties exactly its own partition.
            FactCapture.capture(store.dsl(), true, own, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(ownDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(ownDir),
                null, List.of());
            assertThat(javacPartition(store, "own"))
                .as("the captured graph's javac partition, after its own warm capture")
                .isEmpty();
            assertThat(javacPartition(store, "sibling"))
                .as("the sibling graph's javac partition, after another graph's capture")
                .isEqualTo(siblingBefore);
        }
    }

    /**
     * The {@code ORACLE} arm's content anchor: the same round reduced two ways, at the oracle's
     * cadence. The {@code EQUALITY} arm's own character applied to a writer instead of a walk,
     * and what catches a writer bug (dropped rows, ordinal collisions, a transaction split) that
     * construction alone would let through. Ordinal grain included: two identical diagnostics at
     * one position are two rows, numbered in round order.
     */
    @Test
    @DisplayName("the javac relation's rows equal the round's published list, ordinal grain included")
    void oracleContentEqualsTheRoundsPublishedList(@TempDir Path tmp) {
        try (var store = GraphitronModelStore.open()) {
            var twin = new CompileDiagnostic("file:///gen/A.java", 12, 7, "ERROR",
                "compiler.err.cant.resolve", "cannot find symbol");
            var round = new CompileRound(false, List.of(
                twin, twin,
                new CompileDiagnostic("(no source)", -1, -1, "WARNING", null, "unchecked call")));
            new CompileFacts(store.dsl(), graph(tmp)).write(round);

            var expected = new LinkedHashSet<String>();
            var ordinals = new LinkedHashMap<String, Integer>();
            for (var d : round.diagnostics()) {
                int ordinal = ordinals.merge(d.file() + "|" + d.line() + "|" + d.column(), 1,
                    Integer::sum) - 1;
                expected.add(String.join("|", d.file(), String.valueOf(d.line()),
                    String.valueOf(d.column()), String.valueOf(ordinal), d.kind(),
                    String.valueOf(d.code()), d.message()));
            }

            var captured = new LinkedHashSet<String>();
            store.dsl().selectFrom(JAVAC_DIAGNOSTIC).fetch().forEach(row -> captured.add(
                String.join("|", row.getFile(), String.valueOf(row.getLineNumber()),
                    String.valueOf(row.getColumnNumber()), String.valueOf(row.getOrdinal()),
                    row.getKind(), String.valueOf(row.getCode()), row.getMessage())));
            assertThat(captured).isEqualTo(expected);
        }
    }

    /**
     * The {@code walk_} family's lifecycle anchor, on the same terms as the javac one: seeded rows
     * under two graphs, and a warm capture empties exactly its own partition. The writer here is
     * the capture-and-detect pass rather than a post-capture round, which is the cadence widening
     * the {@code ORACLE} arm's javadoc states. The binding grain is the family's only remaining
     * relation, so it is the whole partition this asserts over.
     */
    @Test
    @DisplayName("a capture empties its own graph's walk-binding partition and no other's")
    void oracleLifecycleClearsTheOwnedWalkBindingPartitionOnly(@TempDir Path tmp) throws java.io.IOException {
        Path ownDir = java.nio.file.Files.createDirectories(tmp.resolve("own"));
        Path siblingDir = java.nio.file.Files.createDirectories(tmp.resolve("sibling"));
        try (var store = GraphitronModelStore.open()) {
            var own = new FactCapture.GraphIdentity("own", ownDir);
            var sibling = new FactCapture.GraphIdentity("sibling", siblingDir);
            FactCapture.capture(store.dsl(), own, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(ownDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(ownDir));
            FactCapture.capture(store.dsl(), sibling, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(siblingDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(siblingDir));
            assertThat(walkBindingPartition(store, "own")).isEmpty();

            var backing = new no.sikt.graphitron.rewrite.derive.TypeBackingClasses(
                Map.of("Film", "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"));
            no.sikt.graphitron.rewrite.derive.TypeBackingClassRows.write(store.dsl(), "own", backing);
            no.sikt.graphitron.rewrite.derive.TypeBackingClassRows.write(store.dsl(), "sibling", backing);
            assertThat(walkBindingPartition(store, "own")).isNotEmpty();
            var siblingBefore = walkBindingPartition(store, "sibling");
            assertThat(siblingBefore).isNotEmpty();

            FactCapture.capture(store.dsl(), true, own, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(ownDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(ownDir),
                null, List.of());
            assertThat(walkBindingPartition(store, "own"))
                .as("the captured graph's walk-binding partition, after its own warm capture")
                .isEmpty();
            assertThat(walkBindingPartition(store, "sibling"))
                .as("the sibling graph's walk-binding partition, after another graph's capture")
                .isEqualTo(siblingBefore);
        }
    }

    /**
     * The {@code walk_} family's content anchor: the same value reduced two ways, once by the
     * writer's rows and once by re-reading the
     * {@link no.sikt.graphitron.rewrite.derive.TypeBackingClasses} it transcribed. A rewrite
     * replaces the partition rather than accreting, which is what makes the second write's smaller
     * map an assertion and not a subset check. The projection itself is pinned against a walked
     * model by {@code no.sikt.graphitron.rewrite.derive.TypeBackingClassesTest}; what is pinned
     * here is that the writer lands exactly what it was handed.
     */
    @Test
    @DisplayName("the walk-binding relation's rows equal the transcribed bindings")
    void oracleContentEqualsTheTranscribedBindings(@TempDir Path tmp) {
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(tmp, "type Query { ping: String }"),
                CapturedStore.attributionOf(tmp));
            var backing = new no.sikt.graphitron.rewrite.derive.TypeBackingClasses(Map.of(
                "Film", "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord",
                "Language", "no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord"));
            no.sikt.graphitron.rewrite.derive.TypeBackingClassRows.write(store.dsl(), graph(tmp).name(), backing);

            assertThat(store.dsl().select(WALK_TYPE_BACKING_CLASS.TYPE_NAME, WALK_TYPE_BACKING_CLASS.CLASS_NAME)
                .from(WALK_TYPE_BACKING_CLASS)
                .fetchMap(r -> r.value1(), r -> r.value2()))
                .isEqualTo(backing.byTypeName());

            no.sikt.graphitron.rewrite.derive.TypeBackingClassRows.write(store.dsl(), graph(tmp).name(),
                new no.sikt.graphitron.rewrite.derive.TypeBackingClasses(Map.of(
                    "Film", "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord")));
            assertThat(store.dsl().fetchCount(WALK_TYPE_BACKING_CLASS)).isEqualTo(1);
        }
    }

    /**
     * The loaded diagnostics arms' lifecycle anchor, on the same terms as the javac one: seeded
     * rows under two graphs, and a warm capture empties exactly its own partition. One anchor
     * covers all three relations (and the residue's directive child), because they share one
     * writer cadence and the graph-scoped clear that empties them is the same derived set.
     */
    @Test
    @DisplayName("a capture empties its own graph's loaded diagnostics partitions and no other's")
    void oracleLifecycleClearsTheOwnedDiagnosticsPartitionsOnly(@TempDir Path tmp) throws java.io.IOException {
        Path ownDir = java.nio.file.Files.createDirectories(tmp.resolve("own"));
        Path siblingDir = java.nio.file.Files.createDirectories(tmp.resolve("sibling"));
        try (var store = GraphitronModelStore.open()) {
            var own = new FactCapture.GraphIdentity("own", ownDir);
            var sibling = new FactCapture.GraphIdentity("sibling", siblingDir);
            FactCapture.capture(store.dsl(), own, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(ownDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(ownDir));
            FactCapture.capture(store.dsl(), sibling, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(siblingDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(siblingDir));
            assertThat(diagnosticsPartition(store, "own")).isEmpty();

            var loc = new graphql.language.SourceLocation(3, 1, ownDir.resolve("s.graphqls").toString());
            var errors = List.of(
                no.sikt.graphitron.rewrite.ValidationError.forField("Film.title",
                    no.sikt.graphitron.rewrite.model.Rejection.directiveConflict(
                        List.of("service", "routine"), "@service, @routine are mutually exclusive"), loc));
            var warnings = List.<no.sikt.graphitron.rewrite.BuildWarning>of(
                new no.sikt.graphitron.rewrite.BuildWarning.NoRule("advisory", loc));
            new no.sikt.graphitron.rewrite.diagnostics.RejectionFacts(store.dsl(), own).write(errors);
            new no.sikt.graphitron.rewrite.diagnostics.BuildWarningFacts(store.dsl(), own).write(warnings);
            new no.sikt.graphitron.rewrite.diagnostics.RejectionFacts(store.dsl(), sibling).write(errors);
            new no.sikt.graphitron.rewrite.diagnostics.BuildWarningFacts(store.dsl(), sibling).write(warnings);
            assertThat(diagnosticsPartition(store, "own")).isNotEmpty();
            var siblingBefore = diagnosticsPartition(store, "sibling");
            assertThat(siblingBefore).isNotEmpty();

            FactCapture.capture(store.dsl(), true, own, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(ownDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(ownDir),
                null, List.of());
            assertThat(diagnosticsPartition(store, "own"))
                .as("the captured graph's loaded diagnostics partitions, after its own warm capture")
                .isEmpty();
            assertThat(diagnosticsPartition(store, "sibling"))
                .as("the sibling graph's loaded diagnostics partitions, after another graph's capture")
                .isEqualTo(siblingBefore);
        }
    }

    /**
     * The SDL-toolchain arms' write-read content anchor. One fixture provokes all three reading
     * stages at once, and the rows are reduced against what the stages themselves reported rather
     * than against hand-written literals, so the anchor pins the transcription without restating
     * graphql-java's verdicts (the one thing the arm leaves genuinely unpinned).
     *
     * <p>The fixture is also the pipeline property in miniature: three sources, one of which will
     * not parse and one of which redefines a type the first declared, and the run still reaches
     * assembly and still has facts about every source that survived.
     */
    @Test
    @DisplayName("a capture writes every reading stage's verdict, and the survivors' facts with them")
    void sdlVerdictsAreWrittenAsTheStagesReportedThem(@TempDir Path tmp) throws java.io.IOException {
        Path good = tmp.resolve("good.graphqls");
        java.nio.file.Files.writeString(good, "type Query { ping: String, gone: Nope }\ntype Dup { a: String }\n");
        Path broken = tmp.resolve("broken.graphqls");
        java.nio.file.Files.writeString(broken, "type Ignored { id: ID! }\nstrayTokenHere\n");
        Path redefining = tmp.resolve("redefining.graphqls");
        java.nio.file.Files.writeString(redefining, "type Dup { b: String }\n");

        var sources = List.of(SchemaSource.file(good), SchemaSource.file(broken),
            SchemaSource.file(redefining));
        var read = RewriteSchemaLoader.parsePerSource(sources);
        var assembly = no.sikt.graphitron.rewrite.schema.SchemaAssembly.of(read.registry());
        var verdicts = no.sikt.graphitron.rewrite.schema.SdlVerdicts.of(read);

        // All three stages refused something, so no arm of the anchor below is vacuous.
        assertThat(read.failures()).hasSize(1);
        assertThat(verdicts.schemaErrors()).extracting(e -> e.stage().name()).contains("REGISTRY");
        assertThat(assembly.errors()).isNotEmpty();

        var graph = new FactCapture.GraphIdentity("own", tmp);
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), false, graph, FactCapture.SubjectConfig.none(),
                read.registry(), assembly, verdicts,
                SchemaInputAttribution.build(sources.stream().map(f -> SchemaInput.file(f.path())).toList()),
                null, List.of());

            var syntaxRows = store.dsl().selectFrom(GRAPHQL_SYNTAX_ERROR)
                .where(GRAPHQL_SYNTAX_ERROR.GRAPH_NAME.eq("own")).fetch();
            assertThat(syntaxRows).hasSize(1);
            var syntaxRow = syntaxRows.getFirst();
            var failure = read.failures().getFirst();
            assertThat(syntaxRow.getSourceName()).isEqualTo(failure.sourceName());
            assertThat(syntaxRow.getMessage()).isEqualTo(failure.verbatimMessage());
            assertThat(syntaxRow.getSourceLine()).isEqualTo(failure.location().getLine());
            assertThat(syntaxRow.getSourceColumn()).isEqualTo(failure.location().getColumn());

            assertThat(store.dsl().selectFrom(GRAPHQL_SCHEMA_ERROR)
                .where(GRAPHQL_SCHEMA_ERROR.GRAPH_NAME.eq("own"))
                .orderBy(GRAPHQL_SCHEMA_ERROR.ORDINAL).fetch()
                .map(row -> row.getOrdinal() + "|" + row.getStage() + "|" + row.getErrorClass()
                    + "|" + row.getMessage()))
                .containsExactlyElementsOf(renderExpectedSchemaErrors(verdicts, assembly));

            // The stages refusing did not cost the surviving sources their facts, and the source
            // that did not parse contributed none: the property the whole arrangement rests on.
            assertThat(store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq("own")).fetchSet(0, String.class))
                .contains("Query", "Dup")
                .doesNotContain("Ignored");
        }
    }

    /**
     * The schema-error rows the verdicts and the assembly entail, in the same rendering the
     * assertion reads. Both stages in the order they ran, which is the order the ordinal keys.
     */
    private static List<String> renderExpectedSchemaErrors(
            no.sikt.graphitron.rewrite.schema.SdlVerdicts verdicts,
            no.sikt.graphitron.rewrite.schema.SchemaAssembly assembly) {
        var expected = new ArrayList<String>();
        var errors = new ArrayList<>(verdicts.schemaErrors());
        errors.addAll(assembly.errors());
        int ordinal = 0;
        for (var error : errors) {
            expected.add(ordinal++ + "|" + error.stage().name() + "|" + error.errorClass()
                + "|" + error.message());
        }
        return expected;
    }

    /**
     * The SDL-toolchain arms' lifecycle anchor. Unlike the loaded diagnostics arms, whose rows a
     * separate writer seeds, these are written by capture itself, so the anchor is a capture that
     * refused things followed by one that refused nothing: the first proves the rows are written at
     * all, the second that a clean read empties exactly the reading graph's own partition. That
     * second capture is also what makes emptiness readable as "this document was read clean"
     * instead of "nobody has looked".
     */
    @Test
    @DisplayName("a clean capture empties its own graph's SDL verdict partitions and no other's")
    void oracleLifecycleClearsTheOwnedSdlVerdictPartitionsOnly(@TempDir Path tmp) throws java.io.IOException {
        Path ownDir = java.nio.file.Files.createDirectories(tmp.resolve("own"));
        Path siblingDir = java.nio.file.Files.createDirectories(tmp.resolve("sibling"));
        try (var store = GraphitronModelStore.open()) {
            var own = new FactCapture.GraphIdentity("own", ownDir);
            var sibling = new FactCapture.GraphIdentity("sibling", siblingDir);
            captureRefusing(store, own, ownDir);
            captureRefusing(store, sibling, siblingDir);
            assertThat(sdlVerdictPartition(store, "own")).isNotEmpty();
            var siblingBefore = sdlVerdictPartition(store, "sibling");
            assertThat(siblingBefore).isNotEmpty();

            FactCapture.capture(store.dsl(), true, own, FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(ownDir, "type Query { ping: String }"),
                CapturedStore.attributionOf(ownDir),
                null, List.of());
            assertThat(sdlVerdictPartition(store, "own"))
                .as("the captured graph's SDL verdict partitions, after a clean warm capture")
                .isEmpty();
            assertThat(sdlVerdictPartition(store, "sibling"))
                .as("the sibling graph's SDL verdict partitions, after another graph's capture")
                .isEqualTo(siblingBefore);
        }
    }

    /** A capture of a source set whose parse and assembly both refuse something. */
    private static void captureRefusing(GraphitronModelStore store, FactCapture.GraphIdentity graph,
                                        Path directory) throws java.io.IOException {
        Path missingType = directory.resolve("missing.graphqls");
        java.nio.file.Files.writeString(missingType, "type Query { gone: Nope }\n");
        Path broken = directory.resolve("broken.graphqls");
        java.nio.file.Files.writeString(broken, "strayTokenHere\n");
        var sources = List.of(SchemaSource.file(missingType), SchemaSource.file(broken));
        var read = RewriteSchemaLoader.parsePerSource(sources);
        var verdicts = no.sikt.graphitron.rewrite.schema.SdlVerdicts.of(read);
        FactCapture.capture(store.dsl(), false, graph, FactCapture.SubjectConfig.none(),
            read.registry(), verdicts,
            SchemaInputAttribution.build(sources.stream().map(f -> SchemaInput.file(f.path())).toList()),
            null, List.of());
    }

    /** The graph's SDL verdict rows across both relations, rendered stably. */
    private static List<String> sdlVerdictPartition(GraphitronModelStore store, String graphName) {
        var rows = new ArrayList<String>();
        store.dsl().selectFrom(GRAPHQL_SYNTAX_ERROR)
            .where(GRAPHQL_SYNTAX_ERROR.GRAPH_NAME.eq(graphName))
            .orderBy(GRAPHQL_SYNTAX_ERROR.SOURCE_NAME)
            .forEach(row -> rows.add("syntax|" + row.getSourceName() + "|" + row.getMessage()));
        store.dsl().selectFrom(GRAPHQL_SCHEMA_ERROR)
            .where(GRAPHQL_SCHEMA_ERROR.GRAPH_NAME.eq(graphName))
            .orderBy(GRAPHQL_SCHEMA_ERROR.ORDINAL)
            .forEach(row -> rows.add("schema|" + row.getOrdinal() + "|" + row.getStage()
                + "|" + row.getErrorClass()));
        return rows;
    }

    /** The graph's loaded diagnostics rows across all four relations, rendered stably. */
    private static List<String> diagnosticsPartition(GraphitronModelStore store, String graphName) {
        var rows = new ArrayList<String>();
        store.dsl().selectFrom(REJECTION_VALIDATION_ERROR)
            .where(REJECTION_VALIDATION_ERROR.GRAPH_NAME.eq(graphName))
            .orderBy(REJECTION_VALIDATION_ERROR.ORDINAL)
            .forEach(row -> rows.add("rejection|" + row.getOrdinal() + "|" + row.getMessage()));
        store.dsl().selectFrom(REJECTION_VALIDATION_ERROR_DIRECTIVE)
            .where(REJECTION_VALIDATION_ERROR_DIRECTIVE.GRAPH_NAME.eq(graphName))
            .orderBy(REJECTION_VALIDATION_ERROR_DIRECTIVE.ERROR_ORDINAL,
                REJECTION_VALIDATION_ERROR_DIRECTIVE.POSITION)
            .forEach(row -> rows.add("directive|" + row.getErrorOrdinal() + "|" + row.getDirective()));
        store.dsl().selectFrom(LINT_FINDING)
            .where(LINT_FINDING.GRAPH_NAME.eq(graphName))
            .orderBy(LINT_FINDING.ORDINAL)
            .forEach(row -> rows.add("lint|" + row.getOrdinal() + "|" + row.getMessage()));
        store.dsl().selectFrom(BUILD_WARNING_NO_RULE)
            .where(BUILD_WARNING_NO_RULE.GRAPH_NAME.eq(graphName))
            .orderBy(BUILD_WARNING_NO_RULE.ORDINAL)
            .forEach(row -> rows.add("advisory|" + row.getOrdinal() + "|" + row.getMessage()));
        return rows;
    }

    /** The graph's walk-binding rows, rendered stably for before/after comparison. */
    private static List<String> walkBindingPartition(GraphitronModelStore store, String graphName) {
        var rows = new ArrayList<String>();
        store.dsl().selectFrom(WALK_TYPE_BACKING_CLASS)
            .where(WALK_TYPE_BACKING_CLASS.GRAPH_NAME.eq(graphName))
            .orderBy(WALK_TYPE_BACKING_CLASS.TYPE_NAME)
            .forEach(row -> rows.add("backing|" + row.getTypeName() + "=" + row.getClassName()));
        return rows;
    }

    /** The graph's {@code javac_diagnostic} rows, rendered stably for before/after comparison. */
    private static List<String> javacPartition(GraphitronModelStore store, String graphName) {
        return store.dsl().selectFrom(JAVAC_DIAGNOSTIC)
            .where(JAVAC_DIAGNOSTIC.GRAPH_NAME.eq(graphName))
            .orderBy(JAVAC_DIAGNOSTIC.FILE, JAVAC_DIAGNOSTIC.LINE_NUMBER,
                JAVAC_DIAGNOSTIC.COLUMN_NUMBER, JAVAC_DIAGNOSTIC.ORDINAL)
            .fetch()
            .map(Object::toString);
    }

    /** Counts every non-graphitron directive application in the fixture, keyed as the view keys them. */
    private static Map<String, Integer> sdlApplicationCounts(CapturedStore store) {
        var counts = new LinkedHashMap<String, Integer>();
        var registry = store.registry();

        registry.schemaDefinition().ifPresent(schema ->
            count(counts, schema.getDirectives(), "SCHEMA", null, null, null));
        registry.getSchemaExtensionDefinitions().forEach(extension ->
            count(counts, extension.getDirectives(), "SCHEMA", null, null, null));

        // graphql-java declares types() over the raw TypeDefinition; widen once here rather than
        // naming the raw type at every use site.
        var definitions = new ArrayList<TypeDefinition<?>>();
        registry.types().values().forEach(definitions::add);
        definitions.addAll(registry.scalars().values());
        registry.objectTypeExtensions().values().forEach(definitions::addAll);
        registry.interfaceTypeExtensions().values().forEach(definitions::addAll);
        registry.unionTypeExtensions().values().forEach(definitions::addAll);
        registry.enumTypeExtensions().values().forEach(definitions::addAll);
        registry.inputObjectTypeExtensions().values().forEach(definitions::addAll);
        registry.scalarTypeExtensions().values().forEach(definitions::addAll);

        for (TypeDefinition<?> definition : definitions) {
            String type = definition.getName();
            count(counts, definition.getDirectives(), "TYPE", type, null, null);
            switch (definition) {
                case ObjectTypeDefinition object -> countFields(counts, type, object.getFieldDefinitions());
                case InterfaceTypeDefinition iface -> countFields(counts, type, iface.getFieldDefinitions());
                case InputObjectTypeDefinition input -> input.getInputValueDefinitions().forEach(field ->
                    count(counts, field.getDirectives(), "FIELD", type, field.getName(), null));
                case EnumTypeDefinition enumType -> {
                    for (EnumValueDefinition value : enumType.getEnumValueDefinitions()) {
                        count(counts, value.getDirectives(), "ENUM_VALUE", type, value.getName(), null);
                    }
                }
                default -> { /* unions and scalars carry no member-level applications */ }
            }
        }
        return counts;
    }

    private static void countFields(Map<String, Integer> counts, String type, List<FieldDefinition> fields) {
        for (FieldDefinition field : fields) {
            count(counts, field.getDirectives(), "FIELD", type, field.getName(), null);
            for (InputValueDefinition argument : field.getInputValueDefinitions()) {
                count(counts, argument.getDirectives(), "ARGUMENT", type, field.getName(), argument.getName());
            }
        }
    }

    private static void count(Map<String, Integer> counts, List<Directive> directives,
                              String siteKind, String type, String member, String argument) {
        for (Directive directive : directives) {
            counts.merge(String.join("|", siteKind, String.valueOf(type), String.valueOf(member),
                String.valueOf(argument), directive.getName()), 1, Integer::sum);
        }
    }

    /**
     * The recipe's equality anchor, and the item's own enforcer: the run's recipe rows, decoded back
     * by the production decoder and re-expanded, reproduce the run's own
     * {@link no.sikt.graphitron.rewrite.RewriteContext#schemaInputs} exactly. Both sides run the one
     * expansion, so what the equality pins is transcription fidelity plus glob determinism rather
     * than two independent expansions, which is exactly the residue a single expansion path leaves to
     * verify.
     *
     * <p>Non-vacuity is a requirement on the case rather than a property of the shape: a literal
     * entry re-expands by identity, so a fixture of literals alone would satisfy the equality while
     * testing nothing. The fixture carries a pattern entry and the case asserts that it does before
     * it asserts the round trip, so a later edit that trims the fixture to literals fails here instead
     * of hollowing the anchor out silently.
     */
    @Test
    @DisplayName("a run's recipe rows decode and re-expand to the run's own schema inputs")
    void theRecipeRoundTripsThroughItsRows(@TempDir Path tmp) throws java.io.IOException {
        // Under a subdirectory the capture fixture's own file does not fall into, so the file set the
        // pattern ranges over is exactly what this case wrote.
        Path sdl = java.nio.file.Files.createDirectories(tmp.resolve("sdl"));
        java.nio.file.Files.writeString(sdl.resolve("globbed.graphqls"), "type Query { ping: String }");
        java.nio.file.Files.writeString(sdl.resolve("extra.graphqls"), "type Extra { id: ID }");
        var recipe = new no.sikt.graphitron.rewrite.schema.input.SchemaRecipe(null,
            List.of(no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.Binding.pattern("sdl/*.graphqls")),
            List.of(".graphqls"));
        assertThat(recipe.bindings())
            .as("the fixture carries a pattern entry, without which the round trip is identity and "
                + "pins nothing")
            .anyMatch(b -> b.entry() instanceof
                no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.Entry.Pattern);

        assertRecipeRoundTrips(tmp, recipe);
    }

    /**
     * The same anchor over a programmatic run's literal rows, beside the pattern case rather than
     * instead of it, and pinning something narrower stated honestly: literal re-expansion is
     * identity, so this half verifies row encode/decode fidelity (the empty-tag collapse, the kind
     * dispatch) rather than a second independent derivation.
     */
    @Test
    @DisplayName("a programmatic run's literal recipe rows round-trip to its own input list")
    void aLiteralRecipeRoundTripsThroughItsRows(@TempDir Path tmp) throws java.io.IOException {
        Path file = tmp.resolve("literal.graphqls");
        java.nio.file.Files.writeString(file, "type Query { ping: String }");
        var inputs = List.of(
            new no.sikt.graphitron.rewrite.schema.input.SchemaInput(
                no.sikt.graphitron.rewrite.schema.input.SchemaSource.file(file),
                java.util.Optional.of("t"), java.util.Optional.empty()),
            no.sikt.graphitron.rewrite.schema.input.SchemaInput.named("a-bare-label"));

        assertRecipeRoundTrips(tmp, no.sikt.graphitron.rewrite.schema.input.SchemaRecipe
            .literalOver(inputs, List.of(".graphqls")));
    }

    /**
     * Captures {@code recipe} under a graph and compares the context's own input list against that
     * list round-tripped through the rows. The tier has no mojo, so the fixture mints the recipe
     * directly and derives the inputs from its expansion, the same pairing the build mojo makes.
     */
    private static void assertRecipeRoundTrips(
            Path tmp, no.sikt.graphitron.rewrite.schema.input.SchemaRecipe recipe) {
        var expansion = recipe.expand(tmp);
        assertThat(expansion).isInstanceOf(
            no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.Expansion.Resolved.class);
        var inputs = ((no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.Expansion.Resolved) expansion)
            .matches().stream()
            .map(no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.Expansion.Match::input)
            .toList();

        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.of(recipe),
                CapturedStore.registryOf(tmp, "type Query { ping: String }"),
                CapturedStore.attributionOf(tmp));

            var remembered = StoredRecipe.decode(store.dsl(), "FactCaptureAgreementTest")
                .orElseThrow(() -> new AssertionError("the run's own graph has no anchor row"));
            var replayed = remembered.expand(tmp);
            assertThat(replayed).isInstanceOf(
                no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.Expansion.Resolved.class);
            assertThat(((no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.Expansion.Resolved) replayed)
                .matches().stream()
                .map(no.sikt.graphitron.rewrite.schema.input.SchemaRecipe.Expansion.Match::input)
                .toList())
                .as("the run's schema inputs, against the same value round-tripped through its rows")
                .isEqualTo(inputs);
            assertThat(remembered.extensions())
                .as("the effective extension filter round-trips with the entries")
                .isEqualTo(recipe.extensions());
        }
    }

    /**
     * The configuration family's equality anchor: every parameter the run held is readable back out
     * of the rows as the run's own value, and every parameter it did not hold left no row. The
     * absences are half the claim, because a nullable column or a synthesised default would satisfy
     * a presence-only check while minting the derived fact that can disagree.
     */
    @Test
    @DisplayName("the configuration family equals the run's own resolved configuration")
    void theConfigurationFamilyEqualsTheRunsConfiguration(@TempDir Path tmp) {
        var output = new FactCapture.OutputCoordinates("com.example.out", "com.example.jooq",
            tmp.resolve("target/generated-sources"));
        var lint = new no.sikt.graphitron.rewrite.lint.LintConfig(
            Set.of("rule-a"), List.of("Legacy*", "Deprecated*"));
        var session = no.sikt.graphitron.rewrite.session.SessionStateConfig.from(
            "com.example.db.Routines#connect", "com.example.db.Routines#disconnect");
        var config = new FactCapture.SubjectConfig(java.util.Optional.empty(),
            java.util.Optional.of("checkout-supergraph"), java.util.Optional.of(output),
            java.util.Optional.of("tenant_id"), lint, session);

        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), config,
                CapturedStore.registryOf(tmp, "type Query { ping: String }"),
                CapturedStore.attributionOf(tmp));
            var dsl = store.dsl();

            assertThat(dsl.select(STORE_GRAPH_SUPERGRAPH.SUPERGRAPH_NAME).from(STORE_GRAPH_SUPERGRAPH)
                .fetch(0, String.class)).containsExactly("checkout-supergraph");
            assertThat(dsl.selectFrom(STORE_GRAPH_OUTPUT).fetchSingle())
                .extracting(r -> r.getOutputPackage(), r -> r.getJooqPackage(), r -> r.getOutputDirectory())
                .containsExactly("com.example.out", "com.example.jooq",
                    output.outputDirectory().toString());
            assertThat(dsl.select(STORE_GRAPH_TENANT_COLUMN.COLUMN_NAME).from(STORE_GRAPH_TENANT_COLUMN)
                .fetch(0, String.class)).containsExactly("tenant_id");
            assertThat(dsl.select(STORE_GRAPH_LINT_DISABLED_RULE.RULE_ID)
                .from(STORE_GRAPH_LINT_DISABLED_RULE).fetch(0, String.class))
                .containsExactlyInAnyOrderElementsOf(lint.disabledRuleIds());
            assertThat(dsl.select(STORE_GRAPH_LINT_EXCLUDED_TYPE.TYPE_PATTERN)
                .from(STORE_GRAPH_LINT_EXCLUDED_TYPE)
                .orderBy(STORE_GRAPH_LINT_EXCLUDED_TYPE.ORDINAL).fetch(0, String.class))
                .as("the list half keeps the author's order; the set half has none to keep")
                .isEqualTo(lint.excludedTypePatterns());
            assertThat(dsl.select(STORE_GRAPH_SESSION_MOUNT.MOUNT_METHOD).from(STORE_GRAPH_SESSION_MOUNT)
                .fetch(0, String.class))
                .as("only the authored string lands; the reflected signature is a model fact")
                .containsExactly("com.example.db.Routines#connect");
            assertThat(dsl.select(STORE_GRAPH_SESSION_UNMOUNT.UNMOUNT_METHOD).from(STORE_GRAPH_SESSION_UNMOUNT)
                .fetch(0, String.class)).containsExactly("com.example.db.Routines#disconnect");
            assertThat(dsl.fetchCount(STORE_GRAPH_SCHEMA_INPUT))
                .as("a subject with no recipe writes no recipe rows rather than an empty one").isZero();
        }
    }

    /**
     * The family's absences, stated as absences. Every optional parameter the run did not hold leaves
     * no row, and the two arms that are alternations leave the arm's own relation empty rather than a
     * row carrying a synthesised value.
     */
    @Test
    @DisplayName("a run that declared nothing writes no configuration rows at all")
    void aRunThatDeclaredNothingWritesNoConfigurationRows(@TempDir Path tmp) {
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(tmp, "type Query { ping: String }"),
                CapturedStore.attributionOf(tmp));
            var dsl = store.dsl();

            assertThat(dsl.fetchCount(STORE_GRAPH_SUPERGRAPH))
                .as("no declaration means standalone, and standalone means no row").isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_OUTPUT))
                .as("a run with no output coordinates writes none, not the package sentinel").isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_TENANT_COLUMN)).isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_LINT_DISABLED_RULE)).isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_LINT_EXCLUDED_TYPE)).isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_SESSION_MOUNT))
                .as("the no-configuration arm is the missing row").isZero();
            assertThat(dsl.fetchCount(STORE_GRAPH_SESSION_UNMOUNT)).isZero();
        }
    }

    /**
     * The mount-only configuration: row presence is the fact on both relations, so omitting
     * {@code <unmount>} is a mount row with no unmount row, per the family's absence rule; no
     * opt-out marker exists any more.
     */
    @Test
    @DisplayName("a mount-only configuration is a mount row with no unmount row")
    void aMountOnlyConfigurationIsAMountRowWithNoUnmountRow(@TempDir Path tmp) {
        var mountOnly = no.sikt.graphitron.rewrite.session.SessionStateConfig.from(
            "com.example.KernelIdentity#mount", null);
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), graph(tmp), withSessionState(mountOnly),
                CapturedStore.registryOf(tmp, "type Query { ping: String }"),
                CapturedStore.attributionOf(tmp));

            assertThat(store.dsl().select(STORE_GRAPH_SESSION_MOUNT.MOUNT_METHOD)
                .from(STORE_GRAPH_SESSION_MOUNT).fetch(0, String.class))
                .containsExactly("com.example.KernelIdentity#mount");
            assertThat(store.dsl().fetchCount(STORE_GRAPH_SESSION_UNMOUNT))
                .as("the supported mount-only configuration is the unmount relation's missing row")
                .isZero();
        }
    }

    private static FactCapture.SubjectConfig withSessionState(
            no.sikt.graphitron.rewrite.session.SessionStateConfig sessionState) {
        return new FactCapture.SubjectConfig(java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(),
            no.sikt.graphitron.rewrite.lint.LintConfig.empty(), sessionState);
    }

    /**
     * A registry with no user schema in it, for the two catalog-side anchors. The bundled
     * directives still parse, which is what keeps the SDL families non-empty without adding
     * fixture noise to a catalog comparison.
     */
    private static graphql.schema.idl.TypeDefinitionRegistry emptyRegistry(Path tmp) {
        return CapturedStore.registryOf(tmp, "type Query { ping: String }");
    }

    /** The graph these anchors capture under; one per store, so joins stay within one run's rows. */
    private static FactCapture.GraphIdentity graph(Path tmp) {
        return new FactCapture.GraphIdentity("FactCaptureAgreementTest", tmp);
    }
}
