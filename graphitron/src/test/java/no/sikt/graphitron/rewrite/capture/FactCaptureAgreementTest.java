package no.sikt.graphitron.rewrite.capture;

import graphql.language.Directive;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeDefinition;
import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.JooqCatalog;
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
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_SITE;
import static no.sikt.graphitron.model.Tables.CATALOG_COLUMN;
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
            FactCapture.capture(store.dsl(), emptyRegistry(tmp), facts, List.of());

            var capturedTables = Set.copyOf(store.dsl()
                .select(CATALOG_TABLE.TABLE_SCHEMA.concat(".").concat(CATALOG_TABLE.TABLE_NAME))
                .from(CATALOG_TABLE).fetch(0, String.class));
            assertThat(capturedTables).isEqualTo(facts.tablesByQualifiedName().keySet());

            var capturedColumns = store.dsl().fetchCount(CATALOG_COLUMN);
            int expected = facts.tablesByQualifiedName().values().stream()
                .mapToInt(t -> t.columns().size()).sum();
            assertThat(capturedColumns).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("the extension method census equals the scanner's, compared descriptor-erased")
    void extensionMethodCensusEqualsTheScanner(@TempDir Path tmp) {
        var ctx = testContext();
        List<CompletionData.ExternalReference> extensions = CatalogBuilder.buildExternalReferences(ctx);
        try (var store = GraphitronModelStore.open()) {
            FactCapture.capture(store.dsl(), emptyRegistry(tmp), CatalogFacts.empty(), extensions);

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
