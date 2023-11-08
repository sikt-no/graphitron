package no.fellesstudentsystem.graphitron.definitions.mapping;

/**
 * An element that can be used as a part of a join sequence.
 */
public interface JoinElement {
    /**
     * @return The exact name of the data that this object corresponds to.
     */
    String getMappingName();

    /**
     * @return The method call version of the name.
     */
    String getCodeName();

    /**
     * @return Should this element reset the sequence?
     */
    boolean clearsPreviousSequence();

    /**
     * @return The table that this join element is built of.
     */
    JOOQMapping getTable();
}
