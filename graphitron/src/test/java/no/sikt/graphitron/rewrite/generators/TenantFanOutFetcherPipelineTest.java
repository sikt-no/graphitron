package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronFacadeGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline coverage for the fanned-fetcher emission (fields classified
 * {@link no.sikt.graphitron.rewrite.model.TenantBinding.FanOut}) and the factory's dedicated
 * fan-out tenant-collection parameter, as {@code TypeSpec}-structure assertions; the collapse
 * behaviour (null placement, error path, union order) is pinned at the execution tier per the
 * behaviour-above-pipeline discipline. Fixtures ride the real catalog with {@code film_id} as
 * the tenant column, mirroring {@link TenantRoutedFetcherPipelineTest}.
 */
@PipelineTier
class TenantFanOutFetcherPipelineTest {

    private static GraphitronSchema multiTenant(String sdl) {
        return TestSchemaHelper.buildSchema(
            sdl, TestConfiguration.testContext().withTenantColumn("film_id"));
    }

    private static String render(GraphitronSchema schema, String className, String methodName) {
        TypeSpec spec = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> className.equals(t.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no generated class named " + className));
        return spec.methodSpecs().stream()
            .filter(m -> methodName.equals(m.name()))
            .findFirst()
            .map(MethodSpec::toString)
            .orElseThrow(() -> new AssertionError(
                "no method " + methodName + " on " + className + "; has: "
                    + spec.methodSpecs().stream().map(MethodSpec::name).toList()));
    }

    private static String renderFacade(GraphitronSchema schema) {
        return GraphitronFacadeGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> GraphitronFacadeGenerator.CLASS_NAME.equals(t.name()))
            .findFirst()
            .orElseThrow()
            .toString();
    }

    @Test
    void fannedRootFieldScattersItsOwnStatementAndCollapsesTheUnion() {
        var schema = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] @tenantFanOut }
            """);

        var films = render(schema, "QueryFetchers", "films");
        assertThat(films)
            // Domain computation and the scatter call live in the carrier's helpers; the fetcher
            // hands over the field's ordinary statement as the per-tenant unit of work.
            .contains("fake.code.generated.schema.TenantConnections.collapseFanOut(env,")
            .contains("fake.code.generated.schema.TenantConnections.fanOutRows(env, dsl -> dsl")
            .contains(".select(fake.code.generated.types.Film.$fields(env.getSelectionSet(), filmTable, env))")
            .contains(".where(condition)")
            .contains(".orderBy(orderBy)")
            // No single-DSL acquisition and no single hand-down local: tenants ride per element.
            .doesNotContain("getDslContext(env)")
            .doesNotContain("_divinedTenant");
    }

    @Test
    void fannedChildOfUntenantedParentForcesTheFetcherBoundary_batchedForm() {
        // No @splitQuery in the SDL: the marker itself forces the boundary, so the field
        // classifies batched and the rows method scatters once per parent batch.
        var schema = multiTenant("""
            type Language @table(name: "language") {
                name: String
                films: [Film] @reference(path: [{key: "film_language_id_fkey"}]) @tenantFanOut
            }
            type Film @table(name: "film") { title: String }
            type Query { languages: [Language!]! }
            """);

        var fetcher = render(schema, "LanguageFetchers", "films");
        assertThat(fetcher)
            // The fanned batched fetcher registers under the bare path name (parents are
            // untenanted; the fan-out happens inside the batch load) and collapses each parent's
            // marker-bearing list against that parent's own env.
            .contains("fake.code.generated.schema.TenantConnections.loaderName(env)")
            .contains(".thenApply(payload -> fake.code.generated.schema.TenantConnections.collapseFanOut(env, payload))")
            .doesNotContain("tenantLoaderName");

        var rows = render(schema, "LanguageFetchers", "rowsFilms");
        assertThat(rows)
            // One scatter per parent batch, one statement per tenant per batch: the batch
            // statement is the perTenant unit of work, and no per-method DSL declaration exists.
            .contains("fake.code.generated.schema.TenantConnections.fanOutBatchRows(env, keys.size(), dsl -> {")
            .contains("return scatterByIdx(flat, keys.size());")
            .doesNotContain("org.jooq.DSLContext dsl =");
    }

    @Test
    void factoriesGainTheTenantCollectionParameterExactlyWhenAFannedFieldExists() {
        var fanned = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] @tenantFanOut }
            """);

        var facade = renderFacade(fanned);
        assertThat(facade)
            // Both factory forms carry the dedicated typed slot (missing or mis-typed is a
            // compile error at the call site), null-checked like every factory parameter, and
            // stashed under the carrier's own graphitron-owned key.
            .contains("newExecutionInput(org.jooq.DSLContext defaultDsl,\n"
                + "      java.util.Collection<java.lang.Integer> fanOutTenants)")
            .contains("newOwnedExecutionInput(java.lang.String claims,\n"
                + "      java.util.Collection<java.lang.Integer> fanOutTenants)")
            .contains("java.util.Objects.requireNonNull(fanOutTenants, \"fanOutTenants\")")
            .contains("b.put(fake.code.generated.schema.TenantConnections.FAN_OUT_TENANTS_KEY, fanOutTenants);");
    }

    @Test
    void factoriesOmitTheTenantCollectionParameterWithoutAFannedField() {
        var unfanned = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);
        assertThat(renderFacade(unfanned)).doesNotContain("fanOutTenants");

        var singleTenant = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { allFilms: [Film!]! }
            """);
        assertThat(renderFacade(singleTenant)).doesNotContain("fanOutTenants");
    }
}
