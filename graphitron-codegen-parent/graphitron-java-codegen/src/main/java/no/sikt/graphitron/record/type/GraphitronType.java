package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;

/**
 * Classifies every named GraphQL type. Determines what Graphitron generates for a type
 * and is the authoritative source of source context for all fields defined on it.
 */
public sealed interface GraphitronType
    permits TableType, ResultType, RootType, TableInterfaceType, InterfaceType, UnionType {

    String name();

    /** SDL source location, or {@code null} for runtime-wired types with no SDL definition. */
    SourceLocation location();
}
