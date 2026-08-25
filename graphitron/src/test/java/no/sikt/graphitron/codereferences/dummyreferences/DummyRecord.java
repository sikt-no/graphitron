package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * A minimal consumer-authored POJO backing class, used two ways.
 *
 * <p>Most call sites only need the class to <em>exist</em>: a {@code @service} producer returns it
 * so an SDL type binds to it by reflection, and the test asserts the binding rather than reading
 * anything off it. Those sites never touch the property below.
 *
 * <p>The one property exists so the class is also reachable end to end. Result-side field
 * classification resolves an SDL field against a readable accessor, and input-side bean population
 * resolves it against a JavaBean setter; a class with neither is a backing class that classifies
 * and can never generate. {@code id} gives both sides exactly one field to bind, which is what a
 * worked example of POJO backing needs and no more. Adding a second property would widen what
 * every SDL type bound to this class can resolve, so keep it at one.
 */
public class DummyRecord {

    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
