package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Post-condition tests for {@link FetcherRegistrationsEmitter#emit}: every entry in the
 * returned map must have a non-empty wiring body. The keyset of this map is consumed by
 * {@code GraphitronSchemaClassGenerator}, which iterates and emits a
 * {@code <Name>Type.registerFetchers(codeRegistry)} call per entry; an empty-body entry
 * would cause {@code ObjectTypeGenerator} to skip emitting the corresponding method (it
 * gates on {@code fetcherBody != null}), producing an orphan call site and a javac error
 * in the emitted {@code GraphitronSchema.java}. The invariant is gated at the
 * construction site in {@code typeBody} / {@code nestedBody} (Optional return + ifPresent
 * put); the sakila-example cross-module compile is the structural backstop that catches any
 * regression that slips past the gating change.
 *
 * <p>One fixture per code path that produced an empty body before the gating change:
 * the regular type arm (an unreferenced payload-shaped type with one classifier-skipped
 * field) and the nested type arm (a nesting field whose collected nested type has no
 * classifiable nested fields).
 */
@UnitTier
class FetcherRegistrationsEmitterTest {

    @Test
    void unreferencedPayloadTypeDoesNotProduceEmptyBodyEntry() {
        var sdl = """
            type Query { film: Film }
            type Film @table(name: "film") { id: ID }
            type SlettRegelverksamlingPayload { regelverksamlingId: [ID!] @nodeId }
            """;
        var bodies = bodiesFor(sdl);
        assertNoEmptyValues(bodies);
        assertThat(bodies)
            .as("the bug-reproducing payload must not appear in the keyset at all")
            .doesNotContainKey("SlettRegelverksamlingPayload");
    }

    @Test
    void noEmptyBodies_forSingleRecordCarrierFixture() {
        var sdl = """
            type Query { film: Film }
            type Film @table(name: "film") { id: ID }
            """;
        assertNoEmptyValues(bodiesFor(sdl));
    }

    @Test
    void noEmptyBodies_forConnectionAndEdgeFixture() {
        var sdl = """
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """;
        assertNoEmptyValues(bodiesFor(sdl));
    }

    private static Map<String, CodeBlock> bodiesFor(String sdl) {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(sdl);
        return FetcherRegistrationsEmitter.emit(schema, DEFAULT_OUTPUT_PACKAGE);
    }

    private static void assertNoEmptyValues(Map<String, CodeBlock> bodies) {
        assertThat(bodies)
            .as("every entry's value must be a non-empty CodeBlock so the keyset/method emission contract holds")
            .allSatisfy((name, body) ->
                assertThat(body.isEmpty()).as("entry %s has empty body", name).isFalse());
    }
}
