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
 * Pins {@code Graphitron.newExecutionInput(...)}'s wiring against graphql-java's
 * builder semantics. Two invariants worth catching at build-time rather than
 * leaking into user apps:
 *
 * <ul>
 *   <li>{@code .dataLoaderRegistry(custom)} <em>replaces</em> the factory's
 *       fresh registry rather than merging; this is graphql-java's contract,
 *       so passing a custom registry must win unambiguously.</li>
 *   <li>The {@code (DSLContext)} overload binds the lambda to
 *       {@link GraphitronContext} — exercised here as a compile-time fact
 *       (the call site below would not type-check if the lambda inferred to
 *       {@code Function<DataFetchingEnvironment, DSLContext>}).</li>
 * </ul>
 */
@CompilationTier
class NewExecutionInputFactoryTest {

    @Test
    void dataLoaderRegistry_overrideReplacesFactoryDefault() {
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        DataLoaderRegistry custom = new DataLoaderRegistry();
        var input = Graphitron.newExecutionInput(dsl)
            .query("{ __typename }")
            .dataLoaderRegistry(custom)
            .build();
        assertThat(input.getDataLoaderRegistry()).isSameAs(custom);
    }

    @Test
    void dslOverload_attachesEmptyDataLoaderRegistryByDefault() {
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        var input = Graphitron.newExecutionInput(dsl).query("{ __typename }").build();
        assertThat(input.getDataLoaderRegistry()).isNotNull();
        assertThat(input.getDataLoaderRegistry().getDataLoaders()).isEmpty();
    }

    @Test
    void contextOverload_putsContextUnderTypedKey() {
        GraphitronContext ctx = env -> DSL.using(SQLDialect.POSTGRES);
        var input = Graphitron.newExecutionInput(ctx).query("{ __typename }").build();
        assertThat(input.getGraphQLContext().<GraphitronContext>get(GraphitronContext.class))
            .isSameAs(ctx);
    }
}
