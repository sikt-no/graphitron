package no.sikt.graphitron.rewrite.derive;

import java.util.List;

/**
 * A public producer for the backing shadow: the differential compares the reflective walk against
 * the store's derivation over the same classes, so both sides have to be able to see them. The
 * classpath census keeps public top-level classes only, which is why this fixture is public where
 * the older service stubs are package-private, and why they cannot stand in here.
 */
public final class TestBackingService {

    private TestBackingService() {}

    /** A collection producer, so the seed exercises the peel rather than a bare root position. */
    public static List<TestBackingFilm> films() {
        throw new UnsupportedOperationException();
    }

    /** A second producer naming a different class for the same SDL type, for the disagreement. */
    public static TestBackingOther other() {
        throw new UnsupportedOperationException();
    }

    /** A producer taking an input, so the differential covers the input axis and not only returns. */
    public static List<TestBackingFilm> byFilter(TestBackingFilter filter) {
        throw new UnsupportedOperationException();
    }
}
