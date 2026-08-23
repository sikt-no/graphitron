package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.CodeBlock;

/**
 * One emission host's {@code graphitronContext(env)} seam, handed to a renderer as a value so
 * the renderer emits the call without holding the host. Calling it yields the call expression
 * <em>and</em> records that the host class needs the
 * {@code private static GraphitronContext graphitronContext(DataFetchingEnvironment env)} helper,
 * which is the whole point of the seam: the emitted call and the helper it names are decided
 * together, so neither can exist without the other.
 *
 * <p>The bug class this closes has shipped twice, as {@link RequestContextHelper} records: a call
 * emitted onto a class that never carried the helper, surfacing as the consumer's javac failure.
 * Two hosts satisfy the seam today, {@link RequestContextHelper#call()} and the fetcher
 * generator's own per-class helper set; a renderer that takes one of these works under either.
 */
@FunctionalInterface
public interface RequestContextRead {

    /** The {@code graphitronContext(env)} call expression, recording the host's need for it. */
    CodeBlock call();
}
