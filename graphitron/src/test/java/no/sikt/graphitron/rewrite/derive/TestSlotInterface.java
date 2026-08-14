package no.sikt.graphitron.rewrite.derive;

/**
 * An interface, to pin that the bean arm is chosen by "not a record" rather than by "a class": a
 * type backed by an interface offers the same accessors, and the census records its declared form
 * as INTERFACE.
 */
public interface TestSlotInterface {

    /** Slot {@code name}. */
    String getName();
}
