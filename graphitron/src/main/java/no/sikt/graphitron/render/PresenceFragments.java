package no.sikt.graphitron.render;

import no.sikt.graphitron.command.NullCheck;
import no.sikt.graphitron.command.PresenceProbe;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;
import java.util.Optional;

/**
 * Renders the presence check a nullable single object is decided by, over a {@code row} local
 * holding the object's record: each column leaf is present when all its columns are non-null, the
 * probe when its column is non-null, and the object when any of them is, in which case the check
 * returns {@code present}; otherwise it returns {@code null}. Plain {@code if} statements:
 * generated code targets Java 17. Shared by a child's fetcher and the root table read's launcher,
 * so the two decide presence with one emitter.
 */
public final class PresenceFragments {

    private PresenceFragments() {}

    /**
     * The check of a root single read's {@link NullCheck.Collapses}, its columns read off
     * {@code table}, the expression the launcher projected them from.
     */
    public static CodeBlock check(CodeBlock table, NullCheck.Collapses check, String present) {
        return check(table, check.leaves(), check.probe().map(PresenceProbe::alias), present);
    }

    /**
     * The check over {@code columnLeaves}, read off {@code table}, and the probe column aliased
     * {@code probeAlias} when the object holds references or lists.
     */
    public static CodeBlock check(CodeBlock table, List<List<ColumnRef>> columnLeaves,
            Optional<String> probeAlias, String present) {
        var body = CodeBlock.builder();
        for (var leaf : columnLeaves) {
            body.add("if (");
            for (int i = 0; i < leaf.size(); i++) {
                if (i > 0) body.add(" && ");
                body.add("row.get($L.$L) != null", table, leaf.get(i).javaName());
            }
            body.add(") return $L;\n", present);
        }
        probeAlias.ifPresent(alias -> body.add("if (row.get($S) != null) return $L;\n", alias, present));
        return body.add("return null;\n").build();
    }
}
