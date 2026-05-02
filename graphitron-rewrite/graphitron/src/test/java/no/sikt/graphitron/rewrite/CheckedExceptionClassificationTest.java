package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12 §4 classifier integration: a {@code @service} method that declares a non-exempt checked
 * exception with no covering {@code @error} handler on the field's payload classifies as
 * {@link UnclassifiedField}; an exempt declaration ({@code IOException},
 * {@code InterruptedException}) classifies cleanly. Each test wires an SDL fragment through
 * {@link TestSchemaHelper#buildSchema} and asserts the resulting field variant + reason.
 *
 * <p>Service-method fixtures live in {@link TestServiceStub}; the §4 check sits inside
 * {@link FieldBuilder#buildServiceField} (and parallel sites for child {@code @service} and
 * root/child {@code @tableMethod}). The match-rule unit tests live in
 * {@link CheckedExceptionMatcherTest}; this class covers the wiring path end-to-end.
 */
@UnitTier
class CheckedExceptionClassificationTest {

    @Test
    void serviceMethod_withDeclaredSqlException_noChannel_rejects() {
        // String-returning service method declaring throws SQLException, on a field with no
        // payload-errors slot (the return type is just String). Per §4, every non-exempt
        // declared checked exception is unmatched when there's no channel — the field is
        // rejected with a descriptive reason rather than silently flowing through redact.
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
                throws: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getThrowingSqlException"})
            }
            """);

        var field = schema.field("Query", "throws");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) field).reason();
        assertThat(reason)
            .contains("declares checked exception(s)")
            .contains("java.sql.SQLException")
            .contains("no covering @error handler")
            .contains("(the field has no error channel)");
    }

    @Test
    void serviceMethod_withDeclaredIoException_noChannel_classifiesCleanly() {
        // IOException is exempt under §4's "Special cases" subsection: the matcher skips it
        // without consulting the channel, so a service method declaring throws IOException
        // classifies cleanly even with no channel.
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
                throws: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getThrowingIoException"})
            }
            """);

        var field = schema.field("Query", "throws");
        assertThat(field).isInstanceOf(QueryField.QueryServiceRecordField.class);
    }

    @Test
    void serviceMethod_withSqlAndInterruptedExceptions_rejectsOnlyTheNonExemptEntry() {
        // Per-class exemption: InterruptedException skips the match, but SQLException still
        // requires a covering handler. The reason names only the non-exempt entry.
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
                throws: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getThrowingSqlAndInterrupted"})
            }
            """);

        var field = schema.field("Query", "throws");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) field).reason();
        assertThat(reason)
            .contains("java.sql.SQLException")
            .doesNotContain("InterruptedException");
    }

    @Test
    void serviceMethod_withoutThrows_classifiesCleanly() {
        // Baseline: a service method with no throws clause continues to classify cleanly.
        // Without the §4 check this baseline would be the only case that worked; with the
        // check it must remain unaffected.
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
                rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """);

        assertThat(schema.field("Query", "rating")).isInstanceOf(QueryField.QueryServiceRecordField.class);
    }
}
