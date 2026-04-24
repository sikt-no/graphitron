package no.sikt.graphitron.rewrite.maven;

/**
 * POM XML binding for a single {@code <schemaInput>} entry.
 * Maven populates these fields from the plugin configuration XML.
 * {@link SchemaInputExpander} converts a list of these into
 * {@link no.sikt.graphitron.rewrite.schema.input.SchemaInput} records.
 */
public class SchemaInputBinding {
    /** Ant-style glob pattern relative to the project basedir. */
    String pattern;
    /** Optional tag applied to all elements defined in matched files. */
    String tag;
    /** Optional description note appended to all elements defined in matched files. */
    String descriptionNote;
}
