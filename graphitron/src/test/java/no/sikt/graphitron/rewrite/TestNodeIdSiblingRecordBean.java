package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;

/**
 * Fixture: a {@code @service} input bean with two jOOQ {@code FilmActorRecord} members, each backed
 * by an SDL field naming a <em>different</em> {@code @node} type over {@code film_actor}. One record
 * class, two node types, so the decode identity cannot be the record class: each member must decode
 * against the typeId of the type its own {@code @nodeId(typeName:)} names.
 */
public record TestNodeIdSiblingRecordBean(FilmActorRecord filmActor, FilmActorRecord filmActorFed) {
}
