package no.sikt.graphql;

import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;

public interface MultitenantGraphitronContext {
    DSLContext getDslContext(@Nullable Object localContext);
    String getTenantId(@Nullable Object localContext);
}
