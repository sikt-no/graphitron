package no.sikt.graphitron.model.boot;

/**
 * What a caller will do with the store it opens, which is what decides how many prepared
 * statements each of the store's sessions keeps.
 *
 * <p>A capture runs roughly six hundred distinct statements, a set the code fixes rather than the
 * schema captured, in the same order every round. A statement cache holds a statement only while
 * it is among the last few used, so a cache smaller than a set that repeats evicts each statement
 * just before it comes round again and saves nothing.
 *
 * <p>Two sets repeat. Within one capture, {@link no.sikt.graphitron.model.derive.OccurrenceDepths}
 * runs the same few dozen statements once per depth of the occurrence tree, the depth a bound
 * parameter, so from the second depth on each statement's text is one the capture has already
 * prepared, and a cache with room for one depth's statements plans each of them once rather than
 * once a depth. Across captures on one connection the whole set repeats, and a cache with room for
 * all of it saves about 30% of every capture after the first.
 *
 * <p>The cache costs memory, about 50 KB a statement, paid by every session that fills it, and a
 * build running modules in parallel holds one session per module. Room for one depth is about
 * 3 MB a session and room for the whole set roughly 30 MB, so a caller that captures once keeps the
 * first and a caller that captures repeatedly on one connection the second, and which it is is the
 * caller's to say.
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
     * One capture per connection, then close: a build's module, a one-shot run. Room for the
     * statements one depth of {@link no.sikt.graphitron.model.derive.OccurrenceDepths} runs, so
     * each depth after the first reuses what the depth before it prepared, and no more: a capture's
     * other statements run once each, and keeping them would only cost memory.
     */
    ONE_CAPTURE(64),

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
