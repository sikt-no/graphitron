package no.sikt.graphitron.model.capture.code.fixtures;

/**
 * An abstract class with a public no-argument constructor and a setter, which is the shape a rule
 * reading constructors alone would call constructible. Nothing can make one, so nothing should.
 */
public abstract class AbstractHolder {

    private String title;

    /** Public and takes nothing, and still cannot be called to make one of these. */
    public AbstractHolder() {}

    public void setTitle(String title) {
        this.title = title;
    }
}
