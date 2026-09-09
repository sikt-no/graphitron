package no.sikt.graphitron.rewrite;

import org.jooq.UpdatableRecord;

/**
 * Fixture: a {@code @service} input bean whose member is typed as a jOOQ record <em>supertype</em>,
 * backed by an {@code ID! @nodeId(typeName: "<container>")} SDL field naming a multitable interface
 * or union. The reported shape: the service takes the id of any implementation and dispatches on the
 * record's runtime class, so the member cannot be one implementation's record.
 */
public record TestNodeIdPolymorphicRecordBean(UpdatableRecord<?> occupant) {
}
