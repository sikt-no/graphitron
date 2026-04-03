package no.sikt.graphitron.record;

import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.TypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.GraphitronType.InterfaceType;
import no.sikt.graphitron.record.type.GraphitronType.ResultType;
import no.sikt.graphitron.record.type.GraphitronType.RootType;
import no.sikt.graphitron.record.type.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import no.sikt.graphitron.record.type.GraphitronType.UnionType;
import no.sikt.graphitron.record.type.KeyColumnRef;
import no.sikt.graphitron.record.type.KeyColumnRef.ResolvedKeyColumn;
import no.sikt.graphitron.record.type.KeyColumnRef.UnresolvedKeyColumn;
import no.sikt.graphitron.record.type.NodeRef;
import no.sikt.graphitron.record.type.NodeRef.NoNode;
import no.sikt.graphitron.record.type.NodeRef.NodeDirective;
import no.sikt.graphitron.record.type.TableRef;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import no.sikt.graphitron.record.type.TableRef.UnresolvedTable;
import org.jooq.Table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentStringList;
import static no.sikt.graphql.directives.GenerationDirective.DISCRIMINATE;
import static no.sikt.graphql.directives.GenerationDirective.NODE;
import static no.sikt.graphql.directives.GenerationDirective.RECORD;
import static no.sikt.graphql.directives.GenerationDirective.TABLE;
import static no.sikt.graphql.directives.GenerationDirectiveParam.KEY_COLUMNS;
import static no.sikt.graphql.directives.GenerationDirectiveParam.NAME;
import static no.sikt.graphql.directives.GenerationDirectiveParam.ON;
import static no.sikt.graphql.directives.GenerationDirectiveParam.TYPE_ID;

/**
 * Builds the {@link GraphitronType} map from a {@link TypeDefinitionRegistry} by classifying
 * every named type into the sealed {@link GraphitronType} hierarchy.
 *
 * <p>This is the type half of the schema-lifting boundary: the only place that reads
 * type-level directives ({@code @table}, {@code @record}, {@code @node}, {@code @discriminate}).
 * Downstream code works exclusively with the produced {@link GraphitronType} values.
 *
 * <p>{@link FieldsSpecBuilder} delegates to this class to obtain the types map before
 * classifying fields.
 */
public class TypesSpecBuilder {

    private static final Set<String> ROOT_TYPE_NAMES = Set.of("Query", "Mutation", "Subscription");

    private final TypeDefinitionRegistry registry;
    private final JooqCatalog catalog;

    TypesSpecBuilder(TypeDefinitionRegistry registry, JooqCatalog catalog) {
        this.registry = registry;
        this.catalog = catalog;
    }

    /**
     * Classifies all named types in {@code registry} and returns an ordered map of type name →
     * {@link GraphitronType}. Types that produce no classification (scalars, enums, input types)
     * are omitted.
     */
    Map<String, GraphitronType> build() {
        var types = new LinkedHashMap<String, GraphitronType>();
        registry.types().values().forEach(typeDef -> {
            var gType = classifyType(typeDef);
            if (gType != null) {
                types.put(typeDef.getName(), gType);
            }
        });
        return types;
    }

    private GraphitronType classifyType(TypeDefinition<?> typeDef) {
        if (typeDef instanceof ScalarTypeDefinition
                || typeDef instanceof EnumTypeDefinition
                || typeDef instanceof InputObjectTypeDefinition) {
            return null;
        }

        String name = typeDef.getName();
        SourceLocation location = typeDef.getSourceLocation();

        if (typeDef instanceof ObjectTypeDefinition objType) {
            if (ROOT_TYPE_NAMES.contains(name)) {
                return new RootType(name, location);
            }
            if (objType.hasDirective(TABLE.getName())) {
                return buildTableType(objType);
            }
            if (objType.hasDirective(RECORD.getName())) {
                return new ResultType(name, location);
            }
            return null;
        }
        if (typeDef instanceof InterfaceTypeDefinition iface) {
            if (iface.hasDirective(TABLE.getName()) && iface.hasDirective(DISCRIMINATE.getName())) {
                return buildTableInterfaceType(iface);
            }
            return new InterfaceType(name, location);
        }
        if (typeDef instanceof UnionTypeDefinition) {
            return new UnionType(name, location);
        }
        return null;
    }

    private TableType buildTableType(ObjectTypeDefinition objType) {
        String name = objType.getName();
        SourceLocation location = objType.getSourceLocation();
        String tableName = getOptionalDirectiveArgumentString(objType, TABLE, NAME).orElse(name.toLowerCase());
        TableRef tableStep = resolveTable(tableName);
        NodeRef nodeStep = buildNodeRef(objType, tableStep);
        return new TableType(name, location, tableName, tableStep, nodeStep);
    }

    private TableInterfaceType buildTableInterfaceType(InterfaceTypeDefinition iface) {
        String name = iface.getName();
        SourceLocation location = iface.getSourceLocation();
        String tableName = getOptionalDirectiveArgumentString(iface, TABLE, NAME).orElse(name.toLowerCase());
        String discriminatorColumn = getOptionalDirectiveArgumentString(iface, DISCRIMINATE, ON).orElse(null);
        TableRef tableStep = resolveTable(tableName);
        return new TableInterfaceType(name, location, discriminatorColumn, tableName, tableStep);
    }

    private TableRef resolveTable(String sqlName) {
        return catalog.findTable(sqlName)
            .<TableRef>map(e -> new ResolvedTable(e.javaFieldName(), e.table()))
            .orElseGet(UnresolvedTable::new);
    }

    private NodeRef buildNodeRef(ObjectTypeDefinition objType, TableRef tableStep) {
        if (!objType.hasDirective(NODE.getName())) {
            return new NoNode();
        }
        String typeId = getOptionalDirectiveArgumentString(objType, NODE, TYPE_ID).orElse(null);
        List<String> keyColumnNames = getOptionalDirectiveArgumentStringList(objType, NODE, KEY_COLUMNS);
        Table<?> resolvedTable = tableStep instanceof ResolvedTable rt ? rt.table() : null;
        List<KeyColumnRef> keyColumns = keyColumnNames.stream()
            .map(colName -> resolveKeyColumn(colName, resolvedTable))
            .toList();
        return new NodeDirective(typeId, keyColumns);
    }

    private KeyColumnRef resolveKeyColumn(String colName, Table<?> table) {
        if (table == null) {
            return new UnresolvedKeyColumn(colName);
        }
        return catalog.findColumn(table, colName)
            .<KeyColumnRef>map(e -> new ResolvedKeyColumn(colName, e.javaName()))
            .orElseGet(() -> new UnresolvedKeyColumn(colName));
    }
}
