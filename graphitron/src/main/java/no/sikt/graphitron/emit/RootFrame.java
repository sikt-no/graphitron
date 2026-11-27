package no.sikt.graphitron.emit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A root read the frame runs: its frame and the connection the store states for it. Its fetcher is the
 * frame around its launcher.
 */
public record RootFrame(FrameQuery.Row frame, ConnectionQuery.Row connection) {
    public RootFrame {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(connection, "connection");
    }

    /**
     * The root reads of a run that run in the frame, by parent type and field, and the Java type the
     * graph's tenant columns agree on, which a routed key is built under.
     */
    public record Rows(Map<String, RootFrame> byField, Optional<String> tenantType) {
        public Rows {
            byField = Map.copyOf(byField);
            Objects.requireNonNull(tenantType, "tenantType");
        }

        /** No root read in the frame: a run with no store. */
        public static Rows empty() {
            return new Rows(Map.of(), Optional.empty());
        }

        /**
         * The frame of each root {@code roots} states whole that the store routes by the tenant its
         * arguments name, with the connection the store states for it. {@code roots} is the run's one
         * {@link RootQuery#read} answer, the one the launchers are planned from. A root of global
         * tables, or of a graph with no tenant column, keeps the fetcher it has.
         */
        public static Rows framed(Map<String, RootQuery.Result> roots, List<FrameQuery.Row> frames,
                                  Map<String, ConnectionQuery.Row> connections, Optional<String> tenantType) {
            var byCoordinate = new LinkedHashMap<String, FrameQuery.Row>();
            frames.forEach(f -> byCoordinate.put(f.coordinate(), f));
            var out = new LinkedHashMap<String, RootFrame>();
            for (var root : roots.values()) {
                if (!(root instanceof RootQuery.Emitted emitted)) {
                    continue;
                }
                var connection = connections.get(emitted.coordinate());
                if (connection == null || connection.outcome() != ConnectionQuery.Outcome.ROUTED) {
                    continue;
                }
                var frame = byCoordinate.get(emitted.coordinate());
                if (frame == null) {
                    throw new IllegalStateException("Graphitron generator bug (root frame): the store routes the root "
                        + emitted.coordinate() + " and states no frame for it; the queries have drifted");
                }
                out.put(emitted.parentType() + "." + emitted.fieldName(), new RootFrame(frame, connection));
            }
            return new Rows(out, tenantType);
        }

        /** The frame the root read at this field runs in, where it runs in one. */
        public Optional<RootFrame> frameFor(String parentType, String fieldName) {
            return Optional.ofNullable(byField.get(parentType + "." + fieldName));
        }
    }
}
