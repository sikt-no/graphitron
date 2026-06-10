package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.TestSchemaHelper.buildSchema;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Pins the user-facing warn-message format for {@code @asConnection} + required same-table
 * {@code @nodeId} leaf.
 *
 * <p>R113 demoted the classifier rejection on this shape to an advisory; production
 * schemas (e.g. opptak-subgraph's {@code Query.kompetanseregelverkGittIdV2}) deliberately
 * compose required same-table {@code @nodeId} args with {@code @asConnection} to ship a
 * paginated WHERE-IN connection to consumers, and a build break on that shape would be a
 * wire-format-incompatible change. The advisory still fires (the always-bounded shape is worth
 * flagging for authors who didn't mean to compose them), but classification continues.
 *
 * <p>R294 routed the advisory through the unified {@link BuildWarning} channel
 * ({@code ctx.addWarning} → {@link GraphitronSchema#warnings()}), retiring the dedicated
 * {@code asConnectionSameTableHygiene} logger; this test asserts the {@link BuildWarning} on
 * {@code schema.warnings()} rather than capturing logger output.
 *
 * <p>This test only pins the message format on the firing case — predicate coverage (when
 * the warn fires vs. stays silent) lives at pipeline tier in
 * {@code NodeIdPipelineTest.NodeIdConnectionAdvisoryCase}, where the same SDL shapes assert
 * the structural classification outcome. Duplicating the silence pins here would be
 * defence-in-depth on a single flag with no unique signal.
 */
@UnitTier
class AsConnectionSameTableWarnFormatTest {

    private static final RewriteContext NODEID_CTX = new RewriteContext(
        List.of(),
        Path.of(""),
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.nodeidfixture",
        Map.of()
    );

    private static final String CONNECTION_DECLS = """
        type BazConnection { edges: [BazEdge!]! pageInfo: PageInfo! }
        type BazEdge { node: Baz! cursor: String! }
        type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
        """;

    @Test
    void requiredLeaf_emitsWarn_namingFieldLeafAndType() {
        // Mirrors the production shape (required outer wrapper on a same-table @nodeId list arg
        // composed with @asConnection). The classifier emits the warn but classification still
        // produces a working QueryTableField + Connection wrapper at runtime. Pins the stable
        // bits migration tooling can grep for: field name, leaf name, typeName, headline
        // diagnostic, advisory hint.
        var schema = buildSchema("""
            type Baz implements Node @table(name: "baz") @node { id: ID! }
            """ + CONNECTION_DECLS + """
            type Query {
                bazByIds(ids: [ID!]! @nodeId(typeName: "Baz")): BazConnection @asConnection
            }
            """, NODEID_CTX);

        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .filteredOn(msg -> msg.contains("field 'bazByIds'"))
            .singleElement()
            .satisfies(msg -> assertThat(msg)
                .contains("@nodeId(typeName: 'Baz')")
                .contains("'ids'")
                .contains("every page of @asConnection would equal the input set")
                .contains("make 'ids' nullable"));
    }
}
