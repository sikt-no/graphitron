package no.sikt.graphitron.rewrite.validation;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage for the validator mirror of the
 * {@code error-channel.local-context-transport} invariant. A carrier admitted with
 * {@link ChildField.Transport.LocalContext} must have a sibling data-channel field whose fetcher
 * honors the null-source short-circuit guard. The validator allow-list is exercised against the
 * variants currently reachable through {@code BuildContext.classifyCarrierField}; an off-allow-list
 * sibling produces a structural error.
 *
 * <p>The fixture builds {@link GraphitronSchema} instances directly rather than going through SDL
 * because today's classifier doesn't emit an off-allow-list shape. The validator mirror is
 * pre-emptive coverage for future widenings (R161 et al.), so the test fabricates the rejection
 * input at the model level.
 */
@UnitTier
class LocalContextErrorsFieldValidationTest {

    private static GraphitronSchema schemaWithFields(String parentTypeName, GraphitronField... fields) {
        var coords = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        for (var f : fields) {
            coords.put(FieldCoordinates.coordinates(f.parentTypeName(), f.name()), f);
        }
        return new GraphitronSchema(
            Map.of(parentTypeName, new GraphitronType.RootType(parentTypeName, null)),
            coords
        );
    }

    private static ChildField.ErrorsField localContextErrors(String parentTypeName) {
        return new ChildField.ErrorsField(
            parentTypeName, "errors", null, List.of(), new ChildField.Transport.LocalContext());
    }

    private static ChildField.ErrorsField payloadAccessorErrors(String parentTypeName) {
        return new ChildField.ErrorsField(
            parentTypeName, "errors", null, List.of(), new ChildField.Transport.PayloadAccessor());
    }

    private static ChildField.SingleRecordIdentityField guardedSibling(String parentTypeName) {
        return new ChildField.SingleRecordIdentityField(
            parentTypeName, "film", null,
            new ReturnTypeRef.ResultReturnType("Film", new FieldWrapper.Single(true), "com.example.Film"));
    }

    private static ChildField.ColumnField unguardedSibling(String parentTypeName) {
        return new ChildField.ColumnField(
            parentTypeName, "title", null, "title",
            new ColumnRef("TITLE", "", ""),
            new CallSiteCompaction.Direct());
    }

    @Test
    void localContextErrorsField_withGuardedSibling_passes() {
        var errors = validate(schemaWithFields("FilmPayload",
            guardedSibling("FilmPayload"),
            localContextErrors("FilmPayload")));
        assertThat(errors).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("LocalContext errors transport"));
    }

    @Test
    void localContextErrorsField_withUnguardedSibling_rejectsStructurally() {
        var errors = validate(schemaWithFields("FilmPayload",
            unguardedSibling("FilmPayload"),
            localContextErrors("FilmPayload")));
        assertThat(errors)
            .anyMatch(e -> e.kind() == RejectionKind.AUTHOR_ERROR
                && e.message().contains("Field 'FilmPayload.errors'")
                && e.message().contains("LocalContext errors transport")
                && e.message().contains("sibling field 'FilmPayload.title'")
                && e.message().contains("ColumnField"));
    }

    @Test
    void payloadAccessorErrorsField_withUnguardedSibling_doesNotTriggerLocalContextCheck() {
        var errors = validate(schemaWithFields("FilmPayload",
            unguardedSibling("FilmPayload"),
            payloadAccessorErrors("FilmPayload")));
        assertThat(errors).extracting(ValidationError::message)
            .noneMatch(m -> m.contains("LocalContext errors transport"));
    }
}
