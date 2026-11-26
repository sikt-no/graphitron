package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The connection a frame runs its body on, mapped once from the connection the store states for its
 * producer, by what the frame's body is: the request's own where the store states none by design,
 * else the routed, inherited or unscoped key. A body kind is how the frame calls its body and what it
 * can run on, not what produces it: a read is a statement graphitron writes and a routine called as one
 * alike. Each body kind admits the outcomes it can run on, and an outcome it cannot is a generator bug,
 * never a guessed connection; an outcome the store gains later fails to compile here rather than
 * falling into a known arm.
 */
public final class FrameConnections {

    private FrameConnections() {}

    /**
     * The generated classes a routed fetcher calls into: the connection carrier, the key it is acquired
     * and handed down by, the client error a call naming no tenant is refused with, and the node id
     * encoder a tenant inside an id is decoded by. Named only where the store states a routed call.
     */
    public record Routing(ClassName ownedConnections, ClassName connectionKey, ClassName clientException,
                          ClassName nodeIdEncoder) {
        public Routing {
            Objects.requireNonNull(ownedConnections, "ownedConnections");
            Objects.requireNonNull(connectionKey, "connectionKey");
            Objects.requireNonNull(clientException, "clientException");
            Objects.requireNonNull(nodeIdEncoder, "nodeIdEncoder");
        }
    }

    /**
     * The connection {@code frame}'s body runs on, off {@code row}, the connection the store states for
     * its producer: a service's any of the four, the request's own where the store states it none by
     * design ({@code unkeyed}) and a generator bug where it states none otherwise, the store refusing
     * such a service; a routine write's the request's own or the key with an empty tenant part, since it
     * mints no tenant slot and inherits no key; a read's the request's own, the empty tenant part or the
     * routed key.
     *
     * @param tenantType the Java type the graph's tenant columns agree on, where a routed key is built
     */
    public static FrameEmitter.Connection of(FrameQuery.Row frame, Optional<ConnectionQuery.Row> row,
                                             boolean unkeyed, Optional<String> tenantType, Routing routing,
                                             String fieldCoordinate) {
        return switch (frame.bodyKind()) {
            case SERVICE -> service(frame, row, unkeyed, tenantType, routing, fieldCoordinate);
            case ROUTINE_WRITE -> routineWrite(row, fieldCoordinate, routing);
            case READ -> read(frame, row, tenantType, routing, fieldCoordinate);
        };
    }

    /**
     * A read's, a statement graphitron writes or a routine called as one: the request's own where the
     * store states none, as in a graph with no tenant column, the key with an empty tenant part for a
     * read of global tables, and the routed key for one of a tenant's. A root inherits no key, so an
     * inherited one is drift.
     */
    private static FrameEmitter.Connection read(FrameQuery.Row frame, Optional<ConnectionQuery.Row> stated,
                                                Optional<String> tenantType, Routing routing,
                                                String fieldCoordinate) {
        if (stated.isEmpty()) {
            return new FrameEmitter.Connection.Request();
        }
        var row = stated.get();
        return switch (row.outcome()) {
            case UNSCOPED -> new FrameEmitter.Connection.Unscoped(routing.ownedConnections(), routing.connectionKey());
            case ROUTED -> routed(frame, row, tenantType, routing, fieldCoordinate);
            case INHERIT -> throw new IllegalStateException("Graphitron generator bug: the store states INHERIT"
                + " for the read " + fieldCoordinate + " at a root, which has no parent to inherit a key from");
        };
    }

    private static FrameEmitter.Connection service(FrameQuery.Row frame, Optional<ConnectionQuery.Row> stated,
                                                   boolean unkeyed, Optional<String> tenantType, Routing routing,
                                                   String fieldCoordinate) {
        if (stated.isEmpty()) {
            if (!unkeyed) {
                throw new IllegalStateException("Graphitron generator bug: @service " + fieldCoordinate
                    + " has no stored connection in a graph whose connection key has a part, so nothing"
                    + " says which connection it runs on; the store refuses such a service, so reaching"
                    + " it is a generator bug");
            }
            return new FrameEmitter.Connection.Request();
        }
        var row = stated.get();
        return switch (row.outcome()) {
            case INHERIT -> new FrameEmitter.Connection.Inherited(routing.ownedConnections());
            case UNSCOPED -> new FrameEmitter.Connection.Unscoped(routing.ownedConnections(), routing.connectionKey());
            case ROUTED -> routed(frame, row, tenantType, routing, fieldCoordinate);
        };
    }

    /** The routed key: the tenant read off the stored slots, and what a call naming none gets. */
    private static FrameEmitter.Connection routed(FrameQuery.Row frame, ConnectionQuery.Row row,
                                                  Optional<String> tenantType, Routing routing,
                                                  String fieldCoordinate) {
        return new FrameEmitter.Connection.Routed(
            ClassName.bestGuess(tenantType.orElseThrow(() -> new IllegalStateException(frame.coordinate()
                + " is routed, but the graph's tenant columns agree on no one type, which the store refuses"))),
            routing.ownedConnections(), routing.connectionKey(),
            row.slots().stream().map(slot -> slotRead(slot, routing)).toList(),
            noTenant(row, fieldCoordinate, routing));
    }

    /**
     * A routine write's: the request's own where the store states none, as in a graph with no tenant
     * column, and else the key with an empty tenant part, the default source. A routine write mints no
     * tenant slot, so the store routes none, and a root inherits no key; either is drift, refused
     * rather than run on a guessed connection.
     */
    private static FrameEmitter.Connection routineWrite(Optional<ConnectionQuery.Row> row, String fieldCoordinate,
                                                        Routing routing) {
        if (row.isEmpty()) {
            return new FrameEmitter.Connection.Request();
        }
        return switch (row.get().outcome()) {
            case UNSCOPED -> new FrameEmitter.Connection.Unscoped(routing.ownedConnections(), routing.connectionKey());
            case ROUTED, INHERIT -> throw new IllegalStateException(
                "Graphitron generator bug (routine-write connection): the store states "
                + row.get().outcome() + " for the routine write " + fieldCoordinate + ", which mints no"
                + " tenant slot and inherits no key; it runs on the request's connection or the"
                + " default source");
        };
    }

    /** What a routed call whose values name no tenant gets, as its tenant part states it. */
    private static FrameEmitter.NoTenant noTenant(ConnectionQuery.Row row, String fieldCoordinate, Routing routing) {
        return switch (row.whenEmpty().orElseThrow(() -> new IllegalStateException(row.coordinate()
            + " is routed with no tenant part"))) {
            case UNREACHABLE -> new FrameEmitter.NoTenant.Unreachable();
            case EMPTY -> new FrameEmitter.NoTenant.Empty(emptyValue(row));
            case REFUSE -> new FrameEmitter.NoTenant.Refuse(routing.clientException(),
                noTenantMessage(fieldCoordinate, row.slots().stream()
                    .map(slot -> slot.names().getFirst()).distinct().toList()));
        };
    }

    /**
     * The client-facing refusal for a call whose lists name no tenant:
     * {@code Mutation.rateFilms names no tenant: its tenant is read from the elements of 'in', which
     * is empty.}
     */
    private static String noTenantMessage(String coordinate, List<String> arguments) {
        var quoted = arguments.stream().map(a -> "'" + a + "'").toList();
        String names = quoted.size() == 1
            ? quoted.getFirst()
            : String.join(", ", quoted.subList(0, quoted.size() - 1)) + " and " + quoted.getLast();
        return coordinate + " names no tenant: its tenant is read from the elements of " + names
            + (quoted.size() == 1 ? ", which is empty." : ", which are empty.");
    }

    /** The producer's empty answer, as the store states it: an empty list, set or result. */
    private static CodeBlock emptyValue(ConnectionQuery.Row row) {
        var empty = row.empty().orElseThrow(() -> new IllegalStateException(row.coordinate()
            + " answers an empty call empty, but its producer states no empty answer"));
        return switch (empty.kind()) {
            case LIST -> CodeBlock.of("$T.of()", List.class);
            case SET -> CodeBlock.of("$T.of()", Set.class);
            case RESULT -> CodeBlock.of("$T.using($T.DEFAULT).newResult($T.$L)",
                ClassName.get("org.jooq.impl", "DSL"), ClassName.get("org.jooq", "SQLDialect"),
                ClassName.bestGuess(empty.tablesClass().orElseThrow()), empty.tableField().orElseThrow());
        };
    }

    /**
     * How one value the tenant is read from is read: the argument, and where it lies under an input
     * object, the path below it, read over every element of a list it crosses; and where it is a node
     * id, the tenant column's value out of each id, decoded strictly: an id that is not one of the
     * node's is the client error a {@code @nodeId} argument raises, never a value naming no tenant.
     * A value that is no id at all, absent or not a string, names none.
     */
    private static CodeBlock slotRead(ConnectionQuery.Slot slot, Routing routing) {
        var names = slot.names();
        // Read as an Object: inside the fold's varargs a bare generic read is inferred as the array.
        var read = names.size() == 1
            ? CodeBlock.of("env.<$T>getArgument($S)", Object.class, names.getFirst())
            : CodeBlock.of("$T.tenantSlot(env.getArgument($S), $L)", routing.ownedConnections(), names.getFirst(),
                CodeBlock.join(names.subList(1, names.size()).stream().map(n -> CodeBlock.of("$S", n)).toList(),
                    ", "));
        if (slot.node().isEmpty()) {
            return read;
        }
        // An expression, since the read sits inside the fold's own statement.
        var decode = NodeIdHelperNames.decodeOrThrow(slot.node().get().typeName());
        return CodeBlock.of("$T.mapTenantLeaves($L, id -> id instanceof $T s ? $T.$L(s).value$L() : null)",
            routing.ownedConnections(), read, String.class, routing.nodeIdEncoder(), decode,
            slot.keyPosition().orElseThrow() + 1);
    }
}
