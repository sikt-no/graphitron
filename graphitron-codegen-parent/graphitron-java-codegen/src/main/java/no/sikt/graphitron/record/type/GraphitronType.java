package no.sikt.graphitron.record.type;

import graphql.schema.GraphQLNamedType;

/**
 * Classifies every named GraphQL type. Determines what Graphitron generates for a type
 * and is the authoritative source of source context for all fields defined on it.
 */
public sealed interface GraphitronType
    permits TableType, ResultType, RootType, TableInterfaceType, InterfaceType, UnionType {

    GraphQLNamedType definition();

    default String name() {
        return definition().getName();
    }
}
