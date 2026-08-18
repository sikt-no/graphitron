package no.sikt.graphitron.rewrite;

/**
 * Fixture: JavaBean-shaped consumer bean (public no-arg constructor, JavaBean-style setters) that is
 * flat where its SDL input clusters fields under a nested input object. The JavaBean twin of
 * {@link TestInputBeanGrouped}: before grouping inputs flattened, an unmatched nested SDL field was
 * skipped here in silence, so {@code length} stayed null and nothing in the build said so.
 */
public class TestInputJavaBeanGrouped {
    private String title;
    private Integer length;

    public TestInputJavaBeanGrouped() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getLength() {
        return length;
    }

    public void setLength(Integer length) {
        this.length = length;
    }
}
