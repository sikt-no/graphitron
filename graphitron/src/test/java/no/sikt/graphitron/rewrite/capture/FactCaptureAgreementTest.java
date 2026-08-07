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
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.schema.federation.KeyNodeSynthesiser;
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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DECLARATION_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_SITE;
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

    /** A federated slice: one node with an authored key alternative, one without any. */
    private static final String FEDERATED_FIXTURE = """
        directive @link(url: String!, import: [String]) repeatable on SCHEMA
        directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT

        extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

        type Query { film: Film, language: Language }

        interface Node { id: ID! }

        type Film implements Node @node @key(fields: "title") {
          id: ID!
          title: String
        }

        type Language implements Node @node {
          id: ID!
          name: String
        }
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
     */
    @Test
    @DisplayName("synthesized federation keys agree with the registry rewrite's")
    void federationKeySynthesisAgreesWithTheRewrite(@TempDir Path tmp) {
        var nodes = new NodeDeclaration(null);
        try (var store = CapturedStore.of(tmp, FEDERATED_FIXTURE)) {
            var captured = new LinkedHashSet<String>();
            store.dsl()
                .select(GRAPHITRON_FEDERATION_KEY.TYPE_NAME, GRAPHITRON_FEDERATION_KEY.FIELDS_SDL)
                .from(GRAPHITRON_FEDERATION_KEY)
                .fetch()
                .forEach(row -> captured.add(row.value1() + "|" + row.value2()));

            var rewritten = CapturedStore.registryOf(tmp, FEDERATED_FIXTURE);
            KeyNodeSynthesiser.apply(rewritten, nodes);
            var expected = new LinkedHashSet<String>();
            for (TypeDefinition<?> definition : rewritten.types().values()) {
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
