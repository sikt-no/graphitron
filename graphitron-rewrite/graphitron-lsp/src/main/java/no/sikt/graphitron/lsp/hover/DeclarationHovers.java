package no.sikt.graphitron.lsp.hover;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.Descriptions;
import no.sikt.graphitron.lsp.inlay.LspClassificationLabels;
import no.sikt.graphitron.lsp.parsing.DeclTarget;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.parsing.SdlDeclaration;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Range;

import java.util.Optional;

/**
 * R160 — classification-hover dispatch on SDL declaration coordinates. Parallel to
 * {@link Hovers}'s directive-argument-keyed dispatch: where {@link Hovers} keys on the
 * cursor sitting inside a directive, this dispatch keys on the cursor sitting on a
 * field-declaration or type-declaration <em>name token</em> (outside any directive).
 *
 * <p>The exhaustive switch over {@link DeclarationHover} permits enforces that any new
 * SDL declaration coordinate added to the sealed family fails to compile until its
 * hover content lands here in the same commit.
 *
 * <p>R371 — beneath the classification block, the hover overlays the jOOQ class /
 * column / member Javadoc the coordinate binds to, read at request time from the
 * LSP-owned {@link SourceWalker.Index}. The binding is resolved through the shared
 * {@link DeclTarget} that goto-definition ({@code DeclarationDefinitions}) also
 * projects, so the two arms agree on which declaration the coordinate names: goto
 * jumps to its {@code location()}, hover overlays its {@code javadoc()}. The overlay
 * switch over {@code DeclTarget} is exhaustive, mirroring goto's, so a new backing
 * permit breaks both at compile time.
 */
public final class DeclarationHovers {

    private DeclarationHovers() {}

    /**
     * Classification-only entry: no source-index Javadoc overlay. Back-compat for
     * callers that carry no catalog / source index; the production path uses the
     * five-arg overload so the R371 declaration-name overlay lights up.
     */
    public static Optional<Hover> compute(
        WorkspaceFile file, LspSchemaSnapshot snapshot, Point pos
    ) {
        return compute(file, null, SourceWalker.Index.EMPTY, snapshot, pos);
    }

    /**
     * Computes the declaration-name hover for {@code pos}: the classification
     * projection (R160), and, when the catalog / source index resolve the
     * coordinate to a jOOQ class / column / member, that declaration's Javadoc
     * overlaid beneath it (R371). Returns {@link Optional#empty()} when the cursor
     * is not on a recognised SDL declaration name token, the snapshot is
     * unavailable, or neither a classification nor an overlay is available.
     */
    public static Optional<Hover> compute(
        WorkspaceFile file, CompletionData catalog, SourceWalker.Index sourceIndex,
        LspSchemaSnapshot snapshot, Point pos
    ) {
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) return Optional.empty();
        if (file == null || file.tree() == null) return Optional.empty();
        var declOpt = SdlDeclaration.findContaining(file.tree().getRootNode(), pos, file.source());
        if (declOpt.isEmpty()) return Optional.empty();
        var declaration = declOpt.get();
        var hoverDecl = toDeclarationHover(declaration);
        String classification = classificationMarkdown(built, hoverDecl);
        // The overlay shares goto-definition's binding resolution. A null catalog
        // (the classification-only entry) skips it; an empty source index yields no
        // overlay, leaving the classification block exactly as R160 rendered it.
        String overlay = catalog == null
            ? ""
            : overlay(DeclTarget.resolve(declaration, built, catalog, file.source()), sourceIndex);
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

    /** The R160 classification block, or {@code null} when no projection entry exists. */
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
     * The R371 Javadoc overlay for the resolved {@link DeclTarget}, or empty when the
     * coordinate binds to no Java declaration or the source index does not hold it.
     * Switches over the same target goto-definition projects to a {@code Location};
     * the table / column arms honour {@link Descriptions}'s catalog-description
     * precedence, the member arms read the indexed {@code Decl} directly (the catalog
     * carries no description to layer under a POJO accessor or record component).
     *
     * <p>Public so the LSP tier can assert, per variant, that this overlay is
     * non-empty exactly when goto-definition jumps (the R371 parity property),
     * without a tree-sitter round-trip.
     */
    public static String overlay(DeclTarget target, SourceWalker.Index sourceIndex) {
        return switch (target) {
            case DeclTarget.CatalogTable t -> Descriptions.ofTable(t.table(), sourceIndex);
            case DeclTarget.CatalogColumn c -> Descriptions.ofColumn(c.table(), c.column(), sourceIndex);
            case DeclTarget.SourceClass s -> Descriptions.classJavadoc(s.fqClassName(), sourceIndex);
            case DeclTarget.SourceMethod m -> memberJavadoc(
                sourceIndex.methods().get(new SourceWalker.MethodKey(m.fqClassName(), m.accessorMethodName(), 0)));
            case DeclTarget.SourceField f -> memberJavadoc(
                sourceIndex.fields().get(new SourceWalker.FieldKey(f.fqClassName(), f.memberName())));
            case DeclTarget.None ignored -> "";
        };
    }

    private static String memberJavadoc(SourceWalker.Decl decl) {
        return decl != null ? decl.javadoc() : "";
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
            case FieldClassification.TableMethod t ->
                sb.append("\n\nTable method `").append(nullSafe(t.methodClassName())).append("#")
                  .append(nullSafe(t.methodName())).append("` → `")
                  .append(nullSafe(t.tableName())).append("`");
            case FieldClassification.TableInterface t -> {
                sb.append("\n\nInterface table: `").append(nullSafe(t.tableName())).append("`");
                sb.append("\n\nDiscriminator: `").append(nullSafe(t.discriminatorColumn())).append("`");
                appendParticipants(sb, t.participantTypeNames());
            }
            case FieldClassification.Polymorphic p ->
                appendParticipants(sb, p.participantTypeNames());
            case FieldClassification.Nesting ignored ->
                sb.append("\n\nNested projection on parent table.");
            case FieldClassification.ServiceBacked s ->
                sb.append("\n\nService method `").append(nullSafe(s.methodClassName())).append("#")
                  .append(nullSafe(s.methodName())).append("`")
                  .append(s.tableBound() ? " → `" + nullSafe(s.tableName()) + "`" : "")
                  .append(errorChannelSuffix(s.errorChannelMappingName()));
            case FieldClassification.RecordOrProperty r ->
                sb.append("\n\nColumn: `").append(nullSafe(r.columnName())).append("`")
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
            case FieldClassification.QueryTableMethod q ->
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
            case FieldClassification.Unclassified u ->
                sb.append("\n\nReason: ").append(u.reason());
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
            case TypeClassification.PojoInput p ->
                sb.append("\n\nBacking class: `").append(nullSafe(p.fqClassName())).append("`");
            case TypeClassification.TableInput ti ->
                sb.append("\n\nTable: `").append(nullSafe(ti.tableName())).append("`");
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


    private static Hover hover(WorkspaceFile file, Node anchor, String markdown) {
        var content = new MarkupContent(MarkupKind.MARKDOWN, markdown);
        var start = Positions.toLspPosition(file.source(), anchor.getStartByte());
        var end = Positions.toLspPosition(file.source(), anchor.getEndByte());
        return new Hover(content, new Range(start, end));
    }
}
