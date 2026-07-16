package no.sikt.graphitron.rewrite.methodgraph;

import java.util.Objects;

/**
 * One committed emitted-method command: the record that a schema coordinate's emit owns exactly
 * one named method in one generated unit. The command is
 * the name authority for that method — the emitter obtains the declaration name from the commit
 * (see {@link MethodCommandRegistry#declareReentryRowsMethod}), it never registers a description
 * beside a name it derived independently, so a committed command with no emitted method behind it
 * (or an emitted family method with no command) is a real defect the bidirectional closure oracle
 * can catch, not census drift.
 *
 * @param coordinate the owning schema coordinate in {@code ParentType.fieldName} form
 *                   ({@code GraphitronField.qualifiedName()})
 * @param unitFqcn   fully-qualified name of the generated compilation unit declaring the method
 *                   (e.g. {@code com.example.fetchers.FilmFetchers})
 * @param typePath   nested-type path inside the unit, {@code ""} for a top-level declaration —
 *                   the same key shape the closure walk's node relation uses
 * @param methodName the committed method name, read off the model's regime-1 naming fact at
 *                   commit time
 */
public record MethodCommand(String coordinate, String unitFqcn, String typePath, String methodName) {

    public MethodCommand {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(unitFqcn, "unitFqcn");
        Objects.requireNonNull(typePath, "typePath");
        Objects.requireNonNull(methodName, "methodName");
    }

    /** The {@code (unit, typePath, method)} identity a command claims — one command per key. */
    public String methodKey() {
        return typePath.isEmpty()
            ? unitFqcn + "#" + methodName
            : unitFqcn + "." + typePath + "#" + methodName;
    }
}
