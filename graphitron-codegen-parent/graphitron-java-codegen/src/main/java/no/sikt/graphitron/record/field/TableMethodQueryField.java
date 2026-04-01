package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field using {@code @tableMethod} — the developer provides a pre-filtered {@code Table<?>}.
 */
public record TableMethodQueryField(String name, SourceLocation location) implements QueryField {}
