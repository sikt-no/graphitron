package no.sikt.graphitron.rewrite;

import org.jooq.DSLContext;
import org.jooq.Row1;

import java.util.Map;
import java.util.Set;

/**
 * A child-service fixture for {@link TenantBindingClassificationTest}: an instance method on a
 * holder whose constructor takes the {@code DSLContext}, so the call reaches its connection
 * through the constructor rather than a method parameter.
 */
public class TenantHolderServiceStub {
    @SuppressWarnings("unused")
    private final DSLContext dsl;

    public TenantHolderServiceStub(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Map<Row1<Integer>, String> rating(Set<Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }
}
