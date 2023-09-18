package no.fellesstudentsystem.graphitron.generators.context;

public class Recursion {
    /**
     * Helper method for enforcing a recursion limit.
     */
    public static void recursionCheck(int recursion) {
        if (recursion == Integer.MAX_VALUE - 1) {
            throw new RuntimeException("Recursion depth has reached the integer max value.");
        }
    }
}
