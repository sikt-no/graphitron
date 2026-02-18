package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.List;
import java.util.Set;

/**
 * Abstract base class for Graphitron Maven plugin mojos that process schemas.
 * Contains shared configuration parameters for schema validation and code generation.
 * <p>
 * Note: {@link TransformMojo} does not extend this class as it has different concerns.
 */
public abstract class AbstractGraphitronMojo extends AbstractMojo {

    /**
     * The Maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    protected MavenProject project;

    /**
     * The locations of the schema files to process.
     * For generate goal without transform config, these are pre-transformed schema files.
     * For generate goal with transform config, this is set programmatically from transform output.
     */
    @Parameter(property = "graphitron.schemaFiles", defaultValue = "${project.basedir}/target/generated-resources/graphql_transformer/generator-schema.graphql")
    protected Set<String> schemaFiles;

    /**
     * The package where jOOQ generated code resides.
     * Required for validating database references.
     */
    @Parameter(property = "graphitron.jooqGeneratedPackage")
    protected String jooqGeneratedPackage;

    /**
     * Whether to enable Node ID strategy for Relay Global Object Identification.
     */
    @Parameter(property = "graphitron.makeNodeStrategy", defaultValue = "false")
    protected boolean makeNodeStrategy;

    /**
     * Whether type ID is required on Node types (experimental).
     */
    @Parameter(property = "graphitron.experimental_requireTypeIdOnNode", defaultValue = "false")
    protected boolean experimental_requireTypeIdOnNode;

    /**
     * External reference classes that can be used in code generation.
     */
    @Parameter(property = "graphitron.externalReferences")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> externalReferences;

    /**
     * Package imports for external references.
     */
    @Parameter(property = "graphitron.externalReferenceImports")
    @SuppressWarnings("unused")
    private Set<String> externalReferenceImports;

    public MavenProject getProject() {
        return project;
    }

    public Set<String> getSchemaFiles() {
        return schemaFiles;
    }

    public void setSchemaFiles(Set<String> schemaFiles) {
        this.schemaFiles = schemaFiles;
    }

    public String getJooqGeneratedPackage() {
        return jooqGeneratedPackage;
    }

    public void setJooqGeneratedPackage(String jooqGeneratedPackage) {
        this.jooqGeneratedPackage = jooqGeneratedPackage;
    }

    public boolean makeNodeStrategy() {
        return makeNodeStrategy;
    }

    public boolean requireTypeIdOnNode() {
        return experimental_requireTypeIdOnNode;
    }

    public List<? extends ExternalReference> getExternalReferences() {
        return externalReferences;
    }

    public Set<String> getExternalReferenceImports() {
        return externalReferenceImports;
    }
}
