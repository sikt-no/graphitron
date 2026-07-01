package no.sikt.graphitron.rewrite.test.services;

/**
 * R110 fixture: a free-form (non-table-backed) {@code @record} payload type used by
 * {@code MutationPayloadLifterTest} to exercise the {@code @sourceRow} directive (leaf-PK
 * arm) end-to-end.
 *
 * <p>The schema's {@code CreateFilmPayload} type points at this record via
 * {@code @record(record: {className: "..."})}. The payload's {@code language} child field
 * carries {@code @sourceRow}; {@link CreateFilmPayloadLifter#liftLanguageId} extracts the
 * {@code language_id} key for each parent row, and the resolver derives the JOIN target as
 * the leaf table {@code language}'s primary key (no {@code @reference} needed).
 */
public record CreateFilmPayload(Integer languageId) {}
