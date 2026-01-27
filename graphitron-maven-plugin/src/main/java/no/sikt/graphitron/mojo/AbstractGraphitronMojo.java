package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;
import java.util.Set;

/**
 * Abstract base class for Graphitron Maven plugin mojos.
 * Contains shared configuration parameters for schema processing.
 */
public abstract class AbstractGraphitronMojo extends AbstractMojo {

    /**
     * The comma-separated locations of the schema files to process.
     */
    @Parameter(property = "graphitron.schemaFiles", defaultValue = "${project.basedir}/target/generated-resources/schema.graphql", required = true)
    protected Set<String> schemaFiles;

    /**
     * The package where jOOQ generated code resides.
     * Required for validating database references.
     */
    @Parameter(property = "graphitron.jooqGeneratedPackage")
    protected String jooqGeneratedPackage;

    /**
     * Whether to enable Node strategy.
     */
    @Parameter(property = "graphitron.makeNodeStrategy", defaultValue = "false")
    protected boolean makeNodeStrategy;

    /**
     * Whether type ID is required on Node types.
     */
    @Parameter(property = "graphitron.experimental_requireTypeIdOnNode", defaultValue = "false")
    protected boolean experimental_requireTypeIdOnNode;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "graphitron.externalReferences")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> externalReferences;


    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "graphitron.externalReferenceImports")
    @SuppressWarnings("unused")
    private Set<String> externalReferenceImports;


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