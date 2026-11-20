package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.emit.FillQuery;
import no.sikt.graphitron.rewrite.model.ResolvedContextArg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The request-context keys the graph's calls read, folded to one {@link ResolvedContextArg} per
 * key.
 *
 * <p>The store states every key a call reads, a {@code @service}'s and a {@code @condition}'s
 * alike, each with the type of the parameter it fills ({@link FillQuery#contextKeys}). The factory
 * emitter ({@code Graphitron.newExecutionInput(...)}) reads {@link ResolvedContextArg#javaType},
 * the type the calls cast the context's value to. A key read as two types is the store's
 * {@code CONTEXT_KEY_TYPE_DISAGREES}, which refuses the build, so a key that reaches this fold has
 * one type.
 *
 * <p>Invoked once by {@link GraphitronSchema}'s constructor; the cached {@link Classification}
 * hangs off {@link GraphitronSchema#contextArguments()}.
 */
public final class ContextArgumentClassifier {

    private ContextArgumentClassifier() {}

    public record Classification(Map<String, ResolvedContextArg> resolved) {
        public Classification {
            // LinkedHashMap (not Map.copyOf) so the alphabetical iteration order survives the
            // defensive copy; Map.copyOf's iteration order is unspecified.
            resolved = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
        }
    }

    /**
     * One {@link ResolvedContextArg} per key in {@code keys}, alphabetical by key for a
     * deterministic factory parameter order, typed by the key's first row in {@code keys}' order
     * and carrying every call reading it as that type.
     */
    public static Classification classify(List<FillQuery.ContextKey> keys) {
        var byKey = new LinkedHashMap<String, List<FillQuery.ContextKey>>();
        for (var key : keys) {
            byKey.computeIfAbsent(key.key(), k -> new ArrayList<>()).add(key);
        }
        var resolved = new TreeMap<String, ResolvedContextArg>();
        for (var entry : byKey.entrySet()) {
            // The store refuses one key read as disagreeing types, so this pick only keeps the fold total.
            TypeName javaType = entry.getValue().get(0).type();
            var sites = entry.getValue().stream()
                .filter(key -> key.type().equals(javaType))
                .map(key -> new ResolvedContextArg.Site(key.className(), key.methodName()))
                .toList();
            resolved.put(entry.getKey(), new ResolvedContextArg(entry.getKey(), javaType, sites));
        }
        return new Classification(resolved);
    }
}
