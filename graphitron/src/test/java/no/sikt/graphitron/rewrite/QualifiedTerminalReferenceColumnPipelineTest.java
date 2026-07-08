package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R444 pipeline coverage: a scalar {@code @reference} field whose FK path terminates on a table
 * whose bare name collides across generated schemas must resolve the column against the FK-pinned
 * terminal {@link no.sikt.graphitron.rewrite.model.TableRef} (class identity), not re-resolve the
 * bare SQL name through the catalog. Pre-R444, {@code ServiceCatalog.terminalTableSqlName}
 * collapsed the identity-resolved terminal ref to a bare name string and
 * {@code JooqCatalog.findColumn(String, ...)} hit {@code TableResolution.Ambiguous} on the
 * colliding name, demoting the field to {@link GraphitronField.UnclassifiedField} with a spurious
 * "column could not be resolved" author error — with no author-side workaround, since the
 * {@code @reference} key names the FK on the source table and carries no syntax to qualify the
 * FK terminal.
 *
 * <p>Sibling of R422's {@code QualifiedReturnTypeReferencePipelineTest} (object-return-type
 * terminal verdict) and R396's {@code QualifiedSourceReferencePipelineTest} (source {@code @table}
 * resolution); this member covers the scalar-column read at the FK terminal. The fixture is the
 * existing multi-schema jOOQ codegen output: {@code multischema_a.event_log} carries FK
 * {@code event_log_event_id_fkey} into {@code multischema_a.event}; {@code event} collides across
 * {@code multischema_a} / {@code multischema_b}; column {@code name} exists only on A's copy,
 * {@code code} only on B's.
 *
 * <p>Assertions land at the classifier/model surface. The schema-pinned test (reading {@code code})
 * pins that the fix resolves against the FK-pinned schema A copy rather than scanning every schema
 * for a match; the unknown-column test pins the diagnostic path's candidate list, which pre-R444
 * was empty on a colliding terminal (the bare-name candidate lookup was ambiguity-broken too).
 */
@PipelineTier
class QualifiedTerminalReferenceColumnPipelineTest {

    private static final String MULTI_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.multischemafixture";
    private static final String MULTI_OUTPUT_PACKAGE = "fake.code.generated.multischema";

    private static RewriteContext multiSchemaContext() {
        return new RewriteContext(
            List.of(), Path.of(""), Path.of(""),
            MULTI_OUTPUT_PACKAGE, MULTI_JOOQ_PACKAGE, Map.of());
    }

    /**
     * An EventLog parent bound to the bare {@code event_log} (unique to schema A) with
     * {@code fieldDecl} the scalar {@code @reference}-bearing field under test, mirroring the live
     * repro shape: source table unique, FK terminal colliding across schemas.
     */
    private static String sdl(String fieldDecl) {
        return """
            type EventLog @table(name: "event_log") {
                eventLogId: Int! @field(name: "event_log_id")
                %s
            }
            type Query { eventLogs: [EventLog!]! }
            """.formatted(fieldDecl);
    }

    private static GraphitronField classify(String fieldDecl) {
        var schema = TestSchemaHelper.buildSchema(sdl(fieldDecl), multiSchemaContext());
        assertThat(schema.type("EventLog"))
            .as("parent on the unique bare name must classify as a table type")
            .isInstanceOf(GraphitronType.TableType.class);
        return schema.field("EventLog", "eventName");
    }

    @Test
    void collidingTerminal_columnOnPinnedSchema_classifiesAsColumnReferenceField() {
        // 'name' exists on multischema_a.event (the FK-pinned terminal). Pre-R444 the bare-name
        // terminal lookup was ambiguous across the two 'event' tables and demoted the field to
        // UnclassifiedField; the identity-carrying resolver classifies it green.
        var field = classify(
            "eventName: String @field(name: \"name\") @reference(path: [{key: \"event_log_event_id_fkey\"}])");
        assertThat(field)
            .as("scalar @reference read on a cross-schema-colliding FK terminal must resolve, "
                + "not become UnclassifiedField on a spurious ambiguous-name miss")
            .isInstanceOf(ChildField.ColumnReferenceField.class);
        // The resolved column is A's copy: the classification is not vacuous.
        assertThat(((ChildField.ColumnReferenceField) field).column().sqlName()).isEqualTo("name");
    }

    @Test
    void collidingTerminal_columnOnlyOnOtherSchema_stillRejects() {
        // 'code' exists only on multischema_b.event; the FK pins multischema_a.event. This must
        // stay an unknown-column author error — the fix resolves against the FK-pinned schema A
        // copy, it does not search all schemas for any table carrying the column.
        var field = classify(
            "eventName: String @field(name: \"code\") @reference(path: [{key: \"event_log_event_id_fkey\"}])");
        assertThat(field)
            .as("a column on the other schema's same-named table is not reachable through the "
                + "FK-pinned terminal and must keep rejecting")
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).rejection())
            .isInstanceOf(Rejection.AuthorError.UnknownName.class);
    }

    @Test
    void collidingTerminal_genuineUnknownColumn_rejectsWithNonEmptyCandidates() {
        // 'bogus' exists nowhere; the author error must fire, and its candidate list must now
        // enumerate A's event columns. Pre-R444 the diagnostic's candidate lookup went through the
        // same ambiguity-broken bare-name resolve and came back empty on a colliding terminal.
        var field = classify(
            "eventName: String @field(name: \"bogus\") @reference(path: [{key: \"event_log_event_id_fkey\"}])");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        var rejection = ((GraphitronField.UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        assertThat(((Rejection.AuthorError.UnknownName) rejection).candidates())
            .as("the unknown-column diagnostic must enumerate the FK-pinned terminal's columns, "
                + "not come back empty on a colliding terminal name")
            .containsExactlyInAnyOrder("EVENT_ID", "NAME");
    }
}
