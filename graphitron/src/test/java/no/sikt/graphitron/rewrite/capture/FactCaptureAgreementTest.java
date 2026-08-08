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
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.model.ConnectionSynthesis;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.CATALOG_COLUMN;
import static no.sikt.graphitron.model.Tables.CATALOG_KEY;
import static no.sikt.graphitron.model.Tables.CATALOG_TABLE;
import static no.sikt.graphitron.model.Tables.EXTENSION_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow period's honesty check: the store is filled beside the live pipeline and nobody reads
 * it, so nothing but a test can notice capture drifting from the model it shadows.
 *
 * <p>The driver is mechanical. It enumerates the generated jOOQ relations and fails on any one
 * without a registered agreement source, so a relation added to the DDL cannot arrive unchecked.
 * Registrations form a closed set of three arms, which is why there is no skip list:
 * <ul>
 *   <li>{@link Arm#CONTAINMENT} for the SDL side. Capture is total and {@code GraphitronSchema} is
 *       reachability-pruned, so the honest relation is that the store contains the model.</li>
 *   <li>{@link Arm#EQUALITY} for the catalog and scanner censuses, which are the same walk reduced
 *       two ways.</li>
 *   <li>{@link Arm#DERIVED} for shipped views, which register the base relations they project so
 *       their agreement is vacuous by construction. Later derivation strata land as registrations
 *       here, not as exemptions.</li>
 * </ul>
 *
 * <p>These tests retire as consumers migrate off {@code GraphitronSchema} piece by piece; they pin
 * a shadow copy, and a shadow with a reader does not need one.
 */
@PipelineTier
class FactCaptureAgreementTest {

    /** How a relation's contents are pinned to the model it shadows. */
    private enum Arm { CONTAINMENT, EQUALITY, DERIVED }

    private static final Map<String, Arm> REGISTRATIONS = registrations();

    private static Map<String, Arm> registrations() {
        var registrations = new LinkedHashMap<String, Arm>();
        for (String relation : List.of(
            "graphql_type", "graphql_type_declaration", "graphql_field", "graphql_argument",
            "graphql_enum_value", "graphql_union_member", "graphql_implements",
            "graphql_root_operation", "graphql_duplicate_declaration", "graphql_directive",
            "graphql_directive_location", "graphql_directive_argument", "graphql_schema_directive",
            "graphql_schema_directive_arg", "graphql_type_directive", "graphql_type_directive_arg",
            "graphql_field_directive", "graphql_field_directive_arg", "graphql_argument_directive",
            "graphql_argument_directive_arg", "graphql_enum_value_directive",
            "graphql_enum_value_directive_arg", "graphitron_table", "graphitron_field_binding",
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
            "graphitron_external_field", "graphitron_source_row", "graphitron_connection", "graphitron_facet",
            "graphitron_order_by", "graphitron_order", "graphitron_order_field", "graphitron_index",
            "graphitron_default_order", "graphitron_default_order_field", "graphitron_mutation",
            "graphitron_error", "graphitron_error_handler", "graphitron_node", "graphitron_node_key_column",
            "graphitron_field_node_id", "graphitron_argument_node_id", "graphitron_argument_lookup_key",
            "graphitron_field_lookup_key", "graphitron_split_query", "graphitron_tenant_fan_out",
            "graphitron_pivot", "graphitron_routine", "graphitron_routine_arg_mapping_pair",
            "graphitron_routine_column_mapping_pair", "graphitron_discriminate", "graphitron_discriminator",
            "graphitron_federation_key", "graphitron_federation_key_field", "graphitron_link",
            "graphitron_link_import", "graphitron_multitable_reference", "graphitron_record",
            "graphitron_undecoded_argument", "graphitron_type_declaration_synthesis",
            "graphitron_field_synthesis", "graphitron_type_directive_synthesis")) {
            registrations.put(relation, Arm.CONTAINMENT);
        }
        for (String relation : List.of(
            "catalog_table", "catalog_column", "catalog_key", "catalog_key_column",
            "catalog_foreign_key", "catalog_foreign_key_column", "catalog_index",
            "catalog_index_column", "extension_class", "extension_method",
            "extension_method_parameter", "extension_record_component",
            "extension_scalar_constant")) {
            registrations.put(relation, Arm.EQUALITY);
        }
        registrations.put("graphql_directive_site", Arm.DERIVED);
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
     * A federated slice with one of each outcome: an authored key alternative that synthesis still
     * numbers after, a node with no key at all, and an authored id key that stands synthesis down.
     * The {@code @link} is the author's; the {@code @key} declaration arrives through the pipeline's
     * own federation import, which is the point of running this fixture through the pipeline.
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
     * pipeline's assembly runs, and the walk macro capture runs, at different stages over different
     * representations. Neither can call the other without inverting the pipeline's ordering, so
     * this anchor is what keeps them from drifting. It retires with the rewrite's last consumer.
     *
     * <p>Both sides come out of one pipeline run rather than out of two registries the test
     * assembles: the expectation is the registry the rewrite mutated, the comparison is the store
     * filled from the handle production hands capture. That is what makes the reading position part
     * of what this pins. Capture reading the mutated registry finds the keys already there, agrees
     * on this set for the wrong reason, and shows it by minting no provenance, which is the second
     * assertion.
     */
    @Test
    @DisplayName("synthesized federation keys agree with the registry rewrite's, off one pipeline run")
    void federationKeySynthesisAgreesWithTheRewrite(@TempDir Path tmp) {
        try (var store = CapturedStore.ofPipeline(tmp, FEDERATED_FIXTURE)) {
            var captured = new LinkedHashSet<String>();
            store.dsl()
                .select(GRAPHITRON_FEDERATION_KEY.TYPE_NAME, GRAPHITRON_FEDERATION_KEY.FIELDS_SDL)
                .from(GRAPHITRON_FEDERATION_KEY)
                .fetch()
                .forEach(row -> captured.add(row.value1() + "|" + row.value2()));

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
            assertThat(captured).isEqualTo(expected);

            // The authored picture is the anti-join, so the macro has to be what put the unauthored
            // keys there. Film carries an alternative and Language carries nothing, so both are
            // synthesized; Actor's authored id key stands synthesis down on both implementations.
            assertThat(store.dsl()
                .select(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS.TYPE_NAME)
                .from(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS)
                .where(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS.MACRO.eq("FEDERATION_KEY"))
                .fetch(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS.TYPE_NAME))
                .containsExactlyInAnyOrder("Film", "Language");

            // A rewrite-built directive carries no source location, so a key captured off the
            // rewritten registry would transcribe unlocated. The macro's inherits the declaration.
            assertThat(store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE.SOURCE_LINE)
                .from(GRAPHQL_TYPE_DIRECTIVE)
                .where(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME.eq("Language"))
                .and(GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.eq("key"))
                .fetch(GRAPHQL_TYPE_DIRECTIVE.SOURCE_LINE))
                .doesNotContainNull();
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
     * return type was rewritten; the store keeps the authored expression, and the two have to be
     * talking about the same set of carriers.
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
    @DisplayName("the catalog table and column census equals CatalogFacts")
    void catalogCensusEqualsCatalogFacts(@TempDir Path tmp) {
        var ctx = testContext();
        var facts = CatalogBuilder.buildCatalogFacts(new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()));
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), emptyRegistry(tmp), facts, List.of(), new NodeDeclaration(null));

            var capturedTables = Set.copyOf(store.dsl()
                .select(CATALOG_TABLE.TABLE_SCHEMA.concat(".").concat(CATALOG_TABLE.TABLE_NAME))
                .from(CATALOG_TABLE).fetch(0, String.class));
            assertThat(capturedTables).isEqualTo(facts.tablesByQualifiedName().keySet());

            var capturedJavaNames = store.dsl()
                .select(CATALOG_TABLE.TABLE_SCHEMA.concat(".").concat(CATALOG_TABLE.TABLE_NAME),
                    CATALOG_TABLE.JAVA_NAME)
                .from(CATALOG_TABLE)
                .fetch()
                .intoMap(r -> r.value1(), r -> r.value2());
            assertThat(capturedJavaNames).isEqualTo(facts.tablesByQualifiedName().values().stream()
                .collect(java.util.stream.Collectors.toMap(
                    CatalogFacts.Table::qualifiedName, CatalogFacts.Table::javaName)));

            var capturedColumns = store.dsl().fetchCount(CATALOG_COLUMN);
            int expected = facts.tablesByQualifiedName().values().stream()
                .mapToInt(t -> t.columns().size()).sum();
            assertThat(capturedColumns).isEqualTo(expected);
        }
    }

    /**
     * A gate rather than an agreement, homed here because it needs a real catalog to have anything
     * to range over. Uniqueness constraints all land in one relation with the primary key flagged,
     * which only reads unambiguously while a table has at most one flagged row; more than one is a
     * capture bug, since the DDL can key the constraint but not count the flag.
     */
    @Test
    @DisplayName("no catalog table carries two primary keys")
    void atMostOnePrimaryKeyPerTable(@TempDir Path tmp) {
        var ctx = testContext();
        var facts = CatalogBuilder.buildCatalogFacts(new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()));
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), emptyRegistry(tmp), facts, List.of(), new NodeDeclaration(null));
            assertThat(store.dsl().fetchCount(CATALOG_KEY, CATALOG_KEY.IS_PRIMARY.isTrue()))
                .as("the catalog has primary keys, so this pins something")
                .isPositive();
            var offenders = store.dsl()
                .select(CATALOG_KEY.TABLE_SCHEMA, CATALOG_KEY.TABLE_NAME)
                .from(CATALOG_KEY)
                .where(CATALOG_KEY.IS_PRIMARY.isTrue())
                .groupBy(CATALOG_KEY.TABLE_SCHEMA, CATALOG_KEY.TABLE_NAME)
                .having(org.jooq.impl.DSL.count().gt(1))
                .fetch();
            assertThat(offenders).as("tables with more than one primary key").isEmpty();
        }
    }

    @Test
    @DisplayName("the extension method census equals the scanner's, compared descriptor-erased")
    void extensionMethodCensusEqualsTheScanner(@TempDir Path tmp) {
        var ctx = testContext();
        List<CompletionData.ExternalReference> extensions = CatalogBuilder.buildExternalReferences(ctx);
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), emptyRegistry(tmp), CatalogFacts.empty(), extensions, new NodeDeclaration(null));

            var captured = new LinkedHashSet<String>();
            store.dsl().select(EXTENSION_METHOD.CLASS_NAME, EXTENSION_METHOD.METHOD_NAME)
                .from(EXTENSION_METHOD).fetch()
                .forEach(row -> captured.add(row.value1() + "#" + row.value2()));

            // Descriptor-erased: CompletionData.Method carries no descriptor, so the comparison is
            // a projection rather than a mirror and the driver compares it as one.
            var expected = new LinkedHashSet<String>();
            extensions.forEach(reference -> reference.methods()
                .forEach(method -> expected.add(reference.className() + "#" + method.name())));
            assertThat(captured).isEqualTo(expected);
        }
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
     * A registry with no user schema in it, for the two catalog-side anchors. The bundled
     * directives still parse, which is what keeps the SDL families non-empty without adding
     * fixture noise to a catalog comparison.
     */
    private static graphql.schema.idl.TypeDefinitionRegistry emptyRegistry(Path tmp) {
        return CapturedStore.registryOf(tmp, "type Query { ping: String }");
    }
}
