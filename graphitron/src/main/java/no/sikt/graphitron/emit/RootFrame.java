package no.sikt.graphitron.emit;

import java.util.Objects;
import java.util.Optional;

/**
 * A root read the frame runs: its frame, the connection the store states for it, and the Java type the
 * graph's tenant columns agree on, which a routed key is built under. Read off the store with the root
 * the store states whole, so its fetcher is the frame around its launcher.
 */
public record RootFrame(FrameQuery.Row frame, ConnectionQuery.Row connection, Optional<String> tenantType) {
    public RootFrame {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tenantType, "tenantType");
    }
}
