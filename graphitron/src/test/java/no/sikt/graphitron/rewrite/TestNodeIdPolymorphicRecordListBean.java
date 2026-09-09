package no.sikt.graphitron.rewrite;

import org.jooq.UpdatableRecord;

import java.util.List;

/**
 * Fixture: the list shape of the polymorphic member, {@code [ID!]} into
 * {@code List<UpdatableRecord<?>>}, where each element decodes on its own type prefix.
 */
public record TestNodeIdPolymorphicRecordListBean(List<UpdatableRecord<?>> occupants) {
}
