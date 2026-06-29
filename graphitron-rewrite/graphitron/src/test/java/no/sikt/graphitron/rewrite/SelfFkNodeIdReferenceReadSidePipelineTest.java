package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R328 (D1) read-side fallout. The same-table-{@code @reference}-means-self-FK gate lives in the
 * single shared {@link NodeIdLeafResolver#resolve}, so relaxing it also admits a same-table
 * {@code @nodeId @reference} on a <em>read-side query argument / filter</em>: it resolves to
 * {@link NodeIdLeafResolver.Resolved.FkTarget.DirectFk DirectFk} and is emitted as a self-FK filter
 * ({@code WHERE child_cols IN (decoded keys)}, no self-join), exactly like a cross-table FK-target
 * arg. This is in scope by design, not an unstated side effect, and is pinned here.
 *
 * <p>Uses the public (Sakila) catalog: the R328 {@code email} self-FK fixture
 * ({@code email_in_reply_to_fk (mailbox_id, in_reply_to_no) -> (mailbox_id, message_no)}) lives there
 * (see {@code init.sql}), reachable from this {@code TestSchemaHelper.buildSchema(sdl)} default
 * context.
 */
@PipelineTier
class SelfFkNodeIdReferenceReadSidePipelineTest {

    @Test
    void selfFkReferenceArg_resolvesToDirectFkFilter_rowInOnOwnChildColumns_noSelfJoin() {
        // The composite-key self-FK reference arg ships a RowIn over email's OWN (mailbox_id,
        // in_reply_to_no) — the lifted self-FK child columns, the DirectFk shape. The predicate is a
        // single-table SELECT (no self-join to the parent email row); the proof is that the RowIn
        // columns are the child columns on the field's own table, fed the decodeEmail extraction.
        var schema = TestSchemaHelper.buildSchema("""
            type Email implements Node @table(name: "email") @node {
                id: ID! @nodeId
                subject: String @field(name: "subject")
            }
            type Query {
                emailReplies(
                    inReplyToIds: [ID!]! @nodeId(typeName: "Email")
                        @reference(path: [{key: "email_in_reply_to_fk"}])
                ): [Email!]!
            }
            """);

        var f = (QueryField.QueryTableField) schema.field("Query", "emailReplies");
        var gcf = (GeneratedConditionFilter) f.filters().stream()
            .filter(GeneratedConditionFilter.class::isInstance)
            .findFirst().orElseThrow();
        var rowIn = (BodyParam.RowIn) gcf.bodyParams().stream()
            .filter(BodyParam.RowIn.class::isInstance)
            .findFirst().orElseThrow();
        assertThat(rowIn.columns())
            .as("filter binds against the self-FK child columns on email's own table (no self-join)")
            .extracting(c -> c.sqlName())
            .containsExactly("mailbox_id", "in_reply_to_no");
        assertThat(rowIn.extraction()).isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class);
        var skip = (CallSiteExtraction.ThrowOnMismatch) rowIn.extraction();
        assertThat(skip.decodeMethod().methodName()).isEqualTo("decodeEmail");
    }

    @Test
    void selfFkReferenceArg_withAsConnection_doesNotFireSameTableAdvisory() {
        // The would-fire shape: a REQUIRED same-table @nodeId arg composed with @asConnection is the
        // exact shape the same-table advisory flags (every page would equal the input set). With
        // @reference present it resolves to DirectFk, not SameTable, so it is NOT collected as a
        // SameTableHit and the advisory stays silent — the @asConnection composes cleanly with a
        // self-FK filter (filter narrows; seek paginates within it). The field still classifies.
        var schema = TestSchemaHelper.buildSchema("""
            type Email implements Node @table(name: "email") @node { id: ID! @nodeId }
            type EmailConnection { edges: [EmailEdge!]! pageInfo: PageInfo! }
            type EmailEdge { node: Email! cursor: String! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query {
                emailReplies(
                    inReplyToIds: [ID!]! @nodeId(typeName: "Email")
                        @reference(path: [{key: "email_in_reply_to_fk"}])
                ): EmailConnection @asConnection
            }
            """);

        var f = schema.field("Query", "emailReplies");
        assertThat(f)
            .as("self-FK reference + @asConnection classifies (it does not reject)")
            .isNotInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((QueryField.QueryTableField) f).returnType().wrapper())
            .isInstanceOf(FieldWrapper.Connection.class);
        assertThat(schema.warnings())
            .as("the same-table advisory must not fire on a self-FK reference (it is a filter, not identity)")
            .extracting(BuildWarning::message)
            .noneMatch(msg -> msg.contains("emailReplies")
                && msg.contains("every page of @asConnection would equal the input set"));
    }
}
