package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.InventoryRecord;

/**
 * Execution-tier fixture: a {@code @service} input bean whose member is a jOOQ generated
 * {@link InventoryRecord} decoded from an {@code ID! @nodeId(typeName: "InventoryItem")} field. The
 * record path calls {@code NodeIdEncoder.decodeColumns} directly, so an id in InventoryItem's legacy
 * key shape reaching it proves the conversion covers a caller no decode helper reaches.
 */
public record InventoryRecordAssignment(InventoryRecord inventory) {
}
