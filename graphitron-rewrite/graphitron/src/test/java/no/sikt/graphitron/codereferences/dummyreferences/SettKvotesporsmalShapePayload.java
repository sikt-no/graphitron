package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * R178 step 3 SettKvotesporsmal-shape regression fixture: a top-level record-backed payload
 * class with exactly one inner {@code @table}-typed accessor. Used by the regression pin that
 * verifies the same payload SDL classifies identically with and without an explicit
 * {@code @field(name:)} on the data field, and by the diagnostic-wording pin that verifies the
 * unified path cites the payload class (not the inner table's record class) when an
 * {@code @service} method's reflected return type doesn't match the payload.
 *
 * <p>Before R178 step 3, the carrier walk's forbidden-directives loop hard-rejected
 * {@code @field} on a carrier data field whose value is not the {@code $source} sigil,
 * routing the with-{@code @field} form through the standard record-backed parent path
 * (which works) and the without-{@code @field} form into the carrier walk (which demanded the
 * inner table's {@code FilmRecord} as the return type, producing a diagnostic citing the
 * inner table's record class instead of the payload class). Step 3 retires the carrier walk's
 * involvement from both forms; both classify identically through the unified path.
 */
public record SettKvotesporsmalShapePayload(FilmRecord film) {}
