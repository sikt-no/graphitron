package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * An inline (non-{@code @splitQuery}) {@code @reference} list field reads its filter arguments
 * off its own {@code SelectedField}, not the ancestor fetcher's {@code env}. Behaviour is pinned at
 * the execution tier ({@code GraphQLQueryTest}); this pipeline tier pins the structural consequence
 * that keeps the {@code -Werror} consumer compile green: the {@code $fields} host stamps
 * {@code @SuppressWarnings("unchecked")} exactly when an inline filter arg emits an unchecked cast
 * under the {@code FromSelectedField} source (a list-typed arg), and not otherwise. Asserts on the
 * generated {@link MethodSpec}'s annotations, never on body strings (banned at every tier).
 *
 * <p>Most cases use the {@code nodeidfixture} jOOQ catalog so the composite-key {@code Bar} NodeType
 * (PK {@code (id_1, id_2)}, FK {@code bar_id_1_fkey} → {@code baz}) is reachable from SDL, matching
 * {@link NodeIdReferenceFilterPipelineTest}. The {@code JooqConvert}+list case uses the default
 * (Sakila) catalog instead: that arm needs a top-level {@code [ID!] @field} column arg over a
 * <em>non-{@code @node}</em> reference target ({@code store → customer}), which the all-{@code @node}
 * nodeidfixture cannot express (the id-reference shim reroutes {@code [ID!] @field} on a NodeType
 * table to a same-table {@code @nodeId}, never reaching {@code JooqConvert}).
 */
@PipelineTier
class InlineFilterArgumentSourcePipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    // A list-typed implicit column filter (bar.name IN (names)) — a GeneratedConditionFilter whose
    // call param is a list-typed Direct extraction, which casts to (List) under FromSelectedField.
    private static final String LIST_FILTER_INPUT = """
        input BarListFilter @table(name: "bar") {
            names: [String!] @field(name: "name")
        }
        """;

    // A scalar implicit column filter (bar.name = name) — a Direct extraction that casts to the
    // reifiable (String) under FromSelectedField (checked), so it needs no suppression.
    private static final String SCALAR_FILTER_INPUT = """
        input BarScalarFilter @table(name: "bar") {
            name: String @field(name: "name")
        }
        """;

    private static TypeSpec generateBazType(String extraSdl) {
        var schema = TestSchemaHelper.buildSchema(extraSdl, FIXTURE_CTX);
        return TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Baz")).findFirst().orElseThrow();
    }

    private static MethodSpec fieldsMethod(TypeSpec type) {
        // The unchecked casts live in the private $fieldsGrouped switch loop (the narrowest
        // enclosing member); the public $fields entries are pure delegations and stay unstamped.
        return type.methodSpecs().stream()
            .filter(m -> m.name().equals("$fieldsGrouped")).findFirst().orElseThrow();
    }

    private static boolean stampsUncheckedSuppression(MethodSpec method) {
        return method.annotations().stream()
            .filter(a -> a.type().toString().equals("java.lang.SuppressWarnings"))
            .flatMap(a -> a.members().getOrDefault("value", List.of()).stream())
            // The value member renders as the quoted literal "unchecked"; assert the value, not just
            // the annotation's presence, so a future @SuppressWarnings with some other reason does
            // not pass this as the unchecked-cast suppression.
            .anyMatch(v -> v.toString().equals("\"unchecked\""));
    }

    @Test
    void inlineReferenceFilter_listArg_stampsUncheckedSuppressionOnFieldsMethod() {
        // A list-typed @condition filter arg extracts as an unchecked (List<X>) cast under the
        // FromSelectedField source, so the $fields host must carry @SuppressWarnings("unchecked").
        var baz = generateBazType(LIST_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarListFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """);

        assertThat(stampsUncheckedSuppression(fieldsMethod(baz))).isTrue();
    }

    @Test
    void inlineReferenceFilter_scalarArgOnly_doesNotStampUncheckedSuppression() {
        // A scalar @condition filter arg casts to a reifiable type (checked), so no suppression is
        // needed — pins that the stamp is source-aware and not a blanket widening.
        var baz = generateBazType(SCALAR_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID!
                bars(filter: BarScalarFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """);

        assertThat(stampsUncheckedSuppression(fieldsMethod(baz))).isFalse();
    }

    // A top-level JooqConvert+list filter arg: a direct [ID!] @field(name: "store_id") column arg on
    // an inline @reference list field over a non-@node target (store → customer). The ID wire type
    // coerces onto the int store_id column via jOOQ Convert, so the arm reads a pre-lifted
    // `<name>Keys` local that emitJooqConvertKeyLifts declares (routed through the SelectedField under
    // FromSelectedField), casting `sf.getArguments().get("storeIds")` to (List<String>) — unchecked,
    // so the $fields host stamps @SuppressWarnings. Sakila catalog: see the class doc for why the
    // nodeidfixture cannot express this shape.
    private static final String JOOQ_CONVERT_LIST_SDL = """
        type Customer @table(name: "customer") { customerId: Int @field(name: "customer_id") }
        type Store @table(name: "store") {
            storeId: Int @field(name: "store_id")
            customersByStoreId(storeIds: [ID!] @field(name: "store_id")): [Customer!]!
                @reference(path: [{key: "customer_store_id_fkey"}])
        }
        type Query { store: Store }
        """;

    @Test
    void inlineReferenceFilter_jooqConvertListArg_stampsUncheckedSuppressionOnFieldsMethod() {
        // Pins the emitJooqConvertKeyLifts pre-lift (a latent compile-breaking defect the SelectedField
        // routing cleared: the JooqConvert list arm previously referenced an undeclared <name>Keys
        // local). Generation must succeed and
        // the $fields host must stamp @SuppressWarnings("unchecked") for the pre-lift's (List<String>)
        // cast.
        var schema = TestSchemaHelper.buildSchema(JOOQ_CONVERT_LIST_SDL);

        // The arg lowers to a top-level JooqConvert+list callParam (not a nested-input leaf, whose
        // list form inlines its own stream and never reaches emitJooqConvertKeyLifts) — pin that so
        // the shape cannot silently degrade off the arm under test.
        var field = schema.field("Store", "customersByStoreId");
        assertThat(field).isInstanceOf(ChildField.TableField.class);
        var callParams = ((GeneratedConditionFilter) ((ChildField.TableField) field).filters().get(0)).callParams();
        assertThat(callParams).singleElement().satisfies(p -> {
            assertThat(p.extraction()).isInstanceOf(CallSiteExtraction.JooqConvert.class);
            assertThat(p.list()).isTrue();
        });

        var store = TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Store")).findFirst().orElseThrow();
        assertThat(stampsUncheckedSuppression(fieldsMethod(store))).isTrue();
    }

    @Test
    void inlineReferenceFilter_jooqConvertListArg_generatesEndToEndWithoutThrowing() {
        // The full classify + generate path (both type class and fetcher class) succeeds for the
        // inline JooqConvert+list shape.
        assertThatCode(() -> {
            var schema = TestSchemaHelper.buildSchema(JOOQ_CONVERT_LIST_SDL);
            TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
            TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        }).doesNotThrowAnyException();
    }

    @Test
    void inlineReferenceFilter_listArg_generatesEndToEndWithoutThrowing() {
        // The full classify + generate path succeeds for the inline list-filter shape.
        assertThatCode(() -> {
            var schema = TestSchemaHelper.buildSchema(LIST_FILTER_INPUT + """
                type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
                type Baz implements Node @table(name: "baz") @node {
                    id: ID!
                    bars(filter: BarListFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
                }
                type Query { baz: Baz }
                """, FIXTURE_CTX);
            TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
            TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        }).doesNotThrowAnyException();
    }
}
