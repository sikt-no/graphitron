package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.command.GlobalUnitKind;
import no.sikt.graphitron.command.UnitRef;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.emit.ConnectionsEmitter;
import no.sikt.graphitron.emit.ConnectionsQuery;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.generators.util.GraphitronContextInterfaceGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronTransactionProviderGenerator;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_OUTPUT;

/**
 * The runtime classes every generating run writes the same way: the request context interface, the
 * transaction provider, the constraint-violation helper, the client exception, the zero-rows-matched
 * exception, the error router and the outcome carrier. What they say depends on nothing but the package they are written under, so
 * there is nothing to plan: this reads the package off {@code store_graph_output} and renders. A graph
 * that vouches adds the voucher runtime ({@link no.sikt.graphitron.emit.VoucherRuntime}), whose gate is the store's.
 *
 * <p>Beside them it writes the one runtime class rendered from the store's facts rather than from
 * the package alone, {@code Connections}, the connection and edge classes every connection
 * producer builds, read by {@link ConnectionsQuery}; it is written exactly when the graph has a
 * connection type, and needs no plan either.
 *
 * <p>The units it returns are the record of what was written, which the incremental compile graph
 * reads in place of a planned entry per class.
 *
 * <p>It lives beside its renderers rather than in {@code no.sikt.graphitron.emit}, whose emitters
 * name nothing of the planner or the old generators; it moves there once the renderers do.
 */
public final class FixedRuntimeEmitter {

    private FixedRuntimeEmitter() {}

    /** One rendered class and the address it lands at. */
    public record Unit(GlobalUnitKind kind, UnitRef ref, TypeSpec spec) {}

    /**
     * The fixed runtime classes of the run the store captured, with the voucher runtime where
     * {@code vouches}, the run's one reading of {@link no.sikt.graphitron.emit.VoucherRuntime#vouches}.
     */
    public static List<Unit> render(StoreHandle store, boolean vouches) {
        String outputPackage = store.dsl()
            .select(STORE_GRAPH_OUTPUT.OUTPUT_PACKAGE)
            .from(STORE_GRAPH_OUTPUT)
            .where(STORE_GRAPH_OUTPUT.GRAPH_NAME.eq(store.graphName()))
            .fetchOptional(STORE_GRAPH_OUTPUT.OUTPUT_PACKAGE)
            .orElseThrow(() -> new IllegalStateException("graph '" + store.graphName()
                + "' has no output coordinates in the store; only a generating run writes classes"));
        var out = new ArrayList<>(render(outputPackage));
        if (vouches) {
            out.addAll(renderVoucherRuntime(outputPackage));
        }
        var shapes = ConnectionsQuery.read(store);
        if (!shapes.connections().isEmpty()) {
            var units = new GeneratedUnits(outputPackage);
            add(out, GlobalUnitKind.CONNECTIONS, units.connections(),
                ConnectionsEmitter.emit(shapes, outputPackage, new ConnectionsEmitter.Runtime(
                    className(units.pageInfo()), className(units.facets()), className(units.errorRouter()))));
        }
        return List.copyOf(out);
    }

    private static ClassName className(UnitRef ref) {
        return ClassName.get(ref.packageName(), ref.simpleName());
    }

    /** The voucher runtime written under {@code outputPackage}, in a graph that vouches. */
    public static List<Unit> renderVoucherRuntime(String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var specs = no.sikt.graphitron.emit.VoucherRuntime.render(outputPackage);
        var out = new ArrayList<Unit>();
        add(out, GlobalUnitKind.VOUCHER_CODEC,
            units.singleton(GeneratedUnits.SUB_SCHEMA, no.sikt.graphitron.emit.VoucherRuntime.CODEC), specs.subList(0, 1));
        add(out, GlobalUnitKind.VOUCHER_KEYS,
            units.singleton(GeneratedUnits.SUB_SCHEMA, no.sikt.graphitron.emit.VoucherRuntime.KEYS), specs.subList(1, 2));
        add(out, GlobalUnitKind.VOUCHER_REJECTED_EXCEPTION,
            units.singleton(GeneratedUnits.SUB_SCHEMA, no.sikt.graphitron.emit.VoucherRuntime.REJECTED), specs.subList(2, 3));
        return List.copyOf(out);
    }

    /** The fixed runtime classes written under {@code outputPackage}. */
    public static List<Unit> render(String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var out = new ArrayList<Unit>();
        add(out, GlobalUnitKind.GRAPHITRON_CONTEXT,
            units.singleton(GeneratedUnits.SUB_SCHEMA, GraphitronContextInterfaceGenerator.CLASS_NAME),
            GraphitronContextInterfaceGenerator.generate());
        add(out, GlobalUnitKind.TRANSACTION_PROVIDER,
            units.singleton(GeneratedUnits.SUB_SCHEMA, GraphitronTransactionProviderGenerator.CLASS_NAME),
            GraphitronTransactionProviderGenerator.generate(outputPackage));
        add(out, GlobalUnitKind.CONSTRAINT_VIOLATIONS,
            units.singleton(GeneratedUnits.SUB_SCHEMA, ConstraintViolationsClassGenerator.CLASS_NAME),
            ConstraintViolationsClassGenerator.generate(outputPackage));
        add(out, GlobalUnitKind.CLIENT_EXCEPTION, units.clientException(),
            GraphitronClientExceptionClassGenerator.generate());
        add(out, GlobalUnitKind.ZERO_ROWS_MATCHED_EXCEPTION, units.zeroRowsMatchedException(),
            ZeroRowsMatchedExceptionClassGenerator.generate());
        add(out, GlobalUnitKind.ERROR_ROUTER, units.errorRouter(),
            ErrorRouterClassGenerator.generate(outputPackage));
        add(out, GlobalUnitKind.OUTCOME, units.outcome(),
            OutcomeClassGenerator.generate(outputPackage));
        return List.copyOf(out);
    }

    private static void add(List<Unit> out, GlobalUnitKind kind, UnitRef ref, List<TypeSpec> specs) {
        if (specs.size() != 1 || !specs.get(0).name().equals(ref.simpleName())) {
            throw new IllegalStateException(kind + " rendered " + specs.stream().map(TypeSpec::name).toList()
                + " where one class named '" + ref.simpleName() + "' was expected");
        }
        out.add(new Unit(kind, ref, specs.get(0)));
    }
}
