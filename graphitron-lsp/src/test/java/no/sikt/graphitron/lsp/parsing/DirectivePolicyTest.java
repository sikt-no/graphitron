package no.sikt.graphitron.lsp.parsing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the per-directive policy contract that completions, diagnostics, hover,
 * and goto-definition all read through {@link DirectivePolicy}. Before this
 * table the {@code @record} carve-out was copy-pasted as {@code "record".equals(...)}
 * into five consumers and the method-validating set lived privately in
 * {@code Diagnostics}; these tests are the regression oracle for the single home.
 */
class DirectivePolicyTest {

    @Test
    @DisplayName("@record is the only directive whose class slot binds nothing live (R307)")
    void recordBindsNoLiveClass() {
        assertThat(DirectivePolicy.bindsLiveClass("record")).isFalse();
        // The coordinate is shared with @enum, which DOES bind a live class.
        assertThat(DirectivePolicy.bindsLiveClass("enum")).isTrue();
        assertThat(DirectivePolicy.bindsLiveClass("service")).isTrue();
    }

    @Test
    @DisplayName("method-validating directives bind a live method invocation")
    void methodValidatingDirectives() {
        assertThat(DirectivePolicy.bindsLiveMethod("service")).isTrue();
        assertThat(DirectivePolicy.bindsLiveMethod("condition")).isTrue();
        assertThat(DirectivePolicy.bindsLiveMethod("externalField")).isTrue();
        assertThat(DirectivePolicy.bindsLiveMethod("tableMethod")).isTrue();
        assertThat(DirectivePolicy.bindsLiveMethod("reference")).isTrue();
        assertThat(DirectivePolicy.bindsLiveMethod("sourceRow")).isTrue();
    }

    @Test
    @DisplayName("@record / @enum method slots wrap a type, not a live method")
    void typeWrappingDirectivesSkipMethodValidation() {
        assertThat(DirectivePolicy.bindsLiveMethod("record")).isFalse();
        assertThat(DirectivePolicy.bindsLiveMethod("enum")).isFalse();
    }
}
