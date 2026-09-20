package no.sikt.graphitron.model.schema;

import graphql.language.SourceLocation;

/** The declaration site an element hangs off: the monomorphic contributed-by reference. */
public record SiteRef(String typeName, SourceLocation location) {}
