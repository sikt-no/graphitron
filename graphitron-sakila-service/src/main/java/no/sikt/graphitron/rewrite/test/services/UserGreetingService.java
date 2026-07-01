package no.sikt.graphitron.rewrite.test.services;

/**
 * R190 fixture: minimal {@code @service} class whose {@link #greet} method takes a
 * {@code userId} parameter classified as {@code ParamSource.Context}. Drives the generated
 * {@code Graphitron.newExecutionInput(DSLContext, String userId)} factory signature in the
 * sakila-example compile and execution fixtures (the schema declares zero other
 * contextArguments today, so {@code userId} is the only typed slot after {@code defaultDsl}).
 *
 * <p>The execution-tier test in {@code FilmContextArgumentRoundTripTest} drives a query against
 * {@code Film.greetingByUser} and asserts that the {@code userId} value threaded through the
 * factory round-trips into the rendered greeting.
 */
public final class UserGreetingService {

    private UserGreetingService() {}

    /**
     * Returns a deterministic greeting incorporating the userId so the round-trip test can
     * assert end-to-end thread-through.
     */
    public static String greet(String userId) {
        return "hello " + userId;
    }
}
