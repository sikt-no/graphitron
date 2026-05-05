package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;

/**
 * Instance-method service stub: mirrors the legacy generator's per-call
 * {@code new ServiceName(ctx)} pattern. Used to exercise
 * {@link ServiceCatalog#reflectServiceMethod}'s instance-method classification path
 * (the holder must expose a public {@code (DSLContext)} constructor) and the emitter's
 * {@code new ServiceClass(dsl).method(...)} call shape.
 *
 * <p>Mirror of {@link TestServiceStub} but with a non-static method; kept narrow to the cases
 * the instance-services bug fix needs to pin.
 */
public class TestInstanceServiceStub {

    @SuppressWarnings("unused")
    private final DSLContext ctx;

    public TestInstanceServiceStub(DSLContext ctx) {
        this.ctx = ctx;
    }

    /** Instance method returning {@link FilmRecord} — exercises root @service on a Film return. */
    public FilmRecord getFilm() { throw new UnsupportedOperationException(); }

    /** Instance method with a GraphQL arg parameter — exercises the call-arg threading path. */
    public FilmRecord getFilmById(String id) { throw new UnsupportedOperationException(); }
}
