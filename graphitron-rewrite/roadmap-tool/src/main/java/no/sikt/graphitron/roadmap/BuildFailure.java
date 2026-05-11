package no.sikt.graphitron.roadmap;

/**
 * Tripwire failure raised by the verify-mode entry points (README drift, leaf-coverage drift,
 * front-matter validation). The diagnostic is already printed to stderr by the caller; this
 * exception only needs to abort the run so exec-maven-plugin can wrap it as a clean
 * MojoExecutionException and produce the normal {@code BUILD FAILURE} summary. The stack
 * trace is suppressed because the failure surface is the printed diagnostic and the throw
 * site, not the Java call chain.
 */
final class BuildFailure extends RuntimeException {
    BuildFailure(String message) {
        super(message);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
