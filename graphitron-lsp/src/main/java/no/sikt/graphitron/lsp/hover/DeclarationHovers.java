package no.sikt.graphitron.lsp.hover;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.SourceDeclarations;
import no.sikt.graphitron.lsp.inlay.LspClassificationLabels;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;

import java.util.Optional;
import java.util.stream.Collectors;

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
     * Classification-only entry: nothing captured and no catalog, so the block the classification
     * snapshot renders is the whole hover. What a session in that state can say about a declaration
     * name, and no less.
     */
    public static Optional<Hover> compute(
        FileSnapshot file, LspSchemaSnapshot snapshot, Point pos
    ) {
        return compute(file, CompletionData.empty(), Optional.empty(), snapshot, pos);
    }

    /**
     * Computes the declaration-name hover for {@code pos}: the classification
     * projection, and, when the coordinate binds to a jOOQ table / column or a Java
     * declaration, what the store holds about it overlaid beneath. Returns
     * {@link Optional#empty()} when the cursor is not on a recognised SDL declaration
     * name token, the snapshot is unavailable, or neither a classification nor an
     * overlay is available.
     */
    public static Optional<Hover> compute(
        FileSnapshot file, CompletionData catalog, Optional<StoreHandle> store,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return Optional.empty();
        if (file == null || file.tree() == null) return Optional.empty();
        var declOpt = SdlDeclaration.findContaining(file.tree().getRootNode(), pos, file.source());
        if (declOpt.isEmpty()) return Optional.empty();
        var declaration = declOpt.get();
        var hoverDecl = toDeclarationHover(declaration);
        String classification = classificationMarkdown(built, hoverDecl);
        // The overlay shares goto-definition's binding resolution, then reads the graph's own facts
        // about what it resolved to. The resolution itself needs the store now, a member name's
        // declaration being one of those facts, so it happens inside the read; no store is still no
        // overlay, leaving the classification block exactly as the classification arm renders it.
        String overlay = store
            .map(handle -> overlay(DeclTarget.resolve(declaration, built, catalog, handle, file.source()), handle))
            .orElse("");
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

    /** The classification block, or {@code null} when no projection entry exists. */
    private static String classificationMarkdown(LspSchemaSnapshot.Built built, DeclarationHover declaration) {
        return switch (declaration) {
            case DeclarationHover.FieldDeclarationHover f -> {
                var classification = built.fieldClassificationsByCoord().get(f.coordinate());
                yield classification == null ? null : renderFieldMarkdown(f, classification);
            }
            case DeclarationHover.TypeDeclarationHover t -> {
                var classification = built.typeClassificationsByName().get(t.typeName());
                yield classification == null ? null : renderTypeMarkdown(t, classification);
            }
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

    private static String renderFieldMarkdown(
        DeclarationHover.FieldDeclarationHover decl, FieldClassification classification
    ) {
        var sb = new StringBuilder();
        sb.append("**").append(FieldClassification.class.getSimpleName()).append(".")
          .append(LspClassificationLabels.projectionLabel(classification)).append("**");
        sb.append("\n\n`").append(decl.coordinate()).append("`");
        switch (classification) {
            case FieldClassification.Column c ->
                sb.append("\n\nColumn `").append(nullSafe(c.columnName())).append("`")
                  .append(c.tableName() != null ? " on `" + c.tableName() + "`" : "");
            case FieldClassification.ColumnReference c -> {
                sb.append("\n\nColumn `").append(nullSafe(c.columnName())).append("`")
                  .append(c.tableName() != null ? " on `" + c.tableName() + "`" : "");
                appendJoinPath(sb, c.joinPath());
            }
            case FieldClassification.ParticipantCrossTable c ->
                sb.append("\n\nColumn `").append(nullSafe(c.columnName())).append("` on `")
                  .append(nullSafe(c.targetTableName())).append("` (FK `")
                  .append(nullSafe(c.fkName())).append("`, alias `")
                  .append(nullSafe(c.alias())).append("`)");
            case FieldClassification.CompositeColumn c ->
                sb.append("\n\nColumns `").append(String.join("`, `", c.columnNames()))
                  .append("` on `").append(nullSafe(c.tableName())).append("`");
            case FieldClassification.CompositeColumnReference c -> {
                sb.append("\n\nColumns `").append(String.join("`, `", c.columnNames()))
                  .append("` on `").append(nullSafe(c.tableName())).append("`");
                appendJoinPath(sb, c.joinPath());
            }
            case FieldClassification.TableTarget t -> {
                sb.append("\n\nTarget table: `").append(nullSafe(t.tableName())).append("`");
                appendJoinPath(sb, t.joinPath());
                if (t.splitBatched()) sb.append("\n- batched via DataLoader");
                if (t.hasLookupKey()) sb.append("\n- lookup-key mapping");
            }
            case FieldClassification.RecordTableTarget t -> {
                sb.append("\n\nTarget table: `").append(nullSafe(t.tableName())).append("`");
                appendJoinPath(sb, t.joinPath());
                if (t.hasLookupKey()) sb.append("\n- lookup-key mapping");
            }
            case FieldClassification.TableInterface t -> {
                sb.append("\n\nInterface table: `").append(nullSafe(t.tableName())).append("`");
                sb.append("\n\nDiscriminator: `").append(nullSafe(t.discriminatorColumn())).append("`");
                appendParticipants(sb, t.participantTypeNames());
            }
            case FieldClassification.Polymorphic p ->
                appendParticipants(sb, p.participantTypeNames());
            case FieldClassification.Nesting ignored ->
                sb.append("\n\nNested projection on parent table.");
            case FieldClassification.Pivot p ->
                sb.append("\n\nPivot over `").append(nullSafe(p.tableName()))
                  .append("`: one aggregate of `").append(nullSafe(p.valueColumn()))
                  .append("` per `").append(nullSafe(p.onColumn())).append("` token")
                  .append(p.batched() ? "\n- batched via DataLoader" : "");
            case FieldClassification.ServiceBacked s ->
                sb.append("\n\nService method `").append(nullSafe(s.methodClassName())).append("#")
                  .append(nullSafe(s.methodName())).append("`")
                  .append(s.tableBound() ? " → `" + nullSafe(s.tableName()) + "`" : "")
                  .append(errorChannelSuffix(s.errorChannelMappingName()));
            case FieldClassification.RecordOrProperty r ->
                sb.append(r.columnName() != null ? "\n\nColumn: `" + r.columnName() + "`" : "")
                  .append(r.accessorName() != null ? "\n\nAccessor: `" + r.accessorName() + "`" : "");
            case FieldClassification.Computed c ->
                sb.append("\n\nComputed via `").append(nullSafe(c.methodClassName())).append("#")
                  .append(nullSafe(c.methodName())).append("`");
            case FieldClassification.InputUnbound c -> {
                if (c.methodClassName() != null) {
                    sb.append("\n\nUnbound input field via `")
                      .append(nullSafe(c.methodClassName())).append("#")
                      .append(nullSafe(c.methodName())).append("`")
                      .append(c.override() ? " (override:true)" : " (override:false)");
                } else {
                    sb.append("\n\nUnbound input field (no column binding, no @condition)");
                }
            }
            case FieldClassification.Errors e -> {
                sb.append("\n\nError types:");
                for (String name : e.errorTypeNames()) sb.append("\n- `").append(name).append("`");
            }
            case FieldClassification.SingleRecordIdFromReturning ignored ->
                sb.append("\n\nEncoded PK echo from RETURNING.");
            case FieldClassification.SingleRecordId s ->
                sb.append("\n\nEncoded node id off the @service producer's record")
                  .append(s.tableName() != null ? " (table `" + s.tableName() + "`)" : "")
                  .append("; no re-fetch.");
            case FieldClassification.QueryTable q ->
                sb.append("\n\nQuery table: `").append(nullSafe(q.tableName())).append("`")
                  .append(q.isLookup() ? "\n\nLookup helper." : "");
            case FieldClassification.RoutineBacked q ->
                sb.append("\n\nMethod `").append(nullSafe(q.methodClassName())).append("#")
                  .append(nullSafe(q.methodName())).append("` → `")
                  .append(nullSafe(q.tableName())).append("`");
            case FieldClassification.QueryNode q ->
                sb.append("\n\nRelay node fetcher (").append(q.isList() ? "list" : "single").append(")");
            case FieldClassification.QueryTableInterface q -> {
                sb.append("\n\nInterface table: `").append(nullSafe(q.tableName())).append("`");
                sb.append("\n\nDiscriminator: `").append(nullSafe(q.discriminatorColumn())).append("`");
                appendParticipants(sb, q.participantTypeNames());
            }
            case FieldClassification.QueryPolymorphic p ->
                appendParticipants(sb, p.participantTypeNames());
            case FieldClassification.QueryService s ->
                sb.append("\n\nService method `").append(nullSafe(s.methodClassName())).append("#")
                  .append(nullSafe(s.methodName())).append("`")
                  .append(s.tableBound() ? " → `" + nullSafe(s.tableName()) + "`" : "")
                  .append(errorChannelSuffix(s.errorChannelMappingName()));
            case FieldClassification.DmlMutation dml ->
                sb.append("\n\nKind: ").append(dml.kind())
                  .append("\n\nTable: `").append(nullSafe(dml.tableName())).append("`")
                  .append("\n\nInput type: `").append(nullSafe(dml.inputTypeName())).append("`")
                  .append(errorChannelSuffix(dml.errorChannelMappingName()));
            case FieldClassification.MutationService s ->
                sb.append("\n\nService method `").append(nullSafe(s.methodClassName())).append("#")
                  .append(nullSafe(s.methodName())).append("`")
                  .append(s.tableBound() ? " → `" + nullSafe(s.tableName()) + "`" : " (record return)")
                  .append(errorChannelSuffix(s.errorChannelMappingName()));
            case FieldClassification.DmlRecord r ->
                sb.append("\n\nKind: ").append(r.kind()).append(r.bulk() ? " (bulk)" : "")
                  .append("\n\nTable: `").append(nullSafe(r.tableName())).append("`")
                  .append("\n\nInput type: `").append(nullSafe(r.inputTypeName())).append("`")
                  .append(errorChannelSuffix(r.errorChannelMappingName()));
            case FieldClassification.Unresolvable u ->
                sb.append("\n\nReason: ").append(u.reason());
            case FieldClassification.Conflicted c -> {
                sb.append("\n\nClaims:");
                for (var claim : c.claims()) {
                    sb.append("\n- ").append(claimLine(claim));
                }
                sb.append("\n\nViolation: ").append(c.violation());
            }
        }
        return sb.toString();
    }

    /**
     * One conflicted-field claim as a hover line: the claim arm's simple name (the store's
     * classifier vocabulary), its trigger, and its slot facts. Exhaustive over the sealed
     * {@link FieldClassification.Claim} permits with no default.
     */
    private static String claimLine(FieldClassification.Claim claim) {
        var sb = new StringBuilder();
        switch (claim) {
            case FieldClassification.Claim.Service s -> {
                sb.append("Service (@").append(s.trigger()).append(")");
                if (s.methodClassName() != null) {
                    sb.append(": `").append(s.methodClassName()).append("#").append(nullSafe(s.methodName())).append("`");
                }
            }
            case FieldClassification.Claim.ExternalField e -> {
                sb.append("ExternalField (@").append(e.trigger()).append(")");
                if (e.methodClassName() != null) {
                    sb.append(": `").append(e.methodClassName()).append("#").append(nullSafe(e.methodName())).append("`");
                }
            }
            case FieldClassification.Claim.NodeId n -> {
                sb.append("NodeId (@").append(n.trigger()).append(")");
                if (n.nodeTypeRef() != null) {
                    sb.append(": `").append(n.nodeTypeRef()).append("`");
                }
            }
            case FieldClassification.Claim.LookupKey l ->
                sb.append("LookupKey (@").append(l.trigger()).append(")");
            case FieldClassification.Claim.Routine r -> {
                sb.append("Routine (@").append(r.trigger()).append(")");
                if (r.routineRefs() != null && !r.routineRefs().isEmpty()) {
                    // Comma, not a path arrow: the steps' order is a slot fact, their adjacency
                    // is not (@reference hops may interleave, and the store's per-name ordinal
                    // does not model cross-directive order).
                    sb.append(": ").append(r.routineRefs().stream()
                        .map(ref -> "`" + ref + "`")
                        .collect(Collectors.joining(", ")));
                }
            }
            case FieldClassification.Claim.Mutation m -> {
                sb.append("Mutation (@").append(m.trigger()).append(")");
                if (m.dmlKind() != null) {
                    sb.append(": ").append(m.dmlKind());
                }
                if (m.tableName() != null) {
                    sb.append(" → `").append(m.tableName()).append("`");
                }
            }
        }
        if (!claim.decoded()) {
            sb.append(" (arguments did not decode)");
        }
        return sb.toString();
    }

    private static String renderTypeMarkdown(
        DeclarationHover.TypeDeclarationHover decl, TypeClassification classification
    ) {
        var sb = new StringBuilder();
        sb.append("**").append(TypeClassification.class.getSimpleName()).append(".")
          .append(LspClassificationLabels.projectionTypeLabel(classification)).append("**");
        sb.append("\n\n`").append(decl.typeName()).append("`");
        switch (classification) {
            case TypeClassification.Table t ->
                sb.append("\n\nTable: `").append(nullSafe(t.tableName())).append("`");
            case TypeClassification.Node n -> {
                sb.append("\n\nTable: `").append(nullSafe(n.tableName())).append("`");
                if (n.typeId() != null) sb.append("\n\nTypeId: `").append(n.typeId()).append("`");
                if (!n.keyColumnNames().isEmpty())
                    sb.append("\n\nKey columns: `").append(String.join("`, `", n.keyColumnNames())).append("`");
            }
            case TypeClassification.TableInterface ti -> {
                sb.append("\n\nTable: `").append(nullSafe(ti.tableName())).append("`");
                sb.append("\n\nDiscriminator: `").append(nullSafe(ti.discriminatorColumn())).append("`");
                appendParticipants(sb, ti.participantTypeNames());
            }
            case TypeClassification.Interface i ->
                appendParticipants(sb, i.participantTypeNames());
            case TypeClassification.Union u ->
                appendParticipants(sb, u.participantTypeNames());
            case TypeClassification.JavaRecord t ->
                sb.append("\n\nBacking record: `").append(nullSafe(t.fqClassName())).append("`");
            case TypeClassification.JavaRecordInput t ->
                sb.append("\n\nBacking record: `").append(nullSafe(t.fqClassName())).append("`");
            case TypeClassification.JooqRecord t ->
                sb.append("\n\njOOQ record class: `").append(nullSafe(t.fqClassName())).append("`");
            case TypeClassification.JooqRecordInput t ->
                sb.append("\n\njOOQ record class: `").append(nullSafe(t.fqClassName())).append("`");
            case TypeClassification.JooqTableRecord t ->
                sb.append("\n\nClass: `").append(nullSafe(t.fqClassName())).append("`")
                  .append("\n\nTable: `").append(nullSafe(t.tableName())).append("`");
            case TypeClassification.JooqTableRecordInput t ->
                sb.append("\n\nClass: `").append(nullSafe(t.fqClassName())).append("`")
                  .append("\n\nTable: `").append(nullSafe(t.tableName())).append("`");
            case TypeClassification.PojoResult t ->
                sb.append("\n\nBacking class: `").append(nullSafe(t.fqClassName())).append("`");
            case TypeClassification.PojoInput p -> {
                if (p.fqClassName() != null) {
                    sb.append("\n\nBacking class: `").append(p.fqClassName()).append("`");
                }
                if (!p.resolvedTables().isEmpty()) {
                    sb.append("\n\nResolved table").append(p.resolvedTables().size() == 1 ? "" : "s")
                      .append(": `").append(String.join("`, `", p.resolvedTables())).append("`");
                }
            }
            case TypeClassification.Root r ->
                sb.append("\n\nOperation: ").append(r.operation());
            case TypeClassification.Connection c ->
                sb.append("\n\nElement: `").append(nullSafe(c.elementTypeName())).append("`")
                  .append("\n\nEdge: `").append(nullSafe(c.edgeTypeName())).append("`");
            case TypeClassification.Edge e ->
                sb.append("\n\nElement: `").append(nullSafe(e.elementTypeName())).append("`");
            case TypeClassification.PageInfo ignored ->
                sb.append("\n\nRelay page-info wrapper.");
            case TypeClassification.Error e -> {
                if (!e.handlerKinds().isEmpty())
                    sb.append("\n\nHandlers: ").append(String.join(", ", e.handlerKinds()));
            }
            case TypeClassification.Enum ignored ->
                sb.append("\n\nGraphQL enum.");
            case TypeClassification.Scalar s ->
                sb.append("\n\nJava type: `").append(nullSafe(s.javaType())).append("`");
            case TypeClassification.PlainObject ignored ->
                sb.append("\n\nPlain SDL object (no domain directive).");
            case TypeClassification.Unclassified u ->
                sb.append("\n\nReason: ").append(u.reason());
        }
        return sb.toString();
    }

    private static void appendJoinPath(StringBuilder sb, java.util.List<FieldClassification.FkStep> path) {
        if (path == null || path.isEmpty()) return;
        sb.append("\n\nJoin path:");
        for (var step : path) {
            sb.append("\n- ");
            if (step.fkName() != null) sb.append("`").append(step.fkName()).append("` → ");
            if (step.targetTableName() != null) sb.append("`").append(step.targetTableName()).append("`");
        }
    }

    private static void appendParticipants(StringBuilder sb, java.util.List<String> names) {
        if (names == null || names.isEmpty()) return;
        sb.append("\n\nParticipants: ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("`").append(names.get(i)).append("`");
        }
    }

    private static String errorChannelSuffix(String name) {
        return name == null ? "" : "\n\nError channel: `" + name + "`";
    }

    private static String nullSafe(String s) {
        return s == null ? "?" : s;
    }


    private static Hover hover(FileSnapshot file, Node anchor, String markdown) {
        var content = new MarkupContent(MarkupKind.MARKDOWN, markdown);
        var start = Positions.toLspPosition(file.source(), anchor.getStartByte());
        var end = Positions.toLspPosition(file.source(), anchor.getEndByte());
        return new Hover(content, new Range(start, end));
    }
}
