package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationServiceTableInterfaceField;
import no.sikt.graphitron.rewrite.model.QueryField.QueryServiceTableInterfaceField;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors the {@link QueryTableInterfaceFieldValidationTest} shape for the single-table
 * service-interface variants. A well-formed field passes with no {@link ValidationError} (the mirror
 * runs {@code validateCardinality} only — the multi-table participant floor route (a) needs would
 * wrongly reject the valid single-table shape, so it is deliberately not applied). This pins that the
 * classifier's new acceptance did not leave the accepted shape without a validate-time floor.
 */
@UnitTier
class ServiceTableInterfaceFieldValidationTest {

    private static ServiceMethodCall call() {
        return new ServiceMethodCall.Static(
            "no.sikt.graphitron.rewrite.TestServiceStub", "getContents", List.of(),
            ClassName.get("org.jooq", "Record"));
    }

    enum Case implements ValidatorCase {

        QUERY_SINGLE("query, single cardinality — accepted, no errors expected",
            new QueryServiceTableInterfaceField("Query", "media", null,
                new ReturnTypeRef.TableBoundReturnType("Content",
                    TestFixtures.tableRef("content", "CONTENT", "Content", List.of()),
                    new FieldWrapper.Single(true)),
                "CONTENT_TYPE", List.of("FILM", "SHORT"), List.of(), call(), Optional.empty()),
            List.of()),

        MUTATION_LIST("mutation, list cardinality — accepted, no errors expected",
            new MutationServiceTableInterfaceField("Mutation", "mediaSearch", null,
                new ReturnTypeRef.TableBoundReturnType("Content",
                    TestFixtures.tableRef("content", "CONTENT", "Content", List.of()),
                    new FieldWrapper.List(false, true)),
                "CONTENT_TYPE", List.of("FILM", "SHORT"), List.of(), call(), Optional.empty()),
            List.of());

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void serviceTableInterfaceFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
