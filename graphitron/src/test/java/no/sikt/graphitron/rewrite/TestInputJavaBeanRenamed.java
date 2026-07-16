package no.sikt.graphitron.rewrite;

/**
 * Fixture: JavaBean-shaped consumer bean (public no-arg constructor, JavaBean-style setters)
 * whose property names ({@code heading}, {@code score}) diverge from the SDL input field names they
 * bind to ({@code title}, {@code rating}). The matching SDL input type bridges the divergence with
 * {@code @field(name: "heading")} / {@code @field(name: "score")}; the binding key is the directive
 * value, which must resolve to the {@code setHeading} / {@code setScore} setters.
 *
 * <p>Sibling to {@link TestInputJavaBeanWithBoolean}, whose single property is an exact name mirror
 * of its SDL field; this is the first JavaBean fixture whose property names do not coincide with the
 * SDL field names.
 */
public class TestInputJavaBeanRenamed {
    private String heading;
    private Integer score;

    public TestInputJavaBeanRenamed() {
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }
}
