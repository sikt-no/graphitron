package no.sikt.graphitron.model.capture.graphitron;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.AstPrinter;
import graphql.language.BooleanValue;
import graphql.language.Directive;
import graphql.language.EnumValue;
import graphql.language.IntValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Value;
import no.sikt.graphitron.model.derive.Nodes;
import no.sikt.graphitron.model.derive.SpelledTables;
import no.sikt.graphitron.model.derive.NodeKeyColumns;
import no.sikt.graphitron.model.derive.TableTypes;
import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
import no.sikt.graphitron.model.schema.SiteRef;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import no.sikt.graphitron.model.sink.FactSink;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPELLED_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ELEMENT_TYPE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The decode of the {@code graphitron_} family: {@code SdlFactCapture} drives it one application at
 * a time while it walks, filling every relation whose rows are a function of one document.
 *
 * <p>The stages that resolve used to sit here too, and are the graphitron anchor's last step now.
 * The two were one file because they share the vocabulary, not because they share a writer or a
 * moment: a decode is a function of one document and runs where the document is, where a resolution
 * needs the store whole and cannot run inside a walk at all.
 *
 * <p>A row here is still a transcription, not a conclusion. It restates what an application spelled,
 * in graphitron's vocabulary rather than the document's, so a reader gets typed columns instead of
 * rendered SDL literals and nothing more. What the generator will actually do with those readings is
 * a layer above this one, and the schema keeps the {@code intent_} prefix free for it.
 *
 * <p>What decodes here and what does not follows one rule: a decode happens at capture exactly
 * when it needs parse-boundary knowledge SQL cannot express (the graphql-java AST, federation's
 * field-set grammar, the shared {@code argMapping} pair grammar). Everything computable as a
 * query over captured columns is derivation and stays out, which is why type wrapping, structured
 * directive arguments, and field sets are columns and child relations here while name resolution
 * and effective-value defaulting are not.
 *
 * <p>The decode never rejects. A literal that does not fit its declared shape leaves its typed
 * column NULL; the authored text is {@code graphql_directive_application_arg}'s.
 *
 * <p>Only what the author wrote is stored: an omitted argument is a NULL column or an absent row,
 * never a default-filled one. Effective values are derivation views, and for the graphitron
 * namespace the defaults are generator constants rather than captured facts.
 *
 * @deprecated the decode as a visitor of a registry; it goes when the decode reads the
 *     entry stratum instead.
 */
@Deprecated
// Acknowledges FactSink's deprecation. This is the decode, which is what the sink is for
// and what it goes with; the suppression is here so the list of them is the list of what
// still writes through it.
@SuppressWarnings("deprecation")
public final class GraphitronFactCapture {

    /** Federation's two decoded applications; every other federation directive is fidelity only. */

    private final FactSink sink;

    GraphitronFactCapture(FactSink sink) {
        this.sink = sink;
    }

    /**
     * The decode, for the walk that holds the parse to drive one application at a time.
     *
     * <p>What lets it run there is a property of the five methods below rather than a concession to
     * their caller: each reads the directive it is handed, buffers rows and queries nothing at all.
     * That is what "the rows are a function of one document" amounts to in code, and it is why the
     * relations they write can be held to catalog-independence while the anchor's resolving stages
     * cannot.
     */
    public static GraphitronFactCapture decodingInto(FactSink sink) {
        return new GraphitronFactCapture(sink);
    }


    // ---------------------------------------------------------------- type-level

    /**
     * Type-coordinate relations carry the applying declaration site, because an extension applies
     * {@code @table} or {@code @key} as readily as a base definition does. A repeated application
     * of a single-application directive keeps the first row; the repeat is a detection over the
     * ordinal, never a collision.
     */
    public void captureTypeDirective(SiteRef site, Directive directive, int ordinal) {
        String type = site.typeName();
        switch (directive.getName()) {
            case "table" -> {
                // The spelling, which graphitron_spelled_reference_entry keys by the value across
                // the several sites that write one, so it moves when the last of them does. The claim
                // stays with it, first-wins per type being what decides whose spelling the corpus
                // records when a type carries more than one application.
                if (!sink.claim(GRAPHITRON_SPELLED_REFERENCE_ENTRY, "TABLE", type)) return;
                // The site records what the author wrote, which on a bare @table is nothing; the
                // spelling it resolves against is the type's own name, and that is the fact the
                // supertype holds, so this site's spelling is not the value it stores.
                String written = string(directive, "name");
                spelling(written == null ? type : written);
            }
            default -> { /* no decoded relation: fidelity-only or consumer-less */ }
        }
    }

    // ---------------------------------------------------------------- field-level

    /**
     * @param inputField whether the coordinate is a field of an INPUT_OBJECT rather than of an
     *     object or interface. Carried in because a condition on an input field is a different
     *     argMapping site from one on an object field, and the type's kind is what separates them;
     *     the walk knows which of the two it is descending and nothing at this depth does
     */
    public void captureFieldDirective(String type, String field, Directive directive, int ordinal,
                               boolean inputField) {
        switch (directive.getName()) {
            case "reference" -> {
                if (!sink.claim(GRAPHITRON_FIELD_REFERENCE_ENTRY, type, field, ordinal)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_FIELD_REFERENCE_ENTRY);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setOrdinal(ordinal);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                sink.add(record);
                int position = 0;
                for (Value<?> element : list(directive, "path")) {
                    var step = referenceElement(element, directive);
                    if (step == null) continue;
                    spelling(step.table());
                    position++;
                }
            }
            case "referenceFor" -> {
                // The application is graphitron_ast_reference_for_entry's; the claim reads each one
                // once for its steps' spellings.
                if (!sink.claim(GRAPHITRON_SPELLED_REFERENCE_ENTRY, "REFERENCE_FOR_STEP", type, field,
                        ordinal)) return;
                if (string(directive, "type") == null) return;
                int position = 0;
                for (Value<?> element : list(directive, "path")) {
                    var step = referenceElement(element, directive);
                    if (step == null) continue;
                    spelling(step.table());
                    position++;
                }
            }
            case "mutation" -> {
                // What is left is the spelling, for the reason @table's arm carries:
                // graphitron_spelled_reference_entry is keyed by the value across the several sites
                // that write one, so it moves when the last of them does. The claim stays with it,
                // first-wins per coordinate being what decides whose spelling the corpus records
                // where a field carries more than one application.
                if (!sink.claim(GRAPHITRON_SPELLED_REFERENCE_ENTRY, "MUTATION", type, field)) return;
                if (tokenOf(argument(directive, "typeName")) == null) return;
                spelling(string(directive, "table"));
            }
            case "routine" -> {
                // The relation is derived now, by GraphitronAnchor over the entry stratum: the
                // application at its coordinate. The claim stays, reading each application once,
                // and the spelling stays for @table's reason, its relation being keyed by the value
                // across the sites that write one.
                if (!sink.claim(GRAPHITRON_ROUTINE_ENTRY, type, field, ordinal)) return;
                String name = string(directive, "name");
                if (name == null) return;
                spelling(name);
            }
            default -> { /* no decoded relation */ }
        }
    }

    // ---------------------------------------------------------------- argument-level

    public void captureArgumentDirective(String type, String field, String argument,
                                  Directive directive, int ordinal) {
        switch (directive.getName()) {
            case "reference" -> {
                // The application is graphitron_ast_reference_entry's; the claim reads each one
                // once for its steps' spellings.
                if (!sink.claim(GRAPHITRON_SPELLED_REFERENCE_ENTRY, "ARGUMENT_REFERENCE_STEP", type, field,
                        argument, ordinal)) return;
                int position = 0;
                for (Value<?> element : list(directive, "path")) {
                    var step = referenceElement(element, directive);
                    if (step == null) continue;
                    spelling(step.table());
                    position++;
                }
            }
            case "referenceFor" -> {
                // On the field site's terms.
                if (!sink.claim(GRAPHITRON_SPELLED_REFERENCE_ENTRY, "ARGUMENT_REFERENCE_FOR_STEP", type,
                        field, argument, ordinal)) return;
                if (string(directive, "type") == null) return;
                int position = 0;
                for (Value<?> element : list(directive, "path")) {
                    var step = referenceElement(element, directive);
                    if (step == null) continue;
                    spelling(step.table());
                    position++;
                }
            }
            default -> { /* no decoded relation */ }
        }
    }

    // ---------------------------------------------------------------- shared decoding

    /** The flattened {@code ReferenceElement}, its step condition inlined. */
    private record Step(String table, String key, String className, String method, String argMapping) {}

    private Step referenceElement(Value<?> value, Directive directive) {
        if (!(value instanceof ObjectValue object)) {
            return null;
        }
        Value<?> condition = field(object, "condition");
        String className = null;
        String method = null;
        String argMapping = null;
        if (condition instanceof ObjectValue conditionObject) {
            className = stringOf(field(conditionObject, "className"), directive, "path");
            method = stringOf(field(conditionObject, "method"), directive, "path");
            argMapping = stringOf(field(conditionObject, "argMapping"), directive, "path");
        }
        return new Step(
            stringOf(field(object, "table"), directive, "path"),
            stringOf(field(object, "key"), directive, "path"),
            className, method, argMapping);
    }

    private static Value<?> argument(Directive directive, String name) {
        Argument argument = directive.getArgument(name);
        return argument == null ? null : argument.getValue();
    }

    private static Value<?> field(ObjectValue object, String name) {
        for (ObjectField field : object.getObjectFields()) {
            if (field.getName().equals(name)) {
                return field.getValue();
            }
        }
        return null;
    }

    private String string(Directive directive, String name) {
        return stringOf(argument(directive, name), directive, name);
    }

    private String stringOf(Value<?> value, Directive directive, String argumentName) {
        if (value == null) {
            return null;
        }
        if (value instanceof StringValue string) {
            return string.getValue();
        }
        return null;
    }

    private Integer integer(Directive directive, String name) {
        Value<?> value = argument(directive, name);
        if (value == null) {
            return null;
        }
        if (value instanceof IntValue number) {
            return number.getValue().intValue();
        }
        return null;
    }

    private List<Value<?>> list(Directive directive, String name) {
        Value<?> value = argument(directive, name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof ArrayValue array) {
            var values = new java.util.ArrayList<Value<?>>(array.getValues().size());
            for (Value<?> element : array.getValues()) {
                values.add(element);
            }
            return List.copyOf(values);
        }
        return List.of();
    }

    /**
     * An author-spelled enum literal: stored as the token the author typed, never checked against
     * a vocabulary. Nothing upstream has validated it under registry capture, so a CHECK would
     * turn a typo into a constraint violation; membership is a detection.
     */
    private static String tokenOf(Value<?> value) {
        return switch (value) {
            case null -> null;
            case EnumValue token -> token.getName();
            case StringValue string -> string.getValue();
            default -> AstPrinter.printAstCompact(value);
        };
    }

    private static void position(Directive directive, Consumer<String> name,
                                 Consumer<Integer> line, Consumer<Integer> column) {
        no.sikt.graphitron.model.capture.sdl.SdlFactCapture.setPosition(directive.getSourceLocation(), name, line, column);
    }

    /**
     * One spelling into {@link no.sikt.graphitron.model.Tables#GRAPHITRON_SPELLED_REFERENCE_ENTRY},
     * deduplicated through {@link FactSink#claim} because the same spelling authored at five
     * coordinates is one row: the relation is keyed by the spelling and the primary key is what says
     * a second write of it is the same fact and not a second one.
     *
     * <p>Takes the spelling rather than the site's stored value, which are the same string at every
     * site but one. A bare {@code @table} stores nothing, the author having written no name, and
     * resolves against the type's own name; that fallback is a spelling the catalog is matched
     * against, so it belongs here even though no column holds it.
     */
    private void spelling(String written) {
        if (written == null || !sink.claim(GRAPHITRON_SPELLED_REFERENCE_ENTRY, written)) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_SPELLED_REFERENCE_ENTRY);
        row.setSpelling(written);
        row.setNamespacePart(QualifiedNameGrammar.namespacePart(written));
        row.setNamePart(QualifiedNameGrammar.namePart(written));
        sink.add(row);
    }

    /**
     * Empties this graph's decoded rows before the decode refills them.
     *
     * <p>Cleared rather than swept: these relations carry no instant, so there is nothing to tell
     * this reading's rows from the last one's. The wholesale clear this replaces lived in the
     * pass that used to run before the decode and emptied every graph-keyed relation it did not
     * know about; owning the clear here is what lets that one go, and what makes the set emptied
     * exactly the set refilled.
     *
     * <p>A sweep is the better answer and wants an instant on all of them, which is a change to
     * the family rather than to this method.
     *
     * <p>Children before parents, which the keys inside the family demand. The order is read off
     * jOOQ's own metadata rather than hand-kept, so a relation that gains a key does not need this
     * list re-sorted.
     */
    public static void clear(DSLContext dsl, String graphName) {
        for (Table<?> table : childrenFirst(DECODED)) {
            dsl.deleteFrom(table)
                .where(table.field("GRAPH_NAME", String.class).eq(graphName))
                .execute();
        }
    }

    /**
     * What this class writes, and therefore what it empties.
     *
     * <p>Writes, not mentions. A relation this class only {@link FactSink#claim}s a coordinate in
     * is written by somebody else: {@link no.sikt.graphitron.model.capture.document.GraphitronAnchor}
     * owns the one claimed here, sweeps it by instant, and runs ahead of the decode, so listing it
     * here would empty rows the pass had already written and the relation would read as though the
     * corpus never declared the directive.
     */
    private static final List<Table<?>> DECODED = List.of(
        GRAPHITRON_FIELD_REFERENCE_ENTRY,
        GRAPHITRON_SPELLED_REFERENCE_ENTRY);

    /** {@code DECODED} ordered so every relation follows the ones whose keys point at it. */
    private static List<Table<?>> childrenFirst(List<Table<?>> tables) {
        var names = tables.stream().map(Table::getName).collect(java.util.stream.Collectors.toSet());
        var ordered = new java.util.ArrayList<Table<?>>();
        var placed = new java.util.HashSet<String>();
        while (ordered.size() < tables.size()) {
            boolean progressed = false;
            for (Table<?> table : tables) {
                if (placed.contains(table.getName())) {
                    continue;
                }
                boolean blocked = tables.stream()
                    .filter(other -> !placed.contains(other.getName()))
                    .filter(other -> !other.getName().equals(table.getName()))
                    .anyMatch(other -> other.getReferences().stream()
                        .anyMatch(key -> key.getKey().getTable().getName().equals(table.getName())));
                if (!blocked) {
                    ordered.add(table);
                    placed.add(table.getName());
                    progressed = true;
                }
            }
            if (!progressed) {
                // A cycle among the decoded relations, which the schema does not have; adding the
                // rest in declaration order keeps this total rather than looping.
                tables.stream().filter(t -> !placed.contains(t.getName())).forEach(ordered::add);
                break;
            }
        }
        return ordered;
    }
}
