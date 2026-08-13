package no.sikt.graphitron.rewrite.schema;

import graphql.GraphQLError;
import graphql.language.SourceLocation;

import java.util.List;

/**
 * One verdict from the SDL toolchain's own validation of a schema document, in graphql-java's
 * vocabulary: which stage refused it, which of that stage's error classes fired, what it said, and
 * where.
 *
 * <p>Reading a schema has three stages that can refuse, and this record covers the two that judge
 * the document as a whole. Parsing judges one file at a time and reports through
 * {@link RewriteSchemaLoader.SyntaxFailure}, whose unit is the file. The two stages here have one
 * verdict vocabulary between them ({@link GraphQLError} out of graphql-java's SDL machinery), one
 * column set, and one grain, so they share a carrier and name their stage rather than splitting.
 *
 * @param stage      which stage refused the document
 * @param errorClass the refusing error's class name, graphql-java's own word for what went wrong
 *                   and the only dimension it publishes; {@code getErrorType()} is uniformly
 *                   {@code ValidationError} across the whole SDL error set and discriminates nothing
 * @param message    the error's rendered message; display material, never a dimension
 * @param location   where the error points, or {@code null} where it points nowhere. graphql-java
 *                   locates at the enclosing declaration rather than the offending element (a
 *                   missing field type locates the type, not the field), and signals "nowhere" with
 *                   a {@code (-1, -1)} sentinel that {@link #of} normalises away, so an absent
 *                   location is absent rather than sentinel-valued by the time anything reads it
 * @param cause      the error as graphql-java raised it, so a caller that still wants to fail can
 *                   reconstruct the original {@code SchemaProblem} rather than a transcription of it
 */
public record SchemaError(Stage stage, String errorClass, String message, SourceLocation location,
                          GraphQLError cause) {

    /**
     * The two document-wide stages, in the order they run. Both refuse with a
     * {@code SchemaProblem} carrying a list of errors, and both have a live population: the
     * registry stage refuses the duplicate base declarations (a second {@code type Query}, a
     * second {@code directive @d}, a second {@code schema} block), which in a multi-file workspace
     * are the errors no single file's parse can see, and the assembly stage refuses everything
     * else the SDL specification rules out.
     */
    public enum Stage {
        /** Combining every parsed source's definitions into one registry. */
        REGISTRY,
        /** Assembling the combined registry into an executable schema. */
        ASSEMBLY
    }

    /**
     * Transcribes one raw {@link GraphQLError}, taking its first location and normalising
     * graphql-java's unlocated sentinel to an absent location. Every error in the SDL error set
     * carries exactly one location, so taking the first loses nothing; the list is the shape
     * {@link GraphQLError#getLocations()} declares, not a multiplicity the errors use.
     */
    public static SchemaError of(Stage stage, GraphQLError error) {
        return new SchemaError(stage, error.getClass().getSimpleName(), error.getMessage(),
            firstLocation(error), error);
    }

    /** {@link #of} over a whole stage's error list, in the stage's own emit order. */
    public static List<SchemaError> allOf(Stage stage, List<? extends GraphQLError> errors) {
        return errors.stream().map(error -> of(stage, error)).toList();
    }

    /**
     * The error's location, or {@code null} when it reports none. Both of graphql-java's
     * "nowhere" spellings collapse here: an empty or null list, and the {@code (-1, -1)} sentinel
     * {@code BaseError} substitutes for a node with no position.
     */
    private static SourceLocation firstLocation(GraphQLError error) {
        List<SourceLocation> locations = error.getLocations();
        if (locations == null || locations.isEmpty()) {
            return null;
        }
        SourceLocation first = locations.getFirst();
        if (first == null || first.getLine() < 0 || first.getColumn() < 0) {
            return null;
        }
        return first;
    }
}
