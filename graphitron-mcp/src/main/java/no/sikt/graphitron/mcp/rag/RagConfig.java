package no.sikt.graphitron.mcp.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The dev-environment glue the RAG indices need but the {@code Workspace} does not carry:
 * where to persist the content-hash-keyed Lucene index between {@code dev} restarts. The same kind
 * of thing as the loopback bind address, not generator model state, so it enters the server through
 * a small record rather than riding {@code Workspace}.
 *
 * <p>A record (rather than a third positional {@code Path}) so future knobs can be added without
 * regrowing the {@code GraphitronMcpServer} constructor signature.
 *
 * @param cacheDir the root under which each corpus's {@code <corpusHash>/} index directory lives;
 *                 {@code DevMojo} supplies {@code ${project.build.directory}/graphitron-mcp-rag}, so
 *                 the index survives {@code dev} restarts and dies on {@code mvn clean}
 */
public record RagConfig(Path cacheDir) {

    /**
     * A throwaway cache directory under the system temp root: the back-compat default for callers
     * (and tests) that construct the server without a persistent location. Nothing is promised to
     * survive across runs.
     */
    public static RagConfig temporary() {
        try {
            return new RagConfig(Files.createTempDirectory("graphitron-mcp-rag"));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create a temporary RAG cache directory", e);
        }
    }
}
