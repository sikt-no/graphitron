package no.sikt.graphitron.mcp.fixtures.schema;

/**
 * The class a producer's return backs an SDL type with, shaped as a bean rather than a record so its
 * member slots come from {@code code_type_slot}'s bean-accessor arm.
 *
 * <p>The record arm is covered by the {@code code} tool's own fixtures; this is the arm where the slot
 * name is not the method name, which is the half of the bean rule a rendering can get wrong.
 */
public class FilmSummary {

    /** Offers the slot {@code title}. */
    public String getTitle() {
        return null;
    }

    /** Offers the slot {@code released}, whose accessor prefix is the other one the rule accepts. */
    public boolean isReleased() {
        return false;
    }
}
