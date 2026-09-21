package no.sikt.graphitron.model.capture.code.fixtures;

import java.util.List;

/**
 * A record, which offers its components and nothing else.
 *
 * <p>The methods a record generates beside its accessors are the point of the fixture: toString,
 * hashCode and equals are public, non-synthetic and take no argument, so a rule that admitted every
 * no-argument public method would offer an author toString as a member.
 */
public record SlotRecord(String title, int year, List<String> tags) {

    /** A method of the author's own, which takes an argument and is therefore no slot. */
    public String titled(String suffix) {
        return title + suffix;
    }
}
