package no.sikt.graphitron.roadmap;

/**
 * Tripwire failure raised by the verify-mode entry points (README drift, front-matter
 * validation, leaf-coverage drift, markdown-table findings). The diagnostic is already
 * printed to stderr by the caller; this exception only needs to abort the run so
 * exec-maven-plugin can wrap it as a clean MojoExecutionException and produce the normal
 * {@code BUILD FAILURE} summary. Without it, a {@code System.exit} from one of these checks
 * terminates the Maven JVM directly, leaving the contributor at a shell prompt with
 * {@code $? = 1} and no {@code BUILD FAILURE} banner, as if Maven had crashed.
 *
 * <p>The stack trace is suppressed because the failure surface is the printed diagnostic and
 * the throw site, not the Java call chain.
 */
final class BuildFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BuildFailure(String message) {
        super(message);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
