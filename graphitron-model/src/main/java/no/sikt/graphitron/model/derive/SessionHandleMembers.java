package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.CODE_READ_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SESSION_HANDLE_MEMBER;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_HANDLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;

/**
 * The capture-cadence writer of {@code graphitron_session_handle_member}: what the class a graph
 * names as its session handle offers, one row per accessor, under the property name an author
 * writes after {@code $session.}.
 *
 * <p>The members are the slots the class declares itself, {@code declaring_class = class_name}.
 * The classpath reading writes only those for a handle class, by construction, but the predicate is
 * stated here as well because the rule belongs to this relation and not to how the reading reached
 * it: a jOOQ record handle offers the accessors its generated class declares and not the
 * {@code Record} API beneath them.
 *
 * <p>Reconciles the graph's partition: clears it, then re-derives, since the store persists across
 * rounds and an appending stage would fail on the key.
 */
public final class SessionHandleMembers {

    private SessionHandleMembers() {}

    /** Reconciles the graph's handle members: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_SESSION_HANDLE_MEMBER;
        var handle = STORE_GRAPH_SESSION_HANDLE;
        var source = STORE_GRAPH_SOURCE;
        var slot = CODE_READ_SLOT;

        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.MEMBER_NAME, target.SOURCE_NAME, target.CLASS_NAME,
                target.METHOD_NAME, target.DESCRIPTOR)
            .select(dsl
                .select(handle.GRAPH_NAME, slot.SLOT_NAME, slot.SOURCE_NAME, slot.CLASS_NAME,
                    slot.METHOD_NAME, slot.DESCRIPTOR)
                .from(handle)
                .join(source).on(source.GRAPH_NAME.eq(handle.GRAPH_NAME))
                .join(slot).on(slot.SOURCE_NAME.eq(source.SOURCE_NAME),
                    slot.CLASS_NAME.eq(handle.HANDLE_CLASS))
                .where(handle.GRAPH_NAME.eq(graphName), slot.DECLARING_CLASS.eq(slot.CLASS_NAME)))
            .execute();
    }
}
