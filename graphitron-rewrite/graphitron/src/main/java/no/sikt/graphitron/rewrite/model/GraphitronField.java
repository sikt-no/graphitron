package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.RejectionKind;

/**
 * Classifies every field in a GraphQL schema. The sealed hierarchy mirrors the field taxonomy.
 * Every leaf type is a Java record carrying the properties needed for code generation.
 */
public sealed interface GraphitronField
    permits RootField, ChildField, InputField, GraphitronField.UnclassifiedField {

    /** The name of the parent GraphQL type that defines this field. */
    String parentTypeName();

    String name();

    /** Qualified name in {@code ParentType.fieldName} form, for use in error messages. */
    default String qualifiedName() {
        return parentTypeName() + "." + name();
    }

    /** SDL source location, or {@code null} for runtime-wired fields with no SDL definition. */
    SourceLocation location();

    /**
     * A field that could not be classified. A schema containing unclassified fields is invalid —
     * Graphitron terminates with an error identifying which fields need to be fixed.
     *
     * <p>{@code definition} is the original {@link GraphQLFieldDefinition} from the assembled
     * schema, providing full directive and argument context for rich error messages. May be
     * {@code null} when the field is constructed outside the schema-building pipeline (e.g. in
     * tests).
     *
     * <p>{@code rejection} is the sealed-variant explanation of why classification failed:
     * an {@link Rejection.AuthorError}, an {@link Rejection.InvalidSchema}, or a
     * {@link Rejection.Deferred}. The validator projects it to a {@link RejectionKind} for the
     * {@code [<kind>] <message>} log prefix and surfaces the variant's
     * {@link Rejection#message()} as the prose tail.
     */
    record UnclassifiedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        GraphQLFieldDefinition definition,
        Rejection rejection
    ) implements GraphitronField {

        /** Convenience for log formatters that don't need the structured data. */
        public String reason() { return rejection.message(); }

        /** Convenience for log formatters that need just the kebab-case kind. */
        public RejectionKind kind() { return RejectionKind.of(rejection); }
    }
}
