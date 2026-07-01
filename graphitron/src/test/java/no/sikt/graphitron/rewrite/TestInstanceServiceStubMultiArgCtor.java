package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.DSLContext;

/**
 * Instance-method service stub whose holder constructor takes a {@code DSLContext} plus a context
 * argument ({@code tenantId}). Used to pin {@link ServiceCatalog#reflectServiceMethod}'s relaxed
 * holder resolution (R256): a {@code (DSLContext, ctxArg)} constructor resolves, with each ctor
 * parameter bound to a {@code DSLContext} slot or a declared context key.
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
