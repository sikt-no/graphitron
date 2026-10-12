package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.CodeBlock;

/**
 * The routed-tenant rider a fetcher's success envelope carries: {@code .localContext(<key>)} when
 * the fetcher divined a tenant key its subtree's inherited acquisitions read back, nothing
 * otherwise.
 *
 * <p>graphql-java gives each field one per-subtree value slot, {@code localContext}, and in
 * generated code that slot means the tenant and nothing else; errors travel on the payload's
 * {@code Outcome}. This type is how the invariant reaches the emitters: the success envelope
 * ({@link FetcherResult#success}) and every helper threading toward it take a hand-down rather
 * than a free builder tail, so a non-tenant writer has nothing to pass that would put a value in
 * the slot. The factories are public because the tenant resolvers that mint a keyed hand-down live
 * on both sides of the render boundary; that only tenant code calls {@link #divined} is held by
 * the generator test tier's {@code localContext} ownership guard, which also keeps the slot's
 * literal out of every other generator source.
 */
public final class TenantHandDown {

    private static final TenantHandDown NONE = new TenantHandDown(CodeBlock.of(""));

    private final CodeBlock builderTail;

    private TenantHandDown(CodeBlock builderTail) {
        this.builderTail = builderTail;
    }

    /** No tenant rides down: the single-tenant form, and every routed site that divined no key. */
    public static TenantHandDown none() {
        return NONE;
    }

    /** Hands the divined key held in {@code tenantKeyLocal} down the subtree. */
    public static TenantHandDown divined(String tenantKeyLocal) {
        return new TenantHandDown(CodeBlock.of(".localContext($L)", tenantKeyLocal));
    }

    /** The {@code DataFetcherResult} builder call this hand-down appends; empty for {@link #none()}. */
    public CodeBlock builderTail() {
        return builderTail;
    }
}
