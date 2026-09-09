package no.sikt.graphitron.rewrite;

import org.jooq.Record;

/**
 * Fixture: the polymorphic member at the top of the jOOQ record hierarchy, bare {@code Record}. The
 * widest slot the assignability rule admits, and the one that proves the rule is the record's own
 * declared ancestry rather than a closed list of two or three names.
 */
public record TestNodeIdPolymorphicPlainRecordBean(Record occupant) {
}
