package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;

/**
 * R195 fixture: a {@code @service} input bean whose member is a jOOQ {@code *Record} backed by a
 * <em>composite-key</em> table ({@code film_actor}, PK {@code (actor_id, film_id)}). Composite-key
 * record members are deferred in v1; the classifier rejects this loudly rather than decoding into a
 * single-column key.
 */
public record TestNodeIdCompositeRecordBean(FilmActorRecord filmActor) {
}
