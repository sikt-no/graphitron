package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.plan.ConditionCommands;
import no.sikt.graphitron.plan.LauncherCommands;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The launcher-method census at both of the sites that must agree, driven by one SDL fixture so
 * the agreement itself is what gets asserted rather than two independent facts: the authored-schema
 * mirror the author sees ({@code GraphitronSchemaValidator.validateLauncherMethodNames} reading
 * {@code LauncherCommands.methodCollisions}), and the relation constructor's hard failure behind it,
 * reached through {@code LauncherCommands.produce}.
 *
 * <p>The fixture is a genuine collision: {@code fooBar} and {@code FooBar} both upper-camel to
 * {@code rowsFooBar}, the pair the non-injective naming formula really cannot emit. Its complement,
 * the pair differing after the first letter that mints two distinct methods and is admitted, is
 * pinned by {@code LauncherCommandsPipelineTest}.
 */
@PipelineTier
class LauncherMethodNameValidationTest {

    private static final String COLLIDING_SDL = """
        type Language @table(name: "language") { name: String }
        type Query {
            fooBar: [Language!]!
            FooBar: [Language!]!
        }
        """;

    @Test
    void theMirrorRejectsTheCollisionAsDeferred_namingTheMethodTheGeneratorWouldMint() {
        var schema = TestSchemaHelper.buildSchema(COLLIDING_SDL);

        var collisions = new GraphitronSchemaValidator().validate(schema).stream()
            .filter(e -> e.message().contains("generated launcher method"))
            .toList();

        assertThat(collisions).hasSize(1);
        var error = collisions.get(0);
        // The arm is the assertion, not prose: the schema is legal and an injective formula would
        // emit it, so the rejection is a deferred species and not an author error.
        assertThat(error.kind()).isEqualTo(RejectionKind.DEFERRED);
        assertThat(error.message())
            .as("the quoted name is the method the generator would emit, not a folded string")
            .contains("QueryFetchers#rowsFooBar")
            .doesNotContain("case-folded");
        assertThat(error.location()).isNotNull();
        assertThat(error.location().getLine()).isPositive();
    }

    @Test
    void theRelationConstructorIsTheBackstopBehindTheMirror() {
        var schema = TestSchemaHelper.buildSchema(COLLIDING_SDL);
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);

        assertThatThrownBy(() -> LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minted twice")
            .hasMessageContaining("rowsFooBar");
    }
}
