package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;

import java.util.List;

/**
 * R195 fixture: the both-dimensions corner — a {@code @service} input bean whose member is a
 * {@code List} of composite-key jOOQ {@code *Record}s ({@code film_actor}, PK
 * {@code (actor_id, film_id)}). Backs a {@code [ID!] @nodeId(typeName: "FilmActor")} SDL field, so
 * the list helper variant ({@code decodeFilmActorRecordList}) materialises one composite-key
 * {@link FilmActorRecord} per element, each via two typed {@code set} calls.
 */
public record TestNodeIdCompositeRecordListBean(List<FilmActorRecord> filmActors) {
}
