package no.sikt.graphitron.rewrite.test.services;

/**
 * R1 Phase 2f fixture: a free-form (non-table-backed) {@code @record} payload type used by
 * {@code MutationPayloadLifterTest} to exercise the {@code @batchKeyLifter} directive end-to-end.
 *
 * <p>The schema's {@code CreateFilmPayload} type points at this record via
 * {@code @record(record: {className: "..."})}. The payload's {@code language} child field carries
 * {@code @batchKeyLifter}, with {@link CreateFilmPayloadLifter#liftLanguageId} extracting the
 * {@code language_id} key for each parent row.
 */
public record CreateFilmPayload(Integer languageId) {}
