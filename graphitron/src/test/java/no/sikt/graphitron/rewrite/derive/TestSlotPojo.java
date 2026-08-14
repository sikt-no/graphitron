package no.sikt.graphitron.rewrite.derive;

/**
 * A class whose public methods span the bean rule's accept and reject sides: the two prefixes,
 * the first-letter lowering, and the four near-misses (no prefix, prefix alone, a lower-case
 * letter after the prefix, an accessor that takes an argument).
 *
 * <p>Bodies are stubs; the census reads declarations, never bytecode behaviour.
 */
public class TestSlotPojo {

    /** The canonical accessor: slot {@code title}. */
    public String getTitle() {
        return null;
    }

    /** The second prefix: slot {@code restricted}, of the type the census renders for a boolean. */
    public boolean isRestricted() {
        return false;
    }

    /**
     * Only the first letter is lowered, so this is slot {@code uRL} rather than {@code url}. The
     * projection's rule, transcribed rather than improved: an author writing against this class
     * has been writing the same name.
     */
    public String getURL() {
        return null;
    }

    /**
     * The same property named without a prefix. A slot named {@code title} beside
     * {@link #getTitle()} would mean the rule read a property rather than an accessor.
     */
    public String title() {
        return null;
    }

    /** The prefix with nothing after it: no slot, and no attempt to lower an absent letter. */
    public String get() {
        return null;
    }

    /** A lower-case letter after the prefix, so the name is not bean-shaped at all. */
    public String getlower() {
        return null;
    }

    /** Bean-shaped but parameterised, which no member name can resolve to. */
    public String getRated(int scale) {
        return null;
    }

    /**
     * A second spelling of {@code title}, on a prefix the rule accepts without reading the return
     * type. Two spellings are two slots of one name, which is what the projection's list held.
     */
    public String isTitle() {
        return null;
    }

    /**
     * A generic accessor: its descriptor erases to {@code List} and only the method's
     * {@code Signature} attribute names the element type, so the slot's rendered type says which of
     * the two the census reads.
     */
    public java.util.List<String> getTags() {
        return null;
    }
}
