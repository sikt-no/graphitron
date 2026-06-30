package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Instance-method service stub whose only public constructor takes a parameter that is neither a
 * {@code DSLContext} nor a declared context key. Used to pin the rejection path of
 * {@link ServiceCatalog#reflectServiceMethod}'s relaxed holder resolution (R256): a constructor
 * whose parameters are not all bindable cannot be used as a holder.
 */
public class TestInstanceServiceStubUnbindableCtor {

    @SuppressWarnings("unused")
    private final String name;

    /** Constructor with an unbindable parameter (not a DSLContext, not a context key). */
    public TestInstanceServiceStubUnbindableCtor(String name) {
        this.name = name;
    }

    public FilmRecord getFilm() { throw new UnsupportedOperationException(); }
}
