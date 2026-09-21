package no.sikt.graphitron.model.capture.code.fixtures;

/**
 * An interface, which pins that the bean arm is chosen by "not a record" rather than by "a class".
 * A type backed by an interface offers the same accessors, and the reading records its declared
 * form as INTERFACE.
 */
public interface SlotInterface {

    /** Slot {@code name}. */
    String getName();
}
