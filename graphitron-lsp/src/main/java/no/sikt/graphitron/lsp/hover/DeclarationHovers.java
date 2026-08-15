package no.sikt.graphitron.lsp.hover;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.ClaimClassifiers;
import no.sikt.graphitron.lsp.facts.ClaimFacts;
import no.sikt.graphitron.lsp.facts.DeclarationFact;
import no.sikt.graphitron.lsp.facts.SeparateFetchRule;
import no.sikt.graphitron.lsp.facts.SourceDeclarations;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * Classification-hover dispatch on SDL declaration coordinates. Parallel to
 * {@link Hovers}'s directive-argument-keyed dispatch: where {@link Hovers} keys on the
 * cursor sitting inside a directive, this dispatch keys on the cursor sitting on a
 * field-declaration or type-declaration <em>name token</em> (outside any directive).
 *
 * <p>The exhaustive switch over {@link DeclarationHover} permits enforces that any new
 * SDL declaration coordinate added to the sealed family fails to compile until its
 * hover content lands here in the same commit.
 *
 * <p>The classification block is the classifier a claim carries ({@link ClaimClassifiers}) and the
 * facts behind it ({@link ClaimFacts}), each read from the relation that owns it. There is no
 * variant name here and no per-variant payload: the block is a heading and a list of labelled
 * values, so a claim growing a fact is a query in the reader rather than a new arm in a switch. A
 * declaration nothing claims gets no block, which is the same silence the inlay hint keeps at an
 * unclaimed declaration.
 *
 * <p>Beneath the classification block, the hover overlays what the graph's own facts say about the
 * declaration the coordinate binds to: a table's database comment, a column's or member's doc
 * comment, a backing class's doc comment. The binding is resolved through the shared
 * {@link DeclTarget} that goto-definition ({@code DeclarationDefinitions}) also projects, so the two
 * arms agree on which declaration the coordinate names; the overlay switch over {@code DeclTarget} is
 * exhaustive, mirroring goto's, so a new backing permit breaks both at compile time.
 *
 * <p>The two reads have parted company, though: the overlay is a query and goto's jump is still a
 * lookup in the LSP-owned source index. They answer about one declaration as long as both are
 * refreshed off the same parse, which is what a dev session does, but a session holding only one of
 * the two answers on only one surface. Nothing here can hide that by reading the other's substrate:
 * the resolution hands over names.
 */
public final class DeclarationHovers {

    private DeclarationHovers() {}

    /**
     * Computes the declaration-name hover for {@code pos}: the declaration's claims and their
     * facts, and, when the coordinate binds to a jOOQ table / column or a Java declaration, what
     * the store holds about it overlaid beneath. Returns {@link Optional#empty()} when the cursor
     * is not on a recognised SDL declaration name token, or when neither block has anything to say.
     *
     * <p>The two blocks are gated separately, on what each reads. The classification block needs
     * the store and nothing else, so it renders in a session that has captured but not generated;
     * the overlay additionally needs the snapshot, its binding resolution still going through the
     * classifier's projection.
     */
    public static Optional<Hover> compute(
        FileSnapshot file, Optional<StoreHandle> store,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        if (file == null || file.tree() == null) return Optional.empty();
        var declOpt = SdlDeclaration.findContaining(file.tree().getRootNode(), pos, file.source());
        if (declOpt.isEmpty()) return Optional.empty();
        var declaration = declOpt.get();
        var hoverDecl = toDeclarationHover(declaration);
        String classification = store
            .map(handle -> classificationMarkdown(handle, hoverDecl))
            .orElse(null);
        // The overlay shares goto-definition's binding resolution, then reads the graph's own facts
        // about what it resolved to. The resolution itself needs the store now, a member name's
        // declaration being one of those facts, so it happens inside the read; no store is still no
        // overlay, leaving the classification block exactly as the classification arm renders it.
        String overlay = store.isPresent() && snapshot instanceof LspSchemaSnapshot.Built built
            ? overlay(DeclTarget.resolve(declaration, built, store.get(), file.source()), store.get())
            : "";
        if (classification == null && overlay.isEmpty()) return Optional.empty();
        return Optional.of(hover(file, hoverDecl.nameNode(), compose(classification, overlay)));
    }

    /**
     * Classifies the declaration name {@code pos} sits on, mapping the shared
     * {@link SdlDeclaration} trigger onto this feature's hover-content family.
     * The leaf-walk-and-classify itself lives in {@link SdlDeclaration} so the
     * hover and goto-definition triggers cannot drift apart; this method only
     * adapts the result.
     */
    public static Optional<DeclarationHover> findContaining(Node root, Point pos, byte[] source) {
        return SdlDeclaration.findContaining(root, pos, source).map(DeclarationHovers::toDeclarationHover);
    }

    private static DeclarationHover toDeclarationHover(SdlDeclaration declaration) {
        return switch (declaration) {
            case SdlDeclaration.TypeName t ->
                new DeclarationHover.TypeDeclarationHover(t.nameNode(), t.typeName());
            case SdlDeclaration.FieldName f ->
                new DeclarationHover.FieldDeclarationHover(f.nameNode(), f.parentTypeName(), f.fieldName());
        };
    }

    /** The classification block, or {@code null} when nothing claims the declaration. */
    private static String classificationMarkdown(StoreHandle store, DeclarationHover declaration) {
        return switch (declaration) {
            case DeclarationHover.FieldDeclarationHover f -> renderFieldMarkdown(store, f);
            case DeclarationHover.TypeDeclarationHover t -> renderTypeMarkdown(store, t);
        };
    }

    /**
     * The description overlay for the resolved {@link DeclTarget}, or empty when the coordinate binds
     * to nothing the store describes. Switches over the same target goto-definition projects to a
     * {@code Location}; the difference is only which fact each arm asks for.
     *
     * <p>Each of the two catalog arms picks its own text where a table has both a database comment and
     * a generated class Javadoc. A table's comment wins, its generated Javadoc being boilerplate that
     * names the table back at the reader; a column's Javadoc wins, being the richer of the two (it
     * carries the qualified column name and the comment where there is one). That is the precedence
     * the coordinate hovers and the completion popup already apply, per surface, because it is a
     * rendering choice rather than a fact.
     *
     * <p>Public so {@code DeclarationHoverOverlayParityTest} can assert per variant, without a
     * tree-sitter round-trip, that the overlay answers for exactly the targets goto-definition jumps
     * on when both substrates hold the same parse.
     */
    public static String overlay(DeclTarget target, StoreHandle store) {
        return switch (target) {
            case DeclTarget.CatalogTable t -> tableDescription(store, t.tableName(), t.classFqn());
            case DeclTarget.CatalogColumn c -> columnDescription(store, c.tableName(), c.columnName());
            case DeclTarget.SourceClass s -> SourceDeclarations.classJavadoc(store, s.fqClassName());
            case DeclTarget.SourceMethod m ->
                SourceDeclarations.methodJavadoc(store, m.fqClassName(), m.methodName(), m.paramCount());
            case DeclTarget.SourceField f -> SourceDeclarations.fieldJavadoc(store, f.fqClassName(), f.memberName());
            case DeclTarget.None ignored -> "";
        };
    }

    /**
     * A table's own description, in one query: its database comment, else the doc comment on the
     * class the census recorded for it. A name two schemas both declare is answered for the first in
     * schema order, since the block above it has already named one table and an overlay is a
     * paragraph rather than a list; the coordinate hover, whose whole subject is the table, reports
     * every match instead.
     */
    private static String tableDescription(StoreHandle store, String tableName, String classFqn) {
        if (tableName == null) return SourceDeclarations.classJavadoc(store, classFqn);
        var row = store.dsl()
            .select(SQL_TABLE.DESCRIPTION, SourceDeclarations.classJavadocOf(SQL_TABLE.CLASS_FQN))
            .from(SQL_TABLE)
            .where(store.reads(SQL_TABLE.SOURCE_NAME))
            .and(SQL_TABLE.TABLE_NAME.equalIgnoreCase(tableName))
            .orderBy(SQL_TABLE.TABLE_SCHEMA)
            .limit(1)
            .fetchOne();
        // A census that does not hold the table still leaves the class reachable by name, which is
        // the state a dev session is in before it has run a catalog walk.
        if (row == null) return SourceDeclarations.classJavadoc(store, classFqn);
        return firstNonBlank(row.value1(), row.value2());
    }

    /**
     * The named column's own description: the generated field's doc comment, else the database
     * comment. Matched under either of the two names the census carries, as every other column read
     * does, so a target resolved under the jOOQ spelling and one resolved under the SQL spelling
     * describe the same column.
     */
    private static String columnDescription(StoreHandle store, String tableName, String columnName) {
        if (tableName == null || columnName == null) return "";
        return CatalogColumns.of(store, tableName).stream()
            .filter(column -> column.isNamed(columnName))
            .findFirst()
            .map(column -> firstNonBlank(column.javadoc(), column.comment()))
            .orElse("");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        return fallback == null ? "" : fallback;
    }

    /** Joins the (nullable) classification block and the (possibly empty) overlay with a rule. */
    private static String compose(String classification, String overlay) {
        var sb = new StringBuilder();
        if (classification != null) sb.append(classification);
        if (!overlay.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(overlay);
        }
        return sb.toString();
    }

    /**
     * The field block: every classifier claiming the coordinate, the facts behind each, where an
     * authored {@code @reference} path lands, and whether the field costs a round-trip of its own.
     * {@code null} when nothing claims the coordinate.
     *
     * <p>Several classifiers at one coordinate is a conflict, and it renders as its claims: the
     * heading names them all and each contributes its own facts, so the reader sees what the two
     * directives each asked for rather than a word meaning "these disagree".
     *
     * <p>The two claim-independent facts hold a block open on their own. A {@code @splitQuery} child
     * returning a table type is claimed by nothing (no directive names what it is, and the
     * structural classifier only reaches leaf fields), and it is the field an author most wants the
     * round-trip answer about; gating the block on a claim would silence it exactly there.
     */
    private static String renderFieldMarkdown(StoreHandle store, DeclarationHover.FieldDeclarationHover decl) {
        var classifiers = ClaimClassifiers.ofFields(store, List.of(decl.parentTypeName()))
            .getOrDefault(decl.coordinate(), List.of());
        var joinPath = ClaimFacts.joinPath(store, decl.parentTypeName(), decl.fieldName());
        var fetchRules = ClaimFacts.separateFetchRules(store, List.of(decl.parentTypeName()))
            .getOrDefault(decl.coordinate(), List.of());
        if (classifiers.isEmpty() && joinPath.isEmpty() && fetchRules.isEmpty()) return null;
        var sb = new StringBuilder();
        if (!classifiers.isEmpty()) sb.append("**").append(String.join(", ", classifiers)).append("**\n\n");
        sb.append("`").append(decl.coordinate()).append("`");
        for (String classifier : classifiers) {
            appendFacts(sb, ClaimFacts.ofField(store, decl.parentTypeName(), decl.fieldName(), classifier));
        }
        appendJoinPath(sb, joinPath);
        appendSeparateFetch(sb, fetchRules);
        return sb.toString();
    }

    /** The type block, on the same shape as the field one. {@code null} when nothing claims the type. */
    private static String renderTypeMarkdown(StoreHandle store, DeclarationHover.TypeDeclarationHover decl) {
        var classifiers = ClaimClassifiers.ofTypes(store, List.of(decl.typeName()))
            .getOrDefault(decl.typeName(), List.of());
        if (classifiers.isEmpty()) return null;
        var sb = new StringBuilder();
        sb.append("**").append(String.join(", ", classifiers)).append("**");
        sb.append("\n\n`").append(decl.typeName()).append("`");
        for (String classifier : classifiers) {
            appendFacts(sb, ClaimFacts.ofType(store, decl.typeName(), classifier));
        }
        return sb.toString();
    }

    /** One line per fact, in the order the reader produced them. */
    private static void appendFacts(StringBuilder sb, List<DeclarationFact> facts) {
        for (var fact : facts) {
            sb.append("\n\n").append(fact.label()).append(": `").append(fact.value()).append("`");
        }
    }

    private static void appendJoinPath(StringBuilder sb, List<ClaimFacts.JoinStep> path) {
        if (path.isEmpty()) return;
        sb.append("\n\nJoin path:");
        for (var step : path) {
            sb.append("\n- ");
            if (step.constraintName() != null) sb.append("`").append(step.constraintName()).append("` \u2192 ");
            if (step.toTable() != null) sb.append("`").append(step.toTable()).append("`");
        }
    }

    /**
     * The round-trip line: this field's rows come from a statement of its own, and why. Rendered
     * only where a rule reaches the field, never as its negation, because the relation does not yet
     * carry the implicit split a class-backed parent forces on its table-typed children.
     *
     * <p>Every rule renders, the universal one included: the inlay marker suppresses that case to
     * keep a root type from repeating itself, but a reader who hovered one declaration asked about
     * that declaration and gets the whole answer.
     */
    private static void appendSeparateFetch(StringBuilder sb, List<String> rules) {
        if (rules.isEmpty()) return;
        sb.append("\n\nFetched separately:");
        for (String rule : rules) {
            sb.append("\n- ").append(SeparateFetchRule.of(rule)
                .map(SeparateFetchRule::description)
                .orElse(rule));
        }
    }

    private static Hover hover(FileSnapshot file, Node anchor, String markdown) {
        var content = new MarkupContent(MarkupKind.MARKDOWN, markdown);
        var start = Positions.toLspPosition(file.source(), anchor.getStartByte());
        var end = Positions.toLspPosition(file.source(), anchor.getEndByte());
        return new Hover(content, new Range(start, end));
    }
}
