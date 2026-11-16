package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import java.util.Locale;

/**
 * The one spelling of a nested read's table alias: {@code TableAliases.nested(<enclosing>, "<name>")},
 * the generated runtime call that names a table declared inside a correlated subquery one nesting
 * level deeper than the table it is correlated to, and refuses a selection nested too deep. Every
 * site that declares such a table goes through here, so every nested read is named by one scheme.
 */
public final class TableAliasFragments {

    /** The longest readable name {@link #literal} produces, before the runtime's depth marker. */
    public static final int LITERAL_MAX_LENGTH = 40;

    private static final int TABLE_PART_MAX_LENGTH = 20;

    private TableAliasFragments() {}

    /** The generated {@code <outputPackage>.util.TableAliases} runtime scaffold. */
    public static ClassName tableAliasesClass(String outputPackage) {
        return ClassName.get(outputPackage + ".util", "TableAliases");
    }

    /**
     * The {@code TableAliases} scaffold, which the plan places beside {@code SelectionOccurrences}
     * in the generated {@code util} package; for a renderer that holds a launcher row's
     * {@code selectionOccurrences} ref rather than the output package.
     */
    public static ClassName tableAliasesBeside(no.sikt.graphitron.command.UnitRef selectionOccurrences) {
        return ClassName.get(selectionOccurrences.packageName(), "TableAliases");
    }

    /** {@link #nested(ClassName, String, String, String)} on the scaffold in {@code outputPackage}. */
    public static CodeBlock nested(String outputPackage, String enclosing, String tableJavaFieldName,
            String distinguisher) {
        return nested(tableAliasesClass(outputPackage), enclosing, tableJavaFieldName, distinguisher);
    }

    /**
     * {@code TableAliases.nested(<enclosing>, "<table>_<distinguisher>")}: the alias of a table
     * declared inside a subquery correlated to the jOOQ table local {@code enclosing}.
     *
     * @param tableJavaFieldName the declared table's catalog constant, the readable part of the name
     * @param distinguisher      what keeps the tables one subquery declares apart, typically the
     *                           hop's position in its path
     */
    public static CodeBlock nested(ClassName tableAliases, String enclosing, String tableJavaFieldName,
            String distinguisher) {
        return CodeBlock.of("$T.nested($L, $S)", tableAliases, enclosing,
            literal(tableJavaFieldName, distinguisher));
    }

    /**
     * The readable part of a nested alias: the table's name, lowercased and capped, then the
     * site's distinguisher. A distinguisher longer than the remaining budget keeps its tail, where
     * every site puts its hop position. At most {@link #LITERAL_MAX_LENGTH} characters, so the
     * alias stays short with the runtime's depth marker appended.
     */
    public static String literal(String tableJavaFieldName, String distinguisher) {
        String table = tableJavaFieldName.toLowerCase(Locale.ROOT);
        if (table.length() > TABLE_PART_MAX_LENGTH) {
            table = table.substring(0, TABLE_PART_MAX_LENGTH);
        }
        int budget = LITERAL_MAX_LENGTH - table.length() - 1;
        String tail = distinguisher.length() > budget
            ? distinguisher.substring(distinguisher.length() - budget)
            : distinguisher;
        return table + "_" + tail;
    }
}
