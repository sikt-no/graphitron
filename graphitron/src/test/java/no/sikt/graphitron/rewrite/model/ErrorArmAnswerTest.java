package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error-arm table: what a data field of a payload with an errors field answers when nothing
 * was produced, keyed on its return wrapper alone.
 */
@UnitTier
class ErrorArmAnswerTest {

    @Test
    void nullableFieldsAnswerNull() {
        assertThat(ErrorArmAnswer.of(new FieldWrapper.Single(true))).isEqualTo(ErrorArmAnswer.NULL);
        assertThat(ErrorArmAnswer.of(new FieldWrapper.List(true, true))).isEqualTo(ErrorArmAnswer.NULL);
        assertThat(ErrorArmAnswer.of(new FieldWrapper.List(true, false))).isEqualTo(ErrorArmAnswer.NULL);
        assertThat(ErrorArmAnswer.of(new FieldWrapper.Connection(true, 100))).isEqualTo(ErrorArmAnswer.NULL);
    }

    @Test
    void nonNullPlainListsAnswerTheEmptyList() {
        assertThat(ErrorArmAnswer.of(new FieldWrapper.List(false, true))).isEqualTo(ErrorArmAnswer.EMPTY_LIST);
        assertThat(ErrorArmAnswer.of(new FieldWrapper.List(false, false))).isEqualTo(ErrorArmAnswer.EMPTY_LIST);
    }

    @Test
    void nonNullSinglesAndConnectionsHaveNoAnswer() {
        assertThat(ErrorArmAnswer.of(new FieldWrapper.Single(false))).isEqualTo(ErrorArmAnswer.NONE);
        assertThat(ErrorArmAnswer.of(new FieldWrapper.Connection(false, 100))).isEqualTo(ErrorArmAnswer.NONE);
    }
}
