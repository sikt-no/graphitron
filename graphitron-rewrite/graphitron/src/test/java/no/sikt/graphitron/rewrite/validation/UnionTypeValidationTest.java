package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class UnionTypeValidationTest {

    enum Case implements TypeValidatorCase {

        NO_PARTICIPANTS("union type with no member types — valid",
            new UnionType("SearchResult", null, List.of()),
            List.of()),

        ALL_BOUND("all member types are table-bound — type-level validator stays no-op; "
            + "R36 Track B's PK constraints run at field level (see QueryUnionFieldValidationTest)",
            new UnionType("SearchResult", null, List.of(
                new ParticipantRef.TableBound("Film", new TableRef("film", "FILM", "Film", List.of()), null),
                new ParticipantRef.TableBound("Category", new TableRef("category", "CATEGORY", "Category", List.of()), null)
            )),
            List.of());

        private final String description;
        private final GraphitronType type;
        private final List<String> errors;

        Case(String description, GraphitronType type, List<String> errors) {
            this.description = description;
            this.type = type;
            this.errors = errors;
        }

        @Override public GraphitronType type() { return type; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void unionTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
