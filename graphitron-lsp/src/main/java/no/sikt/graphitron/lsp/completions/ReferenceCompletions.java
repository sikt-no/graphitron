package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;

/**
 * Foreign-key completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.CatalogFkBinding}. Today's
 * canonical overlay binds {@code ReferenceElement.key}, the {@code key}
 * field inside {@code @reference(path: [{key:}])}.
 *
 * <p>Candidates are the foreign keys touching the enclosing GraphQL type's table, in either
 * direction: the keys the table declares and the keys other tables declare against it. Which table
 * that is stays a classification question, answered by the snapshot; the key census is the store
 * read. Path-step refinement (narrowing later steps by where the previous step landed) is not
 * implemented; every step suggests the same set.
 *
 * <p>The label is the SQL constraint name. {@code key:} resolves two namespaces, the SQL name first
 * and the generated {@code Keys} constant second, and the SQL name is the one the manual teaches,
 * the one a rejection's candidate hint echoes, and the one every constraint has: the constant is
 * absent for a key no {@code Keys} class names. The constant goes in the item's documentation, since
 * it resolves too and an author reading generated code will recognise it.
 */
public final class ReferenceCompletions {

    private ReferenceCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
        LspSchemaSnapshot snapshot,
        CompletionContext context,
        Directives.Directive directive,
        byte[] source
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogFkBinding)) {
            return List.of();
        }
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return List.of();
        var tableName = TypeContext.tableNameOf(typeDecl.get(), source, snapshot);
        if (tableName.isEmpty()) return List.of();
        return keyItems(store, tableName.get(), context);
    }

    /** One foreign key as the arm renders it: what an author can spell, and what it joins. */
    private record Candidate(String schema, String name, String detail, String jooqName) {}

    /**
     * The foreign keys touching {@code tableName}, matched case-insensitively as the column arm
     * matches: the name comes from a directive an author typed rather than from the database.
     *
     * <p>One query for both directions rather than two, because a self-referencing key is one row
     * that satisfies both halves of the predicate and a union would offer it twice. Ordered by
     * declaring schema then constraint name, which is stateable where the projection's order was the
     * generated {@code Tables} class's field order.
     */
    private static List<CompletionItem> keyItems(
        StoreHandle store, String tableName, CompletionContext context
    ) {
        var rows = store.dsl()
            .select(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA, SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME,
                SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME,
                SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE, SQL_CONSTRAINT.JOOQ_NAME)
            .from(SQL_REFERENTIAL_CONSTRAINT)
            .join(SQL_CONSTRAINT).on(SQL_CONSTRAINT.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME)
                .and(SQL_CONSTRAINT.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA))
                .and(SQL_CONSTRAINT.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME))
                .and(SQL_CONSTRAINT.CONSTRAINT_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME)))
            .where(store.reads(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME))
            .and(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME.equalIgnoreCase(tableName)
                .or(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE.equalIgnoreCase(tableName)))
            .orderBy(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA, SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME,
                SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME)
            .fetch();
        var candidates = new ArrayList<Candidate>(rows.size());
        for (var row : rows) {
            boolean outbound = row.value2().equalsIgnoreCase(tableName);
            String other = outbound ? row.value4() : row.value2();
            candidates.add(new Candidate(row.value1(), row.value3(),
                (outbound ? "→ " : "← ") + other, row.value5()));
        }
        return items(candidates, context);
    }

    /**
     * One item per spelling, because a spelling is what an author can write. Two keys of one name in
     * different schemas are told apart by the {@code schema.} qualifier the {@code key:} grammar
     * accepts, which the resolver treats as stated intent; qualifying only the colliding names keeps
     * the ordinary candidate the bare name the manual's examples use. Two keys that collide even
     * under a qualifier (one schema, one name, two tables, which PostgreSQL permits) are one
     * candidate naming both joins, since no spelling separates them and the resolver's own
     * table-scoping is what picks.
     */
    private static List<CompletionItem> items(List<Candidate> candidates, CompletionContext context) {
        Map<String, Set<String>> schemasByName = new HashMap<>();
        for (Candidate candidate : candidates) {
            schemasByName
                .computeIfAbsent(candidate.name().toLowerCase(Locale.ROOT), k -> new LinkedHashSet<>())
                .add(candidate.schema());
        }
        Map<String, List<Candidate>> byLabel = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            boolean collides = schemasByName.get(candidate.name().toLowerCase(Locale.ROOT)).size() > 1;
            String label = collides ? candidate.schema() + "." + candidate.name() : candidate.name();
            byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(candidate);
        }
        var items = new ArrayList<CompletionItem>(byLabel.size());
        byLabel.forEach((label, group) -> items.add(toItem(label, group, context)));
        return items;
    }

    private static CompletionItem toItem(
        String label, List<Candidate> group, CompletionContext context
    ) {
        var item = CompletionItems.replacing(label, CompletionItemKind.Reference,
            context.replaceRange(), join(group.stream().map(Candidate::detail).toList()));
        var constants = group.stream().map(Candidate::jooqName)
            .filter(name -> name != null && !name.isEmpty()).distinct().toList();
        if (!constants.isEmpty()) {
            item.setDocumentation(Either.forRight(new MarkupContent(MarkupKind.PLAINTEXT,
                "Also resolves under the generated constant " + join(constants) + ".")));
        }
        return item;
    }

    private static String join(List<String> values) {
        return String.join(", ", values.stream().distinct().toList());
    }
}
