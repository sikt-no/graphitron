package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

/**
 * Execution-tier fixture: a {@code @service} input bean whose two members are both
 * {@link LanguageRecord}s, each decoded from an {@code ID! @nodeId(typeName:)} field naming a
 * different {@code @node} type over {@code language}. One record class, two node types, which is the
 * shape a node-type rename is in while both names stay exposed. Each member must be decoded by the
 * helper of the type its own field names, so a wire id belonging to the other type is refused rather
 * than loaded.
 */
public record LanguagePairAssignment(LanguageRecord language, LanguageRecord alias) {
}
