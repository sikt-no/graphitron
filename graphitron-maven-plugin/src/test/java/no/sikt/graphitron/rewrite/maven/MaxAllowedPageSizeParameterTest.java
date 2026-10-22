package no.sikt.graphitron.rewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link AbstractRewriteMojo#effectiveMaxAllowedPageSize}: an omitted
 * {@code <maxAllowedPageSize>} is no maximum, and a value below 1 fails the goal naming the
 * parameter rather than reaching the context's invariant.
 */
class MaxAllowedPageSizeParameterTest {

    @Test
    void omitted_isNoMaximum() throws Exception {
        assertThat(mojoWith(null).effectiveMaxAllowedPageSize()).isEmpty();
    }

    @Test
    void configured_isCarried() throws Exception {
        assertThat(mojoWith(1000).effectiveMaxAllowedPageSize()).hasValue(1000);
    }

    @Test
    void zero_rejected() {
        assertThatThrownBy(mojoWith(0)::effectiveMaxAllowedPageSize)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessage("<maxAllowedPageSize> must be at least 1 (was: 0)");
    }

    @Test
    void negative_rejected() {
        assertThatThrownBy(mojoWith(-1)::effectiveMaxAllowedPageSize)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<maxAllowedPageSize>");
    }

    private static AbstractRewriteMojo mojoWith(Integer configured) {
        var mojo = new GenerateMojo();
        mojo.maxAllowedPageSize = configured;
        return mojo;
    }
}
