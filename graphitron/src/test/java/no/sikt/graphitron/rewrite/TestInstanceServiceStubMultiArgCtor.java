package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.DSLContext;

/**
 * Instance-method service stub whose only constructor takes a {@code DSLContext} plus a value a
 * context argument ({@code tenantId}) could supply. Pins that {@link ServiceCatalog#decodeServiceMethod}
 * finds no holder in it: a context value fills a method parameter, never a constructor's.
 */
public class TestInstanceServiceStubMultiArgCtor {

    @SuppressWarnings("unused")
    private final DSLContext ctx;
    @SuppressWarnings("unused")
    private final String tenantId;

    public TestInstanceServiceStubMultiArgCtor(DSLContext ctx, String tenantId) {
        this.ctx = ctx;
        this.tenantId = tenantId;
    }

    public FilmRecord getFilm() { throw new UnsupportedOperationException(); }
}
