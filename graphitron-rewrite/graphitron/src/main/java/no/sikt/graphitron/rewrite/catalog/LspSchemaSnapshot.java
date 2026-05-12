package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Projection of the parsed user schema, shipped through the dev pipeline to
 * the LSP as a side-channel alongside {@link CompletionData}. The LSP uses
 * this to resolve directive references that {@code LspVocabulary} (the
 * bundled-SDL view) does not declare: user-authored federation directives,
 * {@code @auth}-style guards, anything else the consumer's schema brings
 * along.
 *
 * <p>Sealed over two orthogonal axes. The first ({@link Unavailable} vs.
 * {@link Built}) carries <em>availability</em>: whether the build pipeline
 * has produced a snapshot yet. The second ({@link Built.Current} vs.
 * {@link Built.Previous}) carries <em>freshness</em>: whether the snapshot
 * reflects the user's latest edit or the last successful parse before a
 * regression. Consumers that don't care about freshness switch on the
 * {@link Built} super-permit and read {@link Built#directives()} uniformly;
 * consumers that care (today: the unknown-directive validator) switch
 * through to the leaf permits.
 *
 * <p>{@code Workspace} owns the lifecycle and the volatile reference; the
 * producer ({@code CatalogBuilder.buildSnapshot}) only ever returns
 * {@link Built.Current}.
 */
public sealed interface LspSchemaSnapshot permits LspSchemaSnapshot.Unavailable, LspSchemaSnapshot.Built {

    /**
     * Pre-build state: the dev pipeline has not produced a successful
     * snapshot yet. Consumers treat this as "no info to act on" and avoid
     * punishing the user for what cannot reliably be seen.
     */
    record Unavailable() implements LspSchemaSnapshot {}

    /**
     * Snapshot produced from a successful parse. The {@link Current} permit
     * is the freshest known projection; {@link Previous} is the most recent
     * successful one, retained when a later parse fails so consumers don't
     * lose the last good directive surface.
     */
    sealed interface Built extends LspSchemaSnapshot permits Built.Current, Built.Previous {
        List<DirectiveShape> directives();

        default Optional<DirectiveShape> directive(String name) {
            return directives().stream().filter(d -> d.name().equals(name)).findFirst();
        }

        record Current(List<DirectiveShape> directives) implements Built {
            public Current {
                directives = List.copyOf(directives);
            }
        }

        record Previous(List<DirectiveShape> directives) implements Built {
            public Previous {
                directives = List.copyOf(directives);
            }
        }
    }

    static LspSchemaSnapshot unavailable() {
        return new Unavailable();
    }
}
