package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLNamedType;

import java.util.Set;

/**
 * One gathered fact's producer: subscribes to the subject kinds it reads
 * ({@link #kinds()}) and accumulates its own typed relation as the shared traversal delivers
 * subjects. The traversal is the shared half; the sink is deliberately not shared. Each visitor
 * owns a private typed accumulator and exposes its relation through its own accessor, and
 * {@link GatheredFacts#gather} lands each relation in a named, typed slot, so no reader ever
 * downcasts out of a keyed bag.
 *
 * <p>The interface is sealed and {@link GatheredFacts#gather}'s slot-filling switch is total
 * over the permits with no default, so registering a new visitor without wiring its output slot
 * is a compile error, not a silently missing fact. That is the compile-checked half of the
 * safety story; the registry-coverage meta-test and each fact's population pin carry the rest.
 *
 * <p>Callbacks default to no-ops so a visitor implements exactly the kinds it subscribes; the
 * dispatcher consults {@link #kinds()} and never calls an unsubscribed callback.
 */
public sealed interface FactVisitor permits PaginationFactVisitor, ConditionFactVisitor,
        OrderByFactVisitor, LookupFactVisitor, ServiceFactVisitor, WriteFactVisitor {

    /** The subject kinds this visitor gathers from. */
    Set<FactSubjectKind> kinds();

    /** A reachable output composite (object or interface type). */
    default void visitOutputType(GraphQLNamedType type) {}

    /** One field coordinate on a reachable output composite. */
    default void visitFieldCoordinate(String parentTypeName, GraphQLFieldDefinition fieldDef) {}

    /** One member field of a reachable input object type. */
    default void visitInputObjectField(GraphQLInputObjectType parent, GraphQLInputObjectField field) {}
}
