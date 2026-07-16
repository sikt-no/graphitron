package no.sikt.graphitron.rewrite.compile;

import java.util.Set;

/**
 * The result of driving the {@link IncrementalCompiler} for one dev-loop event: the
 * javac {@link CompileRound} (verdict + diagnostics) paired with the set of generated units the round
 * actually recompiled. The dev loop logs the unit count and hands {@code round.diagnostics()} to the
 * console renderer and the MCP {@code diagnostics} tool; tests assert on {@code compiledUnits} to pin
 * pruning (a body-only edit recompiles only the delta; an ABI edit pulls in its dependents).
 */
public record CompileOutcome(CompileRound round, Set<String> compiledUnits) {

    public CompileOutcome {
        compiledUnits = Set.copyOf(compiledUnits);
    }
}
