package no.sikt.graphitron.rewrite;

/**
 * Fixture: JavaBean-shaped consumer bean (public no-arg constructor, JavaBean-style setters)
 * with a Java-primitive setter parameter ({@code void setActive(boolean)}). The matching SDL input
 * type is {@code input TestInputJavaBeanWithBoolean { active: Boolean! }}; the {@code FieldBinding}
 * for {@code active} must carry {@code javaElementTypeName == "java.lang.Boolean"}, not
 * {@code "boolean"}.
 */
public class TestInputJavaBeanWithBoolean {
    private boolean active;

    public TestInputJavaBeanWithBoolean() {
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
