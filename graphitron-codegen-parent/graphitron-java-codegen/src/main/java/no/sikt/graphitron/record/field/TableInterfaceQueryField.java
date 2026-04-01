package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field whose return type is a single-table interface ({@code @table} + {@code @discriminate}).
 */
public record TableInterfaceQueryField(String name, SourceLocation location) implements QueryField {}
