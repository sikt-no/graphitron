package no.sikt.graphitron.sakila.example.app;

import jakarta.enterprise.context.RequestScoped;

/**
 * Test-only stand-in for the per-request holder a real consumer populates before delegating: claims
 * read from an {@code Authorization} header, an environment bound structurally by the request path,
 * anything else resolved per request. Here it carries just the calling environment from
 * {@link PolicyMountedGraphqlResource}'s path parameter.
 *
 * <p>Its second field is what makes ordering observable. The mounted resource writes
 * {@link #requested(String)}; the module's test adapter calls {@link #recordSeamObservation()} from
 * {@code newExecutionInput()}; the resource reads {@link #observedBySeam()} after delegating and
 * echoes it back as a response header. A non-null echo means the seam ran after the resource had
 * populated the holder, which is the property a consumer's trust model rests on. A {@code null} echo
 * means the seam never ran at all, which is itself the assertion for a request the operation policy
 * refused before execution.
 */
@RequestScoped
public class CallingEnvironment {

    private String requested;
    private String observedBySeam;

    /** Called by the mounted resource, before it delegates. */
    public void requested(String environment) {
        this.requested = environment;
    }

    /** Called by the SPI adapter, from {@code newExecutionInput()}. */
    public void recordSeamObservation() {
        this.observedBySeam = requested;
    }

    /** What the seam saw, or {@code null} if the seam never ran on this request. */
    public String observedBySeam() {
        return observedBySeam;
    }
}
