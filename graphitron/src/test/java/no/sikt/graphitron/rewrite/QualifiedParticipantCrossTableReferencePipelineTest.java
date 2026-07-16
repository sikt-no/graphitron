package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline coverage: a participant cross-table {@code @reference} field whose single-hop FK
 * terminates on a table whose bare name collides across generated schemas must resolve the column
 * against the FK-pinned terminal {@link no.sikt.graphitron.rewrite.model.TableRef} (class identity),
 * not re-resolve the bare SQL name through the catalog. Pre-fix, {@code TypeBuilder.extractCrossTableFields}
 * collapsed the identity-carrying {@code fk.targetTable()} to its bare {@code tableName()} and
 * {@code JooqCatalog.findColumn(String, ...)} hit {@code TableResolution.Ambiguous} on the colliding
 * name; the column resolve came back empty and the field was skipped from the participant's
 * cross-table set. It then fell through to {@code FieldBuilder}'s scalar {@code @reference} path,
 * which silently misclassifies it as a plain {@link ChildField.ColumnReferenceField}
 * instead of a {@link ChildField.ParticipantColumnReferenceField} — so the interface fetcher emits
 * no conditional LEFT JOIN / alias projection, and the classification of a participant field comes
 * to depend on whether an unrelated schema happens to hold a same-named table. There is no
 * author-side workaround: the FK terminal is not author-named (the {@code @reference} key is
 * {@code TABLE__CONSTRAINT} on the source table).
 *
 * <p>Sibling of {@code QualifiedTerminalReferenceColumnPipelineTest} (scalar terminal column
 * read) and {@code QualifiedReturnTypeReferencePipelineTest} (object-return-type terminal
 * verdict); this member covers the participant cross-table sub-class. The fixture is the existing
 * multi-schema jOOQ codegen output: {@code multischema_a.event_log} (bare name unique to A; columns
 * {@code event_log_id}, {@code event_id}, {@code note}) carries FK {@code event_log_event_id_fkey}
 * into {@code multischema_a.event}; {@code event} collides across {@code multischema_a} /
 * {@code multischema_b}; column {@code name} exists only on A's copy, {@code code} only on B's.
 *
 * <p>The SDL is a single-table discriminated interface over {@code event_log} using {@code note} as
 * the discriminator column, with a participant carrying the cross-table {@code @reference}, mirroring
 * {@code DiscriminatorReferenceContradictionPipelineTest}.
 */
@PipelineTier
class QualifiedParticipantCrossTableReferencePipelineTest {

    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(
            List.of(), Path.of(""), Path.of(""),
            MULTI_OUTPUT_PACKAGE, MULTI_JOOQ_PACKAGE, Map.of());
    }

    /**
     * A single-table discriminated interface over the bare {@code event_log} (unique to schema A)
     * with a participant {@code AlertEntry} carrying {@code fieldDecl}, the cross-table
     * {@code @reference}-bearing field under test whose FK terminal ({@code event}) collides across
     * schemas.
     */
    private static String sdl(String fieldDecl) {
        return """
            interface Entry @table(name: "event_log") @discriminate(on: "note") {
                entryId: Int! @field(name: "event_log_id")
            }
            type AlertEntry implements Entry @table(name: "event_log") @discriminator(value: "ALERT") {
                entryId: Int! @field(name: "event_log_id")
                %s
            }
            type Query { allEntries: [Entry!]! }
            """.formatted(fieldDecl);
    }

    private static GraphitronSchema build(String fieldDecl) {
        return TestSchemaHelper.buildSchema(sdl(fieldDecl), multiSchemaContext());
    }

    @Test
    void collidingTerminal_columnOnPinnedSchema_classifiesAsParticipantColumnReferenceField() {
        // 'name' exists only on multischema_a.event (the FK-pinned terminal). Pre-fix the bare-name
        // terminal lookup was ambiguous across the two 'event' tables, so the field was skipped from
        // the cross-table set and misclassified as a plain ColumnReferenceField through
        // the FieldBuilder fallback. The identity-carrying resolver classifies it green.
        var schema = build(
            "eventName: String @field(name: \"name\") @reference(path: [{key: \"event_log_event_id_fkey\"}])");
        var field = schema.field("AlertEntry", "eventName");
        assertThat(field)
            .as("a participant cross-table @reference on a cross-schema-colliding FK terminal must "
                + "classify as ParticipantColumnReferenceField, not be skipped and misclassified")
            .isInstanceOf(ChildField.ParticipantColumnReferenceField.class);
        // The resolved column is A's copy: the classification is not vacuous.
        assertThat(((ChildField.ParticipantColumnReferenceField) field).column().sqlName())
            .isEqualTo("name");
    }

    @Test
    void collidingTerminal_columnOnlyOnOtherSchema_stillRejectsWithPinnedSchemaCandidates() {
        // 'code' exists only on multischema_b.event; the FK pins multischema_a.event. The field is
        // skipped from the cross-table set (the FK-pinned terminal has no 'code'), falls through to
        // FieldBuilder's scalar @reference path, and rejects as an unknown-column author error whose
        // candidates enumerate A's event columns — not B's. This pins that the fix resolves against
        // the FK-pinned schema A copy rather than scanning every schema for a match.
        var schema = build(
            "eventName: String @field(name: \"code\") @reference(path: [{key: \"event_log_event_id_fkey\"}])");
        var field = schema.field("AlertEntry", "eventName");
        assertThat(field)
            .as("a column on the other schema's same-named table is not reachable through the "
                + "FK-pinned terminal and must keep rejecting")
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
        var rejection = ((GraphitronField.UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        assertThat(((Rejection.AuthorError.UnknownName) rejection).candidates())
            .as("the unknown-column diagnostic must enumerate the FK-pinned terminal's (A's) columns")
            .containsExactlyInAnyOrder("EVENT_ID", "NAME");
    }

    @Test
    void collidingTerminal_baseResidentColumn_tripsContradictionGuardWithNonEmptyHint() {
        // 'event_id' exists on both event_log (base) and multischema_a.event (detail). The
        // base-resident-column guard fires (the column is read directly off the base table, so the cross-table
        // @reference is meaningless), and its detail-only candidate hint must be non-empty — naming
        // 'name', A's only detail-resident column. Pre-fix the colliding detail table produced an
        // empty candidate list, so the "did you mean" hint vanished exactly when the layout confused.
        var schema = build(
            "eventName: String @field(name: \"event_id\") @reference(path: [{key: \"event_log_event_id_fkey\"}])");
        assertThat(schema.diagnostics())
            .as("a @reference on a base-resident column must trip the contradiction guard, and its "
                + "detail-only candidate hint must name A's detail-only column")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("AlertEntry.eventName")
                && e.message().contains("event_id")
                && e.message().contains("event_log")
                && e.message().contains("must be removed")
                // the rendered "did you mean" suffix names A's only detail-resident column; the
                // suffix is emitted only when the candidate list is non-empty (empty pre-fix).
                && e.message().contains("e.g.: name"));
    }
}
