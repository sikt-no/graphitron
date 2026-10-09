package no.sikt.graphitron.rewrite.test.services;

/**
 * One method with one named parameter, the shape an {@code argMapping} entry targets by name. Its
 * twin compiled without {@code -parameters} is
 * {@link no.sikt.graphitron.rewrite.test.nameless.NamelessService#compute}.
 */
public final class ComputingService {

    private ComputingService() {}

    public static Object compute(Object input) {
        return input;
    }
}
