package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R388 defect-2 coverage: a participant {@code @reference} field whose resolved column already
 * exists on the discriminated interface/base table is a contradiction (the column is read directly
 * off the base table, so a cross-table {@code @reference} is meaningless). The classifier resolves
 * the predicate once in {@code TypeBuilder.extractCrossTableFields} (catalog in scope), skips the
 * field from the participant's cross-table set, and registers a build diagnostic that the validator
 * drains; the build fails with a file:line author error. A participant-only {@code @reference} field
 * (column lives only on the detail table) is not matched and stays a valid cross-table field.
 *
 * <p>Driven by the {@code jti_subject} + {@code jti_app_account} joined-inheritance fixture in
 * {@code graphitron-sakila-db/src/main/resources/init.sql}: the detail table re-declares the
 * discriminator column {@code subject_kind} via its composite FK, so {@code subject_kind} exists on
 * both the base table and the FK-target detail table — the exact overlap the guard rejects.
 */
@PipelineTier
class DiscriminatorReferenceContradictionPipelineTest {

    @Test
    void referenceOnDiscriminatorColumn_rejectedAsContradiction() {
        var schema = build("""
            interface Subject @table(name: "jti_subject") @discriminate(on: "subject_kind") {
                subjectId:   Int!    @field(name: "jti_subject_id")
                subjectKind: String! @field(name: "subject_kind")
            }
            type AppAccount implements Subject @table(name: "jti_subject") @discriminator(value: "APP") {
                subjectId:   Int!    @field(name: "jti_subject_id")
                subjectKind: String! @reference(path: [{key: "jti_app_account_subject_fk"}]) @field(name: "subject_kind")
            }
            type Query { allSubjects: [Subject!]! }
            """);

        assertThat(schema.diagnostics())
            .as("a @reference on a discriminator/base-table column must be rejected at build time")
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("AppAccount.subjectKind")
                && e.message().contains("subject_kind")
                && e.message().contains("jti_subject")
                && e.message().contains("must be removed")
                // candidate hint points at the detail-only column, never the overlapping discriminator
                && e.message().contains("client_id"));
    }

    @Test
    void referenceOnDetailOnlyColumn_staysValid() {
        // The participant-only field (client_id lives only on the detail table) is the load-bearing
        // counter-case: its @reference is genuine cross-table navigation and must NOT be rejected.
        var schema = build("""
            interface Subject @table(name: "jti_subject") @discriminate(on: "subject_kind") {
                subjectId: Int! @field(name: "jti_subject_id")
            }
            type AppAccount implements Subject @table(name: "jti_subject") @discriminator(value: "APP") {
                subjectId: Int!   @field(name: "jti_subject_id")
                clientId:  String @reference(path: [{key: "jti_app_account_subject_fk"}]) @field(name: "client_id")
            }
            type Query { allSubjects: [Subject!]! }
            """);

        assertThat(schema.diagnostics())
            .as("a participant-only @reference field must not trip the discriminator-column contradiction guard")
            .noneMatch(e -> e.message().contains("already exists on the interface/base table"));
        assertThat(schema.field("AppAccount", "clientId"))
            .isInstanceOf(ChildField.ParticipantColumnReferenceField.class);
    }

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
