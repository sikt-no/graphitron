package no.sikt.graphitron.rewrite.maven;

/**
 * POM XML binding for a single {@code <schemaInput>} entry.
 * Maven populates these fields from the plugin configuration XML.
 * {@link AbstractRewriteMojo#buildSchemaRecipe} decodes a list of these into the graph's
 * {@link no.sikt.graphitron.model.schema.input.SchemaRecipe}, and
 * {@link AbstractRewriteMojo#expandRecipe} expands that recipe into
 * {@link no.sikt.graphitron.model.schema.input.SchemaInput} records. One seam rather than two:
 * a separate expander re-read these beans and re-collapsed the same empty strings, agreeing with
 * the decode only because both were written to agree.
 */
public class SchemaInputBinding {
    /** Ant-style glob pattern relative to the project basedir. */
    String pattern;
    /** Optional tag applied to all elements defined in matched files. */
    String tag;
    /** Optional description note appended to all elements defined in matched files. */
    String descriptionNote;
}
