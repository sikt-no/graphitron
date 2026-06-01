package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;

/**
 * R195 fixture: a {@code @service} input bean whose member is typed as one jOOQ record
 * ({@link FilmActorRecord}) while the backing SDL field's {@code @nodeId(typeName:)} points at a
 * <em>different</em> NodeType ({@code Film}, whose {@code @table} is {@code film} → {@code FilmRecord}).
 *
 * <p>A NodeId decodes into the record of its own {@code @table}, so this record-type / typeName
 * mismatch must be rejected at generation time. Without the gate it surfaces only downstream as a
 * javac "incompatible types" error in the consumer's {@code *Fetchers} (the reported
 * {@code List<SoknadSoknadsbehandlingTaggRecord>} vs {@code List<SoknadsbehandlingTaggRecord>} case).
 */
public record TestNodeIdMismatchedRecordBean(FilmActorRecord film) {
}
