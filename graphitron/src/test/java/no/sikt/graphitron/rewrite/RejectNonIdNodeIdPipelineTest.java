package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R262: {@code @nodeId} is silently conditional. The SDL directive permits {@code @nodeId} on
 * {@code FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION} with no restriction to
 * {@code ID}, but every decode/encode arm in the generator is gated on {@code "ID".equals(...)}.
 * On a non-{@code ID} coordinate the directive is dropped, the base64 wire String is bound raw, and
 * the build is green while production fails. This test pins the build-time rejection across the
 * three {@code on} locations (findings F, G, and the output encode mechanism) plus the federation
 * encoded-{@code @key} sub-case (finding H), and the legitimate {@code ID} coordinates that must
 * keep passing.
 *
 * <p>Asserts on the typed {@link ValidationError} (coordinate + {@link RejectionKind} + message)
 * draining the schema's diagnostic channel, never on field absence: under the diagnostics design the
 * field keeps its verdict and the validator throws before the emitter runs.
 */
@PipelineTier
class RejectNonIdNodeIdPipelineTest {

    private static final String FEDERATION_DIRECTIVES = """
        directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
        """;

    // The nodeidfixture catalog (NodeIdFixtureGenerator) carries the rooted-at-parent reproducer:
    // child_ref.parent_alt_key -> parent_node.alt_key, where ParentNode's keyColumn is pk_id. The FK
    // target (alt_key) does not match the keyColumn (pk_id), so the @nodeId reference resolves to a
    // ChildField.ColumnReferenceField with NodeIdEncodeKeys — exactly finding H's encoded-key shape.
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, "no.sikt.graphitron.rewrite.nodeidfixture",
        Map.of()
    );

    // ===== Finding F: @nodeId on a non-ID input-object field =====

    @Test
    void nodeIdOnNonIdInputField_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            input FilmFilter { title: String @nodeId }
            type Query { films(filter: FilmFilter): String }
            """);

        assertThat(schema.diagnostics())
            .as("@nodeId on a String input-object field must be rejected at build time (finding F)")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.title")
                && e.message().contains("@nodeId is only valid on a field/argument of type ID")
                && e.message().contains("'String'"));
    }

    // ===== Finding G: @nodeId on a non-ID top-level argument =====

    @Test
    void nodeIdOnNonIdArgument_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { search(term: String @nodeId): String }
            """);

        assertThat(schema.diagnostics())
            .as("@nodeId on a String argument must be rejected at build time (finding G)")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("Query.search")
                && e.message().contains("argument 'term'")
                && e.message().contains("@nodeId is only valid on a field/argument of type ID")
                && e.message().contains("'String'"));
    }

    // ===== Output encode mechanism: @nodeId on a non-ID output field =====

    @Test
    void nodeIdOnNonIdOutputField_rejected() {
        // The output FIELD_DEFINITION encode path has no "ID".equals gate at all
        // (FieldBuilder scalar/enum classifier reads hasAppliedDirective(DIR_NODE_ID) directly), so
        // this is the most acute axis: a base64 String would be projected into an Int-typed field.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { rating: Int @nodeId }
            type Query { film: Film }
            """);

        assertThat(schema.diagnostics())
            .as("@nodeId on an Int output field must be rejected at build time (output encode mechanism)")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("Film.rating")
                && e.message().contains("@nodeId is only valid on a field/argument of type ID")
                && e.message().contains("'Int'"));
    }

    // ===== Finding H: federation @key field that is itself @nodeId-encoded =====

    @Test
    void federationKeyOnNodeIdEncodedReference_rejectedFatally() {
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type ParentNode implements Node @table(name: "parent_node") @node {
                id: ID! @nodeId
                altKey: String! @field(name: "alt_key")
            }
            type ChildRef @table(name: "child_ref") @key(fields: "parentNodeId") {
                childId: String! @field(name: "child_id")
                parentNodeId: ID @nodeId(typeName: "ParentNode")
                    @reference(path: [{key: "child_ref_parent_alt_key_fkey"}])
            }
            type Query { childRefs: [ChildRef!]! }
            """, FIXTURE_CTX);

        assertThat(schema.diagnostics())
            .as("a federation @key on an @nodeId-encoded reference field must be rejected fatally (finding H)")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("ChildRef")
                && e.message().contains("parentNodeId")
                && e.message().contains("@nodeId-encoded reference"));
    }

    // ===== Counter-cases: legitimate @nodeId on ID coordinates must keep passing unchanged =====

    @Test
    void nodeIdOnIdCoordinates_notRejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Customer implements Node @table(name: "customer") @node {
                id: ID! @nodeId
            }
            input CustomerSelector @table(name: "customer") {
                id: ID! @nodeId(typeName: "Customer")
            }
            type Query {
                customerById(id: ID! @nodeId(typeName: "Customer")): Customer
            }
            """);

        assertThat(schema.diagnostics())
            .as("@nodeId on ID input/argument/output coordinates is legitimate and must not be rejected")
            .noneMatch(e -> e.message().contains("@nodeId is only valid on a field/argument of type ID"));
    }

    @Test
    void federationNodeIdHappyPath_notRejected() {
        // The canonical single-id @key on a @node type resolves to a KeyAlternative.NodeId (the
        // rep's id is decoded by NodeIdEncoder at runtime), not the Direct path, so finding H must
        // not fire.
        var schema = TestSchemaHelper.buildSchema(FEDERATION_DIRECTIVES + """
            type Customer implements Node @table(name: "customer") @node @key(fields: "id") {
                id: ID! @nodeId
            }
            type Query { customer: Customer }
            """);

        assertThat(schema.diagnostics())
            .as("the federation NODE_ID happy path (single id @key on a @node type) must not be rejected")
            .noneMatch(e -> e.message().contains("@nodeId-encoded reference"));
    }
}
