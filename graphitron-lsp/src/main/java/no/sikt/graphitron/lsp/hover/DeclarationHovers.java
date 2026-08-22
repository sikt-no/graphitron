package no.sikt.graphitron.lsp.hover;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.ClaimFacts;
import no.sikt.graphitron.lsp.facts.DeclarationFact;
import no.sikt.graphitron.lsp.facts.DeclarationFacts;
import no.sikt.graphitron.lsp.facts.SeparateFetchRule;
import no.sikt.graphitron.lsp.facts.SourceDeclarations;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;
import org.jooq.Field;
import org.jooq.Record;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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
 * <p>The classification block is the classifier a claim carries and the facts behind it, each read
 * from the relation that owns it. There is no variant name here and no per-variant payload: the block
 * is a heading and a list of labelled values, so a claim growing a fact is an arm of
 * {@link ClaimFacts#fieldArms}'s statement rather than a new arm in a projection's switch. A
 * declaration nothing claims gets no block, which is the same silence the inlay hint keeps at an
 * unclaimed declaration.
 *
 * <p>The whole hover costs one statement, both blocks together, and the per-claim reads it replaced
 * were the defect: a conflicted coordinate paid per directive that claimed it, for facts whose
 * relations were all keyed on the one coordinate being hovered.
 * {@code DeclarationHoverStatementCountTest} holds the number, because no assertion on the rendered
 * text can see it.
 *
 * <p>Beneath the classification block, the hover overlays what the graph's own facts say about the
 * declaration the coordinate binds to: a table's database comment, a column's or member's doc
 * comment, a backing class's doc comment. The binding is resolved through the shared
 * {@link DeclTarget} that goto-definition ({@code DeclarationDefinitions}) also projects, so the two
 * arms agree on which declaration the coordinate names; the overlay switch over {@code DeclTarget} is
 * exhaustive, mirroring goto's, so a new backing permit breaks both at compile time.
 *
 * <p>Both blocks are arms of one statement rather than two reads issued in order, which is what lets
 * the overlay render with no build behind it: what it needs is {@link DeclarationFacts}, and a
 * completed build buys neither surface anything either one of them reads. Goto reads the same rows
 * for the same declaration's position, so neither surface can be answering about a state of the
 * source the other has not seen.
 */
public final class DeclarationHovers {

    private DeclarationHovers() {}

    /**
     * Computes the declaration-name hover for {@code pos}: the declaration's claims and their
     * facts, and, when the coordinate binds to a jOOQ table / column or a Java declaration, what
     * the store holds about it overlaid beneath. Returns {@link Optional#empty()} when the cursor
     * is not on a recognised SDL declaration name token, or when neither block has anything to say.
     *
     * <p>Neither block is gated on a completed build any more, both being arms of one statement over
     * the store's own relations. No store at all is still no hover: the classification block and the
     * overlay are both what the store says, so a session with neither has nothing to render rather
     * than half a popup.
     */
    public static Optional<Hover> compute(
        FileSnapshot file, Optional<StoreHandle> store, Point pos
    ) {
        if (file == null || file.tree() == null) return Optional.empty();
        var declOpt = SdlDeclaration.findContaining(file.tree().getRootNode(), pos, file.source());
        if (declOpt.isEmpty()) return Optional.empty();
        if (store.isEmpty()) return Optional.empty();
        var handle = store.get();
        var declaration = declOpt.get();
        var hoverDecl = toDeclarationHover(declaration);
        var block = classificationBlock(handle, hoverDecl);
        var coord = DeclTarget.coordinateOf(declaration, file.source());
        var binding = DeclarationFacts.arms(handle, coord);
        var fields = new ArrayList<Field<?>>(block.arms());
        fields.addAll(binding.fields());
        var row = handle.dsl().select(fields).fetchOne();
        String classification = block.render().apply(row);
        var rows = binding.read(row);
        String overlay = overlay(DeclTarget.of(coord, rows), rows);
        if (classification == null && overlay.isEmpty()) return Optional.empty();
        return Optional.of(hover(file, hoverDecl.nameNode(), compose(classification, overlay)));
    }

    /**
     * The classification block's arms and how they render, chosen by grain. A holder rather than two
     * code paths because the two grains ask different relations and render differently while costing
     * the same one statement, and the switch over {@link DeclarationHover} stays exhaustive here so a
     * new declaration coordinate cannot reach the wire without content.
     */
    private record ClassificationBlock(List<Field<?>> arms, Function<Record, String> render) {}

    private static ClassificationBlock classificationBlock(
        StoreHandle store, DeclarationHover declaration
    ) {
        return switch (declaration) {
            case DeclarationHover.FieldDeclarationHover f -> {
                var arms = ClaimFacts.fieldArms(store, f.parentTypeName(), f.fieldName());
                yield new ClassificationBlock(arms.fields(),
                    row -> renderFieldMarkdown(f, arms.read(row)));
            }
            case DeclarationHover.TypeDeclarationHover t -> {
                var arms = ClaimFacts.typeArms(store, t.typeName());
                yield new ClassificationBlock(arms.fields(),
                    row -> renderTypeMarkdown(t, arms.read(row)));
            }
        };
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
    public static String overlay(DeclTarget target, DeclarationFacts.Rows rows) {
        return switch (target) {
            case DeclTarget.CatalogTable t -> tableDescription(rows, t.tableName(), t.classFqn());
            case DeclTarget.CatalogColumn c ->
                columnDescription(rows, c.tableName(), c.classFqn(), c.columnName());
            case DeclTarget.SourceClass s -> classJavadoc(rows, s.fqClassName());
            case DeclTarget.SourceMethod m -> SourceDeclarations.byArityThenName(
                rows.methodJavadocByArity(m.fqClassName(), m.methodName()), m.paramCount()).orElse("");
            case DeclTarget.SourceField f -> fieldJavadoc(rows, f.fqClassName(), f.memberName());
            case DeclTarget.None ignored -> "";
        };
    }

    /**
     * A table's own description: its database comment, else the doc comment on the class the census
     * recorded for it. A name two schemas both declare is answered for the first in schema order,
     * since the block above it has already named one table and an overlay is a paragraph rather than
     * a list; the coordinate hover, whose whole subject is the table, reports every match instead.
     */
    private static String tableDescription(
        DeclarationFacts.Rows rows, String tableName, String classFqn
    ) {
        var table = rows.tableNamed(tableName);
        // A census that does not hold the table still leaves the class reachable by name, which is
        // the state a dev session is in before it has run a catalog walk.
        if (table.isEmpty()) return classJavadoc(rows, classFqn);
        return firstNonBlank(table.get().description(), classJavadoc(rows, table.get().classFqn()));
    }

    /**
     * The named column's own description: the generated field's doc comment, else the database
     * comment. Matched under either of the two names the census carries, as every other column read
     * does, so a target resolved under the jOOQ spelling and one resolved under the SQL spelling
     * describe the same column.
     */
    private static String columnDescription(
        DeclarationFacts.Rows rows, String tableName, String classFqn, String columnName
    ) {
        return rows.columnNamed(tableName, columnName)
            .map(column -> firstNonBlank(
                fieldJavadoc(rows, classFqn, column.jooqName()), column.comment()))
            .orElse("");
    }

    /** The doc comment the java-source family holds for a class, out of the rows in hand. */
    private static String classJavadoc(DeclarationFacts.Rows rows, String classFqn) {
        return rows.classDeclaration(classFqn)
            .map(DeclarationFacts.ClassRow::javadoc)
            .orElse("");
    }

    /** The same for a field, which is a record component or a generated column constant. */
    private static String fieldJavadoc(
        DeclarationFacts.Rows rows, String classFqn, String fieldName
    ) {
        return rows.fieldDeclaration(classFqn, fieldName)
            .map(DeclarationFacts.FieldRow::javadoc)
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
    private static String renderFieldMarkdown(
        DeclarationHover.FieldDeclarationHover decl, ClaimFacts.FieldBlock block
    ) {
        if (block.isEmpty()) return null;
        var sb = new StringBuilder();
        var classifiers = block.classifiers();
        if (!classifiers.isEmpty()) sb.append("**").append(String.join(", ", classifiers)).append("**\n\n");
        sb.append("`").append(decl.coordinate()).append("`");
        for (var claim : block.claims()) {
            appendFacts(sb, claim.facts());
        }
        appendJoinPath(sb, block.joinPath());
        appendSeparateFetch(sb, block.fetchRules());
        return sb.toString();
    }

    /**
     * The type block, on the same shape as the field one and at the same cost, one statement.
     * {@code null} when the store says nothing about the type at all, which is a plain nesting object
     * and the population this block must stay quiet about.
     *
     * <p>A claim is not the only thing that opens it. A payload type reached through a
     * {@code @service} return carries no type directive, so no claim names it, and the store still
     * knows what stands for it: the class its producer hands back. That case renders with no
     * heading, the type name standing on its own above the backing line, the same shape the field
     * block takes when a claim-independent fact is all it has.
     *
     * <p>The backing shows only where no claim does, and the reason is not room. A claimed type's
     * classifier is the answer to what it is, and its backing follows from that answer: a
     * {@code @table} type's class is its table's generated record, which the table facts already
     * name one join away. That is a rendering rule, so it is applied here rather than asked of the
     * reader, which answers both regardless.
     *
     * <p>Where the two populations meet and disagree the hover says so rather than picking, which is
     * the one thing neither the inlay nor the resolving reader can do. That line exists because the
     * population had no surface at all: two producers naming different classes for one type is a
     * schema the generator refuses to bind, and every reader that needs one class is silent there by
     * design, so without it an author sees a payload type that renders like a plain object and no
     * reason why.
     */
    private static String renderTypeMarkdown(
        DeclarationHover.TypeDeclarationHover decl, ClaimFacts.TypeBlock block
    ) {
        if (block.isEmpty()) return null;
        var sb = new StringBuilder();
        var classifiers = block.classifiers();
        if (!classifiers.isEmpty()) sb.append("**").append(String.join(", ", classifiers)).append("**\n\n");
        sb.append("`").append(decl.typeName()).append("`");
        for (var claim : block.claims()) {
            appendFacts(sb, claim.facts());
        }
        if (!classifiers.isEmpty()) return sb.toString();
        if (block.backing() != null) {
            sb.append("\n\nBacked by: `").append(block.backing()).append("`");
        }
        if (!block.contested().isEmpty()) {
            // The join is here, at the point of display: the store answers with one row per
            // contesting class, and one canonical spelling of the set is a hover's business.
            sb.append("\n\nBacking contested, so nothing binds: `")
                .append(String.join(", ", block.contested())).append("`");
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
     * carry a child reached through a connection wrapper nor the polymorphic fan-in.
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
