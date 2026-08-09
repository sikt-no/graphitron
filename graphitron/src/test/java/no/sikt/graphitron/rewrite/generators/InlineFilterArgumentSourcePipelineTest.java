package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.ProjectionRenderTestSupport;
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

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * An inline (non-{@code @splitQuery}) {@code @reference} list field serves its filter arguments
 * off its own {@code SelectedField}'s argument map, not the ancestor fetcher's {@code env}.
 * Behaviour is pinned at the execution tier ({@code GraphQLQueryTest}); this pipeline tier pins
 * the structural consequence that keeps the {@code -Werror} consumer compile green: the argument
 * extraction (and any unchecked cast it needs) lives in the coordinate's condition glue method,
 * which carries {@code @SuppressWarnings("unchecked")} exactly when a binding local's declaration
 * casts to a non-reifiable target, while the {@code $project} host passes the map through and
 * stays unstamped. Asserts on the generated {@link MethodSpec}'s annotations, never on body
 * strings (banned at every tier).
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
        List.of(), Path.of(""), "InlineFilterArgumentSourcePipelineTest", Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE
    );

    // A list-typed implicit column filter (bar.name IN (names)) — a GeneratedConditionFilter whose
    // call param is a list-typed Direct extraction, whose glue binding local casts to List<String>.
    private static final String LIST_FILTER_INPUT = """
        input BarListFilter {
            names: [String!] @field(name: "name")
        }
        """;

    // A scalar implicit column filter (bar.name = name) — a Direct extraction whose glue binding
    // local casts to the reifiable String (checked), so its glue method needs no suppression.
    private static final String SCALAR_FILTER_INPUT = """
        input BarScalarFilter {
            name: String @field(name: "name")
        }
        """;

    private static MethodSpec glueMethod(String extraSdl, String className, String methodName) {
        var schema = TestSchemaHelper.buildSchema(extraSdl, FIXTURE_CTX);
        return no.sikt.graphitron.rewrite.ConditionRenderTestSupport
            .renderCommittedConditions(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals(className)).findFirst().orElseThrow()
            .methodSpecs().stream()
            .filter(m -> m.name().equals(methodName)).findFirst().orElseThrow();
    }

    private static MethodSpec fieldsMethod(TypeSpec type) {
        return type.methodSpecs().stream()
            .filter(m -> m.name().equals("$project")).findFirst().orElseThrow();
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

    private static final String LIST_FILTER_SDL = LIST_FILTER_INPUT + """
        type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
        type Baz implements Node @table(name: "baz") @node {
            id: ID! @nodeId
            bars(filter: BarListFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
        }
        type Query { baz: Baz }
        """;

    @Test
    void inlineReferenceFilter_listArg_stampsUncheckedSuppressionOnGlueMethod() {
        // The (List<String>) binding-local cast is glue's, so the suppression is glue's; the
        // $project host only passes sf.getArguments() and must stay unstamped.
        assertThat(stampsUncheckedSuppression(
            glueMethod(LIST_FILTER_SDL, "BazConditions", "barsCondition"))).isTrue();

        var schema = TestSchemaHelper.buildSchema(LIST_FILTER_SDL, FIXTURE_CTX);
        var baz = ProjectionRenderTestSupport.renderProjections(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Baz")).findFirst().orElseThrow();
        assertThat(stampsUncheckedSuppression(fieldsMethod(baz))).isFalse();
    }

    @Test
    void inlineReferenceFilter_scalarArgOnly_doesNotStampUncheckedSuppression() {
        // A scalar binding local casts to a reifiable type (checked), so no suppression —
        // pins that the glue stamp is per-method and not a blanket widening.
        var method = glueMethod(SCALAR_FILTER_INPUT + """
            type Bar implements Node @table(name: "bar") @node { id: ID! name: String }
            type Baz implements Node @table(name: "baz") @node {
                id: ID! @nodeId
                bars(filter: BarScalarFilter): [Bar!] @reference(path: [{key: "bar_id_1_fkey"}])
            }
            type Query { baz: Baz }
            """, "BazConditions", "barsCondition");
        assertThat(stampsUncheckedSuppression(method)).isFalse();
    }

    // A top-level JooqConvert+list filter arg: a direct [ID!] @field(name: "store_id") column arg on
    // an inline @reference list field over a non-@node target (store → customer). The ID wire type
    // coerces onto the int store_id column via jOOQ Convert. Sakila catalog: see the class doc for
    // why the nodeidfixture cannot express this shape.
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
    void inlineReferenceFilter_jooqConvertListArg_needsNoSuppressionInGlue() {
        // The arg lowers to a top-level JooqConvert+list callParam (not a nested-input leaf) —
        // pin that so the shape cannot silently degrade off the arm under test.
        var schema = TestSchemaHelper.buildSchema(JOOQ_CONVERT_LIST_SDL);
        var field = schema.field("Store", "customersByStoreId");
        assertThat(field).isInstanceOf(ChildField.TableField.class);
        var callParams = ((GeneratedConditionFilter) ((ChildField.TableField) field).filters().get(0)).callParams();
        assertThat(callParams).singleElement().satisfies(p -> {
            assertThat(p.extraction()).isInstanceOf(CallSiteExtraction.JooqConvert.class);
            assertThat(p.list()).isTrue();
        });

        // The glue's converter arm owns the runtime shape through an instanceof pattern plus
        // DSL.val coercion (no cast at all), so the retired pre-lift's unchecked (List<String>)
        // cast is gone and the glue method stays unstamped.
        var method = no.sikt.graphitron.rewrite.ConditionRenderTestSupport
            .renderCommittedConditions(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("StoreConditions")).findFirst().orElseThrow()
            .methodSpecs().stream()
            .filter(m -> m.name().equals("customersByStoreIdCondition")).findFirst().orElseThrow();
        assertThat(stampsUncheckedSuppression(method)).isFalse();
    }

    @Test
    void inlineReferenceFilter_jooqConvertListArg_generatesEndToEndWithoutThrowing() {
        // The full classify + generate path (both type class and fetcher class) succeeds for the
        // inline JooqConvert+list shape.
        assertThatCode(() -> {
            var schema = TestSchemaHelper.buildSchema(JOOQ_CONVERT_LIST_SDL);
            ProjectionRenderTestSupport.renderProjections(schema, DEFAULT_OUTPUT_PACKAGE);
            TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        }).doesNotThrowAnyException();
    }

    @Test
    void inlineReferenceFilter_listArg_generatesEndToEndWithoutThrowing() {
        // The full classify + generate path succeeds for the inline list-filter shape.
        assertThatCode(() -> {
            var schema = TestSchemaHelper.buildSchema(LIST_FILTER_SDL, FIXTURE_CTX);
            ProjectionRenderTestSupport.renderProjections(schema, DEFAULT_OUTPUT_PACKAGE);
            TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        }).doesNotThrowAnyException();
    }
}
