package no.sikt.graphitron.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.Set;

/**
 * Abstract base class for Graphitron Maven mojos containing shared configuration parameters.
 * Both {@link GenerateMojo} and {@link ValidateMojo} extend this class to share common settings.
 */
public abstract class AbstractGraphitronMojo extends AbstractMojo {

    /**
     * The comma-separated locations of the schema files to use.
     */
    @Parameter(property = "generate.schemaFiles", defaultValue = "${project.basedir}/target/generated-resources/schema.graphql", required = true)
    private Set<String> schemaFiles;

    /**
     * The package where jOOQ generated code resides.
     */
    @Parameter(property = "generate.jooqGeneratedPackage")
    private String jooqGeneratedPackage;

    /**
     * Whether to generate Node ID strategy support.
     */
    @Parameter(property = "generate.makeNodeStrategy", defaultValue = "false")
    private boolean makeNodeStrategy;

    /**
     * Whether type ID is required on Node types.
     */
    @Parameter(property = "generate.experimental_requireTypeIdOnNode", defaultValue = "false")
    private boolean requireTypeIdOnNode;

    public Set<String> getSchemaFiles() {
        return schemaFiles;
    }

    public String getJooqGeneratedPackage() {
        return jooqGeneratedPackage;
    }

    public boolean makeNodeStrategy() {
        return makeNodeStrategy;
    }

    public boolean requireTypeIdOnNode() {
        return requireTypeIdOnNode;
    }

    // Setters for testing
    public void setSchemaFiles(Set<String> schemaFiles) {
        this.schemaFiles = schemaFiles;
    }

    public void setJooqGeneratedPackage(String jooqGeneratedPackage) {
        this.jooqGeneratedPackage = jooqGeneratedPackage;
    }
}
