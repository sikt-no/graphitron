package no.sikt.graphitron.mcp.fixtures;

/**
 * The second of the two classes the paging pin's {@code code} census is counted over.
 *
 * <p>It exists because the census population the {@code code} tool answers for is classes declaring
 * at least one public method, and nothing else this module compiles for its tests declares one: a
 * test class and its cases are package-private by convention here, so a scan over the module's own
 * compiled tests finds no entries at all. A pin that needs more than one entry to tell an unpaged
 * total from a page size therefore has to own the entries, and two is the smallest number that
 * distinguishes them.
 */
public final class PagedCensusSecond {

    /** Never called; the census reads the declaration, not the behaviour. */
    public String name() {
        return "second";
    }
}
