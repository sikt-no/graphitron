package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.CustomerRecord;

/**
 * Fixture: the polymorphic member mistyped as <em>one</em> candidate's record. Refused rather than
 * resolved: decoding the one and rejecting the others is the single-type behaviour the author
 * declined by naming the container, so the refusal points at naming the type instead.
 */
public record TestNodeIdPolymorphicOneMemberBean(CustomerRecord occupant) {
}
