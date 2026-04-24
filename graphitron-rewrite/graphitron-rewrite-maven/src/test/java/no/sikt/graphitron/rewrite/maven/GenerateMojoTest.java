package no.sikt.graphitron.rewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerateMojoTest {

    @Test
    void buildContext_allEightParametersRoundTrip(@TempDir Path basedir) throws Exception {
        var mojo = mojo(basedir);
        mojo.outputDirectory = basedir.resolve("target/generated").toString();
        mojo.maxAllowedPageSize = 50;

        var ref = new NamedReferenceBinding();
        ref.name = "MyRef";
        ref.className = "com.example.MyRef";
        mojo.namedReferences = List.of(ref);

        var scalar = new ScalarBinding();
        scalar.scalarName = "DateTime";
        scalar.className = "java.time.Instant";
        mojo.scalars = List.of(scalar);

        var ctx = mojo.buildContext();

        assertThat(ctx.outputPackage()).isEqualTo("com.example.generated");
        assertThat(ctx.jooqPackage()).isEqualTo("com.example.jooq");
        assertThat(ctx.basedir()).isEqualTo(basedir);
        assertThat(ctx.outputDirectory()).isEqualTo(Path.of(basedir.resolve("target/generated").toString()));
        assertThat(ctx.maxAllowedPageSize()).isEqualTo(50);
        assertThat(ctx.namedReferences()).containsEntry("MyRef", "com.example.MyRef");
        assertThat(ctx.scalars()).hasSize(1);
        assertThat(ctx.scalars().get(0).scalarName()).isEqualTo("DateTime");
        assertThat(ctx.scalars().get(0).className()).isEqualTo("java.time.Instant");
        assertThat(ctx.schemaInputs()).isEmpty();
    }

    @Test
    void schemaInputs_roundTripIntoSchemaInputList(@TempDir Path basedir) throws Exception {
        Files.createFile(basedir.resolve("schema.graphqls"));
        var mojo = mojo(basedir);

        var b = new SchemaInputBinding();
        b.pattern = "schema.graphqls";
        b.tag = "api";
        b.descriptionNote = "public API";
        mojo.schemaInputs = List.of(b);

        var ctx = mojo.buildContext();

        assertThat(ctx.schemaInputs()).hasSize(1);
        assertThat(ctx.schemaInputs().get(0).tag()).isEqualTo(Optional.of("api"));
        assertThat(ctx.schemaInputs().get(0).descriptionNote()).isEqualTo(Optional.of("public API"));
        assertThat(ctx.schemaInputs().get(0).sourceName())
            .isEqualTo(basedir.resolve("schema.graphqls").toAbsolutePath().normalize().toString());
    }

    @Test
    void nullOutputPackage_throwsNullPointerException(@TempDir Path basedir) {
        var mojo = new GenerateMojo();
        var project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        mojo.project = project;
        // outputPackage deliberately null
        mojo.jooqPackage = "com.example.jooq";
        mojo.outputDirectory = basedir.resolve("target/generated").toString();

        assertThatThrownBy(mojo::buildContext)
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("outputPackage");
    }

    /** Constructs a {@link GenerateMojo} with minimal valid state. */
    private static GenerateMojo mojo(Path basedir) throws IOException {
        var mojo = new GenerateMojo();
        var project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        mojo.project = project;
        mojo.outputPackage = "com.example.generated";
        mojo.jooqPackage = "com.example.jooq";
        mojo.outputDirectory = basedir.resolve("target/generated-sources/graphitron").toString();
        return mojo;
    }
}
