package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.List;

import static no.sikt.graphitron.model.Tables.LINT_FINDING;
import static no.sikt.graphitron.model.Tables.LINT_FINDING_FIX;
import static no.sikt.graphitron.model.Tables.LINT_FINDING_FIX_EDIT;

/**
 * The corrections the graph's linter computed for one document, as rows: each fix's label, the
 * finding's own line, and the ordered edits that apply it.
 *
 * <p>A caller hands over the editor's URI and nothing else. The protocol names a document by URI and
 * the store keys a source by the path capture read ({@code store_source}), which every diagnostics
 * family carries too, so the decode happens once here and both statements below compare the one path
 * it produced. A caller holding two spellings and picking one per query is how the two drift apart.
 *
 * <p><b>A fix is offered only for text it was computed against.</b> An edit names a span rather than
 * a declaration, so unlike a coordinate it cannot be re-anchored by resolving the declaration in the
 * live tree: applying a captured span to a buffer whose text has moved would corrupt the document
 * rather than merely miss. The source's recorded stamp is what settles it, compared against the
 * buffer in hand, so an unsaved buffer identical to the captured file is served and a buffer with one
 * character typed into it is not. The gate is per file, which is the grain that matters: an edit in
 * one schema file is addressed by that file's own text whatever the other open buffers say.
 */
public final class LintFixes {

    private LintFixes() {}

    /**
     * One stored fix: what an editor labels the action, where the finding sits, and the edits.
     *
     * @param description the fix's rendered title
     * @param findingLine the finding's own line, 1-based; what a surface intersects a request range
     *                    against, the fix being offered for the squiggle rather than for its edits
     * @param edits       the edits in the order the rule wrote them, which need not be source order
     */
    public record Fix(String description, int findingLine, List<Edit> edits) {
        public Fix {
            edits = List.copyOf(edits);
        }
    }

    /**
     * One edit: the half-open range {@code [start, end)} becomes {@code replacement}, positions
     * 1-based as the store holds them. An insertion is the zero-width case, a deletion the empty
     * replacement.
     */
    public record Edit(
        int startLine, int startColumn, int endLine, int endColumn, String replacement
    ) {}

    /**
     * The fixes offerable for the document at {@code uri} given the text in {@code buffer}, in
     * finding order. Empty when the graph has no fix-bearing finding in that file, and empty when the
     * buffer is not the text the graph captured, including when the source carries no stamp at all: a
     * source with nothing to compare against cannot be shown to still match.
     *
     * <p>A finding with no location is skipped rather than offered at the top of the file. Its fix,
     * if it had one, would carry positions no reader could place, and both shipped location-less
     * producers are whole-build advisories that suggest nothing. A fix with no edits is skipped by the
     * same join that collects them, there being nothing for an editor to apply.
     */
    public static List<Fix> forDocument(StoreHandle store, String uri, byte[] buffer) {
        String sourceName = StoreAccess.sourceNameOf(uri).orElse(null);
        if (sourceName == null || buffer == null) return List.of();
        if (!SourceStamp.recordedMatches(store.dsl(), sourceName, buffer)) return List.of();
        // One query across the three relations, ordered by the finding then by the edit's own
        // position, so the grouping below is a walk over a sorted result rather than a query per fix.
        var rows = store.dsl()
            .select(LINT_FINDING_FIX.FINDING_ORDINAL, LINT_FINDING_FIX.DESCRIPTION,
                LINT_FINDING.SOURCE_LINE,
                LINT_FINDING_FIX_EDIT.START_LINE, LINT_FINDING_FIX_EDIT.START_COLUMN,
                LINT_FINDING_FIX_EDIT.END_LINE, LINT_FINDING_FIX_EDIT.END_COLUMN,
                LINT_FINDING_FIX_EDIT.REPLACEMENT)
            .from(LINT_FINDING_FIX)
            .join(LINT_FINDING)
            .on(LINT_FINDING.GRAPH_NAME.eq(LINT_FINDING_FIX.GRAPH_NAME))
            .and(LINT_FINDING.ORDINAL.eq(LINT_FINDING_FIX.FINDING_ORDINAL))
            .join(LINT_FINDING_FIX_EDIT)
            .on(LINT_FINDING_FIX_EDIT.GRAPH_NAME.eq(LINT_FINDING_FIX.GRAPH_NAME))
            .and(LINT_FINDING_FIX_EDIT.FINDING_ORDINAL.eq(LINT_FINDING_FIX.FINDING_ORDINAL))
            .where(LINT_FINDING_FIX.GRAPH_NAME.eq(store.graphName()))
            .and(LINT_FINDING.FILE.eq(sourceName))
            .and(LINT_FINDING.SOURCE_LINE.isNotNull())
            .orderBy(LINT_FINDING_FIX.FINDING_ORDINAL, LINT_FINDING_FIX_EDIT.POSITION)
            .fetch();
        return rows.intoGroups(LINT_FINDING_FIX.FINDING_ORDINAL).values().stream()
            .map(group -> new Fix(
                group.getFirst().value2(),
                group.getFirst().value3(),
                group.map(row -> new Edit(
                    row.value4(), row.value5(), row.value6(), row.value7(), row.value8()))))
            .toList();
    }
}
