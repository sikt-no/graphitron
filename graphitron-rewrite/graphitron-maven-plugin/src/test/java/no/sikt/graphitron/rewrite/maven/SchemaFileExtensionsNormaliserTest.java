package no.sikt.graphitron.rewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link AbstractRewriteMojo#effectiveSchemaFileExtensions}: the seam where
 * raw {@code <schemaFileExtensions>} values get normalised before flowing into
 * {@link no.sikt.graphitron.rewrite.RewriteContext}.
 */
class SchemaFileExtensionsNormaliserTest {

    @Test
    void nullInput_returnsDefault() throws Exception {
        var mojo = mojoWith(null);

        assertThat(mojo.effectiveSchemaFileExtensions())
            .containsExactlyInAnyOrder(".graphqls", ".graphql");
    }

    @Test
    void missingDot_isPrepended() throws Exception {
        var mojo = mojoWith(List.of("graphqls"));

        assertThat(mojo.effectiveSchemaFileExtensions()).containsExactly(".graphqls");
    }

    @Test
    void duplicates_collapseSilently() throws Exception {
        var mojo = mojoWith(List.of(".graphqls", "graphqls", ".graphqls"));

        assertThat(mojo.effectiveSchemaFileExtensions()).containsExactly(".graphqls");
    }

    @Test
    void whitespaceTrimmed() throws Exception {
        var mojo = mojoWith(List.of("  .graphqls  ", "\tgraphql\n"));

        assertThat(mojo.effectiveSchemaFileExtensions())
            .containsExactlyInAnyOrder(".graphqls", ".graphql");
    }

    @Test
    void allEntriesBlank_rejected() {
        var mojo = mojoWith(Arrays.asList("", "   ", null));

        assertThatThrownBy(mojo::effectiveSchemaFileExtensions)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<schemaFileExtensions>")
            .hasMessageContaining("at least one entry");
    }

    @Test
    void explicitlyEmpty_rejected() {
        var mojo = mojoWith(List.of());

        assertThatThrownBy(mojo::effectiveSchemaFileExtensions)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<schemaFileExtensions>");
    }

    private static AbstractRewriteMojo mojoWith(List<String> configured) {
        var mojo = new GenerateMojo();
        mojo.schemaFileExtensions = configured;
        return mojo;
    }
}
