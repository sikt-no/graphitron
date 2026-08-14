package no.sikt.graphitron.rewrite.derive;

/**
 * A record whose components are the member names an SDL author would write, plus one declared
 * bean accessor. The accessor is the point: a record's members are its components, so
 * {@code getTitle()} must contribute no second slot beside the component it shadows.
 *
 * <p>Public and top-level because the classpath census skips anything else, and the census is
 * what {@link ClassMemberSlotTest} reads.
 */
public record TestSlotRecord(Integer filmId, String title) {

    /** A bean-shaped accessor on a record; the record arm is what must win over it. */
    public String getTitle() {
        return title;
    }
}
