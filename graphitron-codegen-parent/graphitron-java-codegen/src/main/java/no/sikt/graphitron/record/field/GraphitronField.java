package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * Classifies every field in a GraphQL schema. The sealed hierarchy mirrors the field taxonomy.
 * Every leaf type is a Java record carrying the properties needed for code generation.
 */
public sealed interface GraphitronField
    permits RootField, ChildField, NotGeneratedField, UnclassifiedField {

    String name();

    /** SDL source location, or {@code null} for runtime-wired fields with no SDL definition. */
    SourceLocation location();
}
