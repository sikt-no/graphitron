package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Instance-method service stub whose enclosing class has <em>no</em> public
 * {@code (DSLContext)} constructor. Used to pin the rejection path of
 * {@link ServiceCatalog#reflectServiceMethod} when an instance method's holder cannot be
 * constructed by the emitter.
 */
public class TestInstanceServiceStubNoCtor {

    /** No-arg constructor, no DSLContext. */
    public TestInstanceServiceStubNoCtor() {}

    public FilmRecord getFilm() { throw new UnsupportedOperationException(); }
}
