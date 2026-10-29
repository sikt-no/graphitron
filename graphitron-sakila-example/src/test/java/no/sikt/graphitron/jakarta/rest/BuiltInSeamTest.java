package no.sikt.graphitron.jakarta.rest;

import graphql.ExecutionInput;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The built-in endpoint's seam is optional: {@link GraphitronApplication#newExecutionInput()} is a
 * default that refuses with the way out named, and the engine can tell at startup whether an
 * application overrode it. Both are decisions over plain classes, so unit tier; what the container
 * answers is pinned by the built-in endpoint's toggle tests.
 */
@UnitTier
class BuiltInSeamTest {

    private static class WithoutSeam extends AbstractGraphitronApplication {
        WithoutSeam() {
            super(() -> GraphQLSchema.newSchema().query(
                graphql.schema.GraphQLObjectType.newObject().name("Query")
                    .field(f -> f.name("x").type(graphql.Scalars.GraphQLString)).build()).build(),
                schema -> "");
        }
    }

    private static class WithSeam extends WithoutSeam {
        @Override
        public ExecutionInput.Builder newExecutionInput() {
            return ExecutionInput.newExecutionInput();
        }
    }

    /** Stands in for a container proxy, which declares every method and delegates to the instance. */
    private static class WithSeam_ClientProxy extends WithSeam {
        @Override
        public ExecutionInput.Builder newExecutionInput() {
            return super.newExecutionInput();
        }
    }

    /** A proxy over an application that did not override the seam still declares it itself. */
    private static class WithoutSeam_ClientProxy extends WithoutSeam {
        @Override
        public ExecutionInput.Builder newExecutionInput() {
            return super.newExecutionInput();
        }
    }

    @Test
    @DisplayName("The default seam throws, naming both ways out: override it, or turn the built-in endpoint off and use GraphqlEndpoint.")
    void defaultSeamRefusesWithTheWayOut() {
        assertThatThrownBy(() -> new WithoutSeam().newExecutionInput())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("newExecutionInput()")
            .hasMessageContaining("builtInEndpointEnabled()")
            .hasMessageContaining("GraphqlEndpoint");
    }

    @Test
    @DisplayName("The startup check sees an override on the application's own class.")
    void seesAnOverride() {
        assertThat(GraphqlEngine.suppliesBuiltInInput(new WithSeam())).isTrue();
        assertThat(GraphqlEngine.suppliesBuiltInInput(new WithoutSeam())).isFalse();
    }

    @Test
    @DisplayName("The startup check looks past a container proxy to the application's own class, which declares the seam or does not.")
    void looksPastAContainerProxy() {
        assertThat(GraphqlEngine.suppliesBuiltInInput(new WithSeam_ClientProxy())).isTrue();
        assertThat(GraphqlEngine.suppliesBuiltInInput(new WithoutSeam_ClientProxy())).isFalse();
    }

    @Test
    @DisplayName("Without a mount-cause finder, no failure is a mount failure; with one, its answer is used.")
    void mountCauseFollowsTheFinder() {
        var failure = new RuntimeException("boom");
        assertThat(new WithoutSeam().mountCause(failure)).isEmpty();
        var withFinder = new AbstractGraphitronApplication(() -> new WithoutSeam().schema(), s -> "",
            thrown -> java.util.Optional.of(failure)) { };
        assertThat(withFinder.mountCause(new IllegalStateException())).containsSame(failure);
    }
}
