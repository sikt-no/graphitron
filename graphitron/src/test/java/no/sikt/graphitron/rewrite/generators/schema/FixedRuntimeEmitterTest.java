package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.command.GlobalUnitKind;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@PipelineTier
class FixedRuntimeEmitterTest {

    @Test
    void writesTheSevenRuntimeClassesUnderTheSchemaPackage() {
        assertThat(FixedRuntimeEmitter.render("com.example.gen"))
            .extracting(FixedRuntimeEmitter.Unit::kind, unit -> unit.ref().fqcn(), unit -> unit.spec().name())
            .containsExactly(
                tuple(GlobalUnitKind.GRAPHITRON_CONTEXT, "com.example.gen.schema.GraphitronContext", "GraphitronContext"),
                tuple(GlobalUnitKind.TRANSACTION_PROVIDER, "com.example.gen.schema.GraphitronTransactionProvider",
                    "GraphitronTransactionProvider"),
                tuple(GlobalUnitKind.CONSTRAINT_VIOLATIONS, "com.example.gen.schema.ConstraintViolations",
                    "ConstraintViolations"),
                tuple(GlobalUnitKind.CLIENT_EXCEPTION, "com.example.gen.schema.GraphitronClientException",
                    "GraphitronClientException"),
                tuple(GlobalUnitKind.ZERO_ROWS_MATCHED_EXCEPTION, "com.example.gen.schema.ZeroRowsMatchedException",
                    "ZeroRowsMatchedException"),
                tuple(GlobalUnitKind.ERROR_ROUTER, "com.example.gen.schema.ErrorRouter", "ErrorRouter"),
                tuple(GlobalUnitKind.OUTCOME, "com.example.gen.schema.Outcome", "Outcome"));
    }

    /**
     * The package is read off the run's captured output coordinates, so a store holding none, as
     * a validating run's does, refuses rather than writing under a package nobody configured. The
     * reading itself is exercised wherever a run generates, the incremental compile harness among
     * them.
     */
    @Test
    void aStoreWithNoOutputCoordinatesRefuses() {
        assertThatThrownBy(() -> TestSchemaHelper.withStoreOver("type Query { x: String }",
                FixedRuntimeEmitter::render))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("has no output coordinates in the store");
    }
}
