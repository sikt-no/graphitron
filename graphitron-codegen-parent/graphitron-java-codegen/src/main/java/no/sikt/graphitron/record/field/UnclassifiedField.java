package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A field that does not match any known type. A schema containing unclassified fields is invalid —
 * Graphitron terminates with an error identifying which fields need to be fixed.
 */
public record UnclassifiedField(String name, SourceLocation location) implements GraphitronField {}
