package no.sikt.jooq;


import org.jooq.codegen.GeneratorStrategy;
import org.jooq.codegen.JavaGenerator;
import org.jooq.codegen.JavaWriter;
import org.jooq.meta.ColumnDefinition;
import org.jooq.meta.TableDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

//TODO Denne klassen skal endres (kanskje fjernes) i forbindelse med https://unit.atlassian.net/browse/FSP-266
public class IdBasedGenerator extends JavaGenerator {

    private static final Pattern toUpperPattern = Pattern.compile("_\\w|^\\w");

    @Override
    public void generateTableClassFooter(TableDefinition table, JavaWriter out) {
        super.generateTableClassFooter(table, out);

        if (table.getPrimaryKey() == null) {
            return;
        }

        List<ColumnDefinition> keyColumns = table.getPrimaryKey()
                .getKeyColumns();
        var primaryKeyFields = resolveFieldNames(keyColumns);

        generateGetter(out, primaryKeyFields, "Id", "Id");
        generateFieldsGetter(out, table, primaryKeyFields);

        var qualifiers = new ArrayList<Map.Entry<String, String>>();

        for (var fk : table.getForeignKeys()) {
            var name = fk.getKeyColumns().get(0).getName();
            var qualifier = toUpperPattern.matcher(name).replaceAll(snakeToCamel());
            qualifiers.add(Map.entry(fk.getName(), qualifier));

            var sourceFieldNames = resolveFieldNames(fk.getKeyColumns());
            generateGetter(out, sourceFieldNames, qualifier, name);
            generateHasCondition(out, sourceFieldNames, qualifier);
        }
        generateQualifierMap(out, qualifiers);
    }

    @Override
    protected void generateRecordClassFooter(TableDefinition table, JavaWriter out) {

        if (table.getPrimaryKey() == null) {
            return;
        }
        super.generateRecordClassFooter(table, out);
        generateRecordClassIdGetter(out, table.getPrimaryKey().getKeyColumns().size());
        generateRecordClassIdSetter(out);
    }

    private void generateQualifierMap(JavaWriter out, List<Map.Entry<String, String>> qualifiers) {
        out.println();
        if (qualifiers.isEmpty()) {
            out.println("private static final java.util.Map<String, String> qualifiers = java.util.Map.of();");
        } else {
            out.println("private static final java.util.Map<String, String> qualifiers = java.util.Map.ofEntries(");
            for (int i = 0; i < qualifiers.size() - 1; i++) {
                var entry = qualifiers.get(i);
                out.println(String.format("java.util.Map.entry(\"%s\", \"%s\"),", entry.getKey(), entry.getValue()));
            }

            var lastEntry = qualifiers.get(qualifiers.size() - 1);
            out.println(String.format("java.util.Map.entry(\"%s\", \"%s\")", lastEntry.getKey(), lastEntry.getValue()));
            out.println(");");
        }

        out.println();
        out.println("public String getQualifier(String keyName) { return qualifiers.get(keyName); }");
    }

    private List<String> resolveFieldNames(List<ColumnDefinition> keyColumns) {
        return keyColumns.stream()
                .map(column -> getStrategy().getJavaIdentifier(column))
                .collect(Collectors.toList());
    }

    private void generateGetter(JavaWriter out, List<String> primaryKeyFieldNames, String qualifier, String unqualifiedName) {
        var args = IntStream.range(0, primaryKeyFieldNames.size())
                .mapToObj(i -> "s" + i)
                .collect(Collectors.joining(", "));

        var toStrs = IntStream.range(0, primaryKeyFieldNames.size())
                .mapToObj(i -> "s" + i + ".toString()")
                .collect(Collectors.joining("+ \",\" +"));

        out.println();
        out.println("public org.jooq.SelectField<String> get%s() {", qualifier);
        out.println("return DSL.row(%s)", String.join(", ", primaryKeyFieldNames));
        out.tab(1).println(".mapping(String.class, org.jooq.Functions.nullOnAnyNull((%s) -> %s))",
                args, toStrs);
        out.tab(1).println(".as(DSL.name(\"%s\"));", unqualifiedName);
        out.println("}");
    }

    private void generateFieldsGetter(JavaWriter out, TableDefinition table, List<String> fields) {
        var recordType = getStrategy().getFullJavaClassName(table, GeneratorStrategy.Mode.RECORD);

        out.println();
        out.println("public java.util.List<TableField<%s, ?>> getIdFields() {", recordType);
        out.println("return java.util.List.of(%s);", String.join(", ", fields));
        out.println("}");
    }

    private void generateHasCondition(JavaWriter out, List<String> fieldNames, String qualifier) {
        out.println();
        out.println("public org.jooq.Condition has%s(String id) {", qualifier);
        out.println("return has%ss(java.util.Set.of(id));", qualifier);
        out.println("}");
        out.println();
        out.println("public org.jooq.Condition has%ss(java.util.Set<String> ids) {", qualifier);
        out.println("var field = java.util.List.of(%s).get(0);", String.join(", ", fieldNames));
        out.println("var converted = ids.stream().map(it -> field.getDataType().convert(it)).collect(java.util.stream.Collectors.toList());");

        out.println("return field.in(converted);");
        out.println("}");
    }

    private Function<MatchResult, String> snakeToCamel() {
        return match -> match.group().toUpperCase().replaceAll("_", "");
    }

    private void generateRecordClassIdGetter(JavaWriter out, int noOfPrimaryKeyFields) {
        out.println();
        out.println("public String getId() {");
        out.print("return \"\" + ");

        for (int i = 0; i < noOfPrimaryKeyFields; i++) {
            if (i > 0) out.print(" + \",\" + ");
            out.print("(Long) get(%s)", i);
        }
        out.println(";");
        out.println("}");
    }

    private void generateRecordClassIdSetter(JavaWriter out) {
        out.println();
        out.println("public void setId(String id) {");
        out.println("String[] split = id.split(\",\");");
        out.println("for (int i = 0; i < split.length; i++) {");
        out.println("set(i, split[i]);");
        out.println("}");
        out.println("}");
    }
}
