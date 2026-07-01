package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

/**
 * Thrown by {@link no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader#load} when the GraphQL
 * parser rejects a syntactically invalid schema (graphql-java's
 * {@link graphql.parser.InvalidSyntaxException}). A parse failure is upstream of classification
 * and validation, so it carries no {@link ValidationError} verdict; it is a sibling of
 * {@link ValidationFailedException}, not a subtype, and the two ride parallel clean surfaces in
 * the dev-watch loop (parse failure vs validator verdict).
 *
 * <p>{@link #getMessage()} is the file-attributed one-liner
 * ("Schema parse failed in &lt;file&gt; at line N column M: &lt;brief&gt;") that
 * {@code RewriteSchemaLoader} builds, so a generic {@code catch (RuntimeException)} site (the
 * dev goal's quiet catalog-refresh paths) prints the attributed location without unpacking this
 * type. The dev-loop pass arm logs the same message without the throwable, replacing the
 * graphql-java + executor stack trace this exception otherwise carries.
 *
 * <p>{@link #location()} (nullable) and {@link #brief()} carry the structured failure site,
 * positioned for a future LSP red-squiggle surface; the dev-loop catch arm only reads
 * {@link #getMessage()}.
 */
@SuppressWarnings("serial") // thrown and caught in-process; SourceLocation is not Serializable
public class SchemaParseException extends RuntimeException {

    private final SourceLocation location;
    private final String brief;

    public SchemaParseException(String attributedMessage, String brief, SourceLocation location, Throwable cause) {
        super(attributedMessage, cause);
        this.location = location;
        this.brief = brief;
    }

    /** The offending source location (file + line + column), or {@code null} if the parser reported none. */
    public SourceLocation location() {
        return location;
    }

    /** The first-sentence reason, stripped of the redundant offending-token tail. */
    public String brief() {
        return brief;
    }
}
