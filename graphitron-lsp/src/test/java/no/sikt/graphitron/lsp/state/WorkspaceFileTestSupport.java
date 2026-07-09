package no.sikt.graphitron.lsp.state;

/**
 * Test-only bridge to the package-private {@link WorkspaceFile#snapshot()}.
 *
 * <p>Production code takes {@link FileSnapshot}s exclusively through
 * {@link Workspace}'s lambda-scoped view accessors ({@code withView} /
 * {@code withAllViews}), which own the clone's native lifetime. Single-threaded
 * tests that construct a {@link WorkspaceFile} directly and feed a migrated
 * {@code compute()} mint their snapshot here instead. Serialisation is not a
 * concern in a single-threaded test, so the missing under-lock happens-before
 * edge does not matter; the returned clone leaks native memory for the lifetime
 * of the (short-lived) test JVM unless {@link FileSnapshot#close() closed}.
 */
public final class WorkspaceFileTestSupport {

    private WorkspaceFileTestSupport() {}

    /** Snapshot a fresh single-file workspace parsed from {@code source} at version 1. */
    public static FileSnapshot snapshot(String source) {
        return new WorkspaceFile(1, source).snapshot();
    }

    /** Snapshot an already-constructed {@link WorkspaceFile}. */
    public static FileSnapshot snapshot(WorkspaceFile file) {
        return file.snapshot();
    }
}
