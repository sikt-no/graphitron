package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field using {@code @tableMethod} — the developer provides a pre-filtered {@code Table<?>}.
 */
public record TableMethodField(String name, SourceLocation location) implements ChildField {}
