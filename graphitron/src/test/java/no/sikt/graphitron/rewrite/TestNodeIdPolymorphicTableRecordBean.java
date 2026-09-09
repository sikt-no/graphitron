package no.sikt.graphitron.rewrite;

import org.jooq.TableRecord;

/**
 * Fixture: the same polymorphic member one supertype up, {@code TableRecord<?>}, which is what an
 * author whose candidates include a primary-key-less table has to write.
 */
public record TestNodeIdPolymorphicTableRecordBean(TableRecord<?> occupant) {
}
