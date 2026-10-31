package no.sikt.graphitron.model.boot;

/**
 * What a caller will do with the store it opens, which is what decides how many prepared
 * statements each of the store's sessions keeps.
 *
 * <p>A capture runs roughly six hundred distinct statements, a set the code fixes rather than the
 * schema captured, in the same order every round. A statement cache holds a statement only while
 * it is among the last few used, so a cache smaller than that set evicts each statement just before
 * it comes round again and saves nothing, and one big enough saves about 30% of every capture after
 * the first. The first capture saves nothing at all: every statement in it is new. The cache costs
 * memory, about 50 KB a statement and so roughly 30 MB per session that fills it, paid by every
 * session whether or not anything is ever captured on it twice. A caller that captures once should
 * not pay it, a build running modules in parallel holds one session per module, and a caller that
 * captures repeatedly on one connection should, so which it is is the caller's to say.
 *
 * <p>The size is a database-level setting that H2 reads when the first connection opens the
 * database and never again, so it belongs to whoever opens a database first: a store reopened by
 * another handle in the same JVM keeps the size its first opener gave it.
 *
 * <p>The system property {@value #CACHE_SIZE_PROPERTY} replaces the number for either use in one
 * JVM, for an operator or a measurement; callers still state the use.
 */
public enum StoreUse {

    /**
     * One capture per connection, then close: a build's module, a one-shot run. H2's own default,
     * which keeps almost nothing and costs almost nothing.
     */
    ONE_CAPTURE(8),

    /**
     * A connection that captures again and again, or reads again and again, and lives long enough
     * for the second round to matter: the dev loop, a language server or tool server, a test
     * harness capturing many graphs into one store. Room for a capture's statements with headroom.
     */
    LONG_LIVED(1024);

    /** The system property that replaces the cache size of either use for one JVM. */
    public static final String CACHE_SIZE_PROPERTY = "graphitron.queryCacheSize";

    private final int cacheSize;

    StoreUse(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    /** Prepared statements each session of a store opened for this use keeps. */
    int cacheSize() {
        return Integer.getInteger(CACHE_SIZE_PROPERTY, cacheSize);
    }
}
