package no.sikt.graphitron.model.capture.code.fixtures;

/**
 * A superclass declaring a setter its subclass inherits. The emitter fills a bean through
 * {@code Class.getMethods()}, which is every public method including the inherited ones, so a
 * member declared here is one an author can name on the subclass.
 */
public class BeanBase {

    private String title;

    /** Declared here, inherited by {@link BeanChild}, and fillable on either. */
    public void setTitle(String title) {
        this.title = title;
    }

    /** Readable on either, for the same reason. */
    public String getTitle() {
        return title;
    }
}
