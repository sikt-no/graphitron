package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.CompilationTier;
import org.dataloader.DataLoaderRegistry;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code Graphitron.newExecutionInput(...)}'s wiring against graphql-java's builder
 * semantics. {@code newExecutionInput} collapses the legacy two-overload shape into a single
 * schema-driven factory whose parameter list reflects the schema's {@code contextArguments} (alphabetical,
 * after {@code DSLContext defaultDsl}). The sakila-example schema declares zero
 * contextArguments today, so the factory collapses to {@code newExecutionInput(DSLContext)}.
 *
 * <ul>
 *   <li>{@code .dataLoaderRegistry(custom)} <em>replaces</em> the factory's fresh registry
 *       rather than merging; this is graphql-java's contract, so passing a custom registry
 *       must win unambiguously.</li>
 *   <li>The sealed {@link GraphitronContext} is stashed under its typed key as a singleton;
 *       the factory IS the wiring point, consumers no longer construct ad-hoc impls.</li>
 * </ul>
 */
@CompilationTier
class NewExecutionInputFactoryTest {

    @Test
    void dataLoaderRegistry_overrideReplacesFactoryDefault() {
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        DataLoaderRegistry custom = new DataLoaderRegistry();
        var input = Graphitron.newExecutionInput(dsl, "test-user")
            .query("{ __typename }")
            .dataLoaderRegistry(custom)
            .build();
        assertThat(input.getDataLoaderRegistry()).isSameAs(custom);
    }

    @Test
    void factoryAttachesEmptyDataLoaderRegistryByDefault() {
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        var input = Graphitron.newExecutionInput(dsl, "test-user").query("{ __typename }").build();
        assertThat(input.getDataLoaderRegistry()).isNotNull();
        assertThat(input.getDataLoaderRegistry().getDataLoaders()).isEmpty();
    }

    @Test
    void factoryStashesSealedSingletonUnderTypedKey() {
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        var input = Graphitron.newExecutionInput(dsl, "test-user").query("{ __typename }").build();
        GraphitronContext ctx = input.getGraphQLContext().get(GraphitronContext.class);
        assertThat(ctx).isSameAs(GraphitronContext.GraphitronContextImpl.INSTANCE);
    }

    @Test
    void factoryStashesDslContextUnderTypedKey() {
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        var input = Graphitron.newExecutionInput(dsl, "test-user").query("{ __typename }").build();
        assertThat(input.getGraphQLContext().<DSLContext>get(DSLContext.class)).isSameAs(dsl);
    }
}
