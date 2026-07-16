package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.ActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

import java.util.List;

/**
 * Execution-tier fixture: a consumer-authored composite record bundling several jOOQ table
 * records, one {@link FilmRecord} plus a {@code List<}{@link ActorRecord}{@code >}. The Sakila-catalog
 * analog of the driving utdanningsregisteret shape (one {@code UtdanningsspesifikasjonRecord} plus a
 * {@code List<UtdanningsmulighetRecord>}). Returned by {@link FilmsWithActorsService#create} so the
 * two-level {@code @service} carrier classifies its data field as a {@code RecordCompositeField}
 * source-passthrough projection and its {@code @field}-mapped {@code @table} children re-fetch through
 * the record-backed accessor path.
 *
 * <p>The component names ({@code filmRecord} / {@code actorRecords}) diverge from the SDL field names
 * ({@code film} / {@code actors}); the schema bridges the divergence with {@code @field(name:)}.
 */
public record FilmWithActors(FilmRecord filmRecord, List<ActorRecord> actorRecords) {}
