package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

/**
 * The connection key a fetcher's success envelope hands down its subtree: {@code .localContext(<key>)}
 * when the fetcher read on a connection its subtree's inherited acquisitions must take again, nothing
 * otherwise.
 *
 * <p>graphql-java gives each field one per-subtree value slot, {@code localContext}, and in generated
 * code that slot carries the generated {@code ConnectionKey} and nothing else; errors travel on the
 * payload's {@code Outcome}. This type is how the invariant reaches the emitters: the success envelope
 * ({@link FetcherResult#success}) and every helper threading toward it take a hand-down rather than a
 * free builder tail, and the one keyed factory builds the {@code ConnectionKey} itself, so neither a
 * non-routing writer nor a routing one can put anything else in the slot. The factories are public
 * because the resolvers that mint a keyed hand-down live on both sides of the render boundary; that
 * only routing code calls {@link #divined} is held by the generator test tier's {@code localContext}
 * ownership guard, which also keeps the slot's literal out of every other generator source.
 */
public final class ConnectionKeyHandDown {

    private static final ConnectionKeyHandDown NONE = new ConnectionKeyHandDown(CodeBlock.of(""));

    private final CodeBlock builderTail;

    private ConnectionKeyHandDown(CodeBlock builderTail) {
        this.builderTail = builderTail;
    }

    /** Nothing rides down: the single-tenant form, and every routed site that divined no key. */
    public static ConnectionKeyHandDown none() {
        return NONE;
    }

    /**
     * Hands down the connection key of the tenant held in {@code tenantKeyLocal}.
     *
     * @param tenantKeyLocal the local holding the divined tenant
     * @param connectionKey the build's generated {@code ConnectionKey}
     */
    public static ConnectionKeyHandDown divined(String tenantKeyLocal, ClassName connectionKey) {
        return new ConnectionKeyHandDown(CodeBlock.of(".localContext($T.forTenant($L))", connectionKey, tenantKeyLocal));
    }

    /** The {@code DataFetcherResult} builder call this hand-down appends; empty for {@link #none()}. */
    public CodeBlock builderTail() {
        return builderTail;
    }
}
