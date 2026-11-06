package no.sikt.graphitron.emit.stub;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.InventoryRecord;

/**
 * A record whose members are jOOQ records node ids decode into: a film by its own key, and an
 * inventory row through its foreign keys, which two node ids of different types fill together.
 */
public record Stock(FilmRecord film, InventoryRecord inventory) {}
