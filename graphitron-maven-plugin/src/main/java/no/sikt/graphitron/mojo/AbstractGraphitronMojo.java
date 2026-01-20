package no.sikt.graphitron.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

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
    @Parameter(property = "generate.experimental_requireTypeIdOnNode", defaultValue = "false")
    protected boolean requireTypeIdOnNode;

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
        return requireTypeIdOnNode;
    }
}