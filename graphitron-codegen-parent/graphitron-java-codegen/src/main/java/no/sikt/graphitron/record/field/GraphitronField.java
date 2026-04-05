package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * Classifies every field in a GraphQL schema. The sealed hierarchy mirrors the field taxonomy.
 * Every leaf type is a Java record carrying the properties needed for code generation.
 */
public sealed interface GraphitronField
    permits RootField, ChildField, GraphitronField.NotGeneratedField, GraphitronField.UnclassifiedField {

    /** The name of the parent GraphQL type that defines this field. */
    String parentTypeName();

    String name();

    /** SDL source location, or {@code null} for runtime-wired fields with no SDL definition. */
    SourceLocation location();

    /**
     * A field annotated with {@code @notGenerated}. Classified but no data fetcher is generated.
     */
    record NotGeneratedField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements GraphitronField {}

    /**
     * A field that does not match any known type. A schema containing unclassified fields is invalid —
     * Graphitron terminates with an error identifying which fields need to be fixed.
     */
    record UnclassifiedField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements GraphitronField {}
}
