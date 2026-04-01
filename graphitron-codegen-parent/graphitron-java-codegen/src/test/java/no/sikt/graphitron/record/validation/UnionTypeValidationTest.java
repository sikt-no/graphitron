package no.sikt.graphitron.record.validation;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.UnionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class UnionTypeValidationTest {

    enum Case implements TypeValidatorCase {

        VALID {
            public GraphitronType type() {
                return new UnionType(GraphQLUnionType.newUnionType()
                    .name("SearchResult")
                    .possibleType(GraphQLObjectType.newObject().name("Film").build())
                    .build());
            }
            public List<String> errors() { return List.of(); }
        };

        public abstract GraphitronType type();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void unionTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
