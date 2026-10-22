package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build's {@code <maxAllowedPageSize>} reaches the emitted {@code ConnectionHelper} as a
 * constant {@code pageRequest} lowers an oversized page size to, and is absent without one. That
 * the clamp serves pages at the maximum is the sakila example's execution tier's to show.
 */
@UnitTier
class ConnectionHelperClassGeneratorTest {

    private static final String OUTPUT_PACKAGE = "fake.code.generated";

    @Test
    @DisplayName("a maximum is carried as a private static final int constant of that value")
    void aMaximumIsAConstant() {
        var helper = ConnectionHelperClassGenerator
            .generate(OUTPUT_PACKAGE, false, OptionalInt.of(1000)).getFirst();

        assertThat(maxPageSize(helper)).isNotNull();
        var field = maxPageSize(helper);
        assertThat(field.type()).isEqualTo(TypeName.INT);
        assertThat(field.modifiers())
            .containsExactlyInAnyOrder(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        assertThat(field.initializer().toString()).isEqualTo("1000");
    }

    @Test
    @DisplayName("without a maximum the helper declares no such constant")
    void noMaximumNoConstant() {
        var helper = ConnectionHelperClassGenerator
            .generate(OUTPUT_PACKAGE, false, OptionalInt.empty()).getFirst();

        assertThat(maxPageSize(helper)).isNull();
    }

    private static FieldSpec maxPageSize(TypeSpec helper) {
        return helper.fieldSpecs().stream()
            .filter(f -> f.name().equals(ConnectionHelperClassGenerator.MAX_PAGE_SIZE))
            .findFirst().orElse(null);
    }
}
