package no.sikt.graphitron.example.temp.generated;

import com.vaadin.flow.component.grid.Grid;
import no.sikt.frontgen.generate.GeneratedQueryComponent;
import no.sikt.graphitron.example.generated.graphitron.model.Payment;
import no.sikt.graphitron.example.generated.graphitron.model.QueryPaymentsConnection;
import no.sikt.graphitron.example.generated.graphitron.model.QueryPaymentsConnectionEdge;

import java.util.List;
import java.util.function.Function;

public class PaymentQueryComponent extends GeneratedQueryComponent<Payment, QueryPaymentsConnection> {
    @Override
    protected String getQuery() {
        return "query { payments(first: 100) { edges { node {  amount dateTime } } } }";
    }

    @Override
    protected String getRootField() {
        return "payments";
    }

    @Override
    protected Class<QueryPaymentsConnection> getConnectionClass() {
        return QueryPaymentsConnection.class;
    }

    @Override
    protected Function<QueryPaymentsConnection, List<?>> getEdgesFunction() {
        return QueryPaymentsConnection::getEdges;
    }

    @Override
    protected Function<Object, Payment> getNodeFunction() {
        return edge -> ((QueryPaymentsConnectionEdge) edge).getNode();
    }

    @Override
    protected Function<List<Payment>, Grid<Payment>> getGridCreator() {
        return payments -> {
            Grid<Payment> grid = new Grid<>(Payment.class, false);
            grid.addColumn(Payment::getAmount)
                        .setHeader("Amount")
                        .setFlexGrow(1);
            grid.addColumn(Payment::getDateTime)
                        .setHeader("Date Time")
                        .setFlexGrow(1);
            grid.setItems(payments);
            return grid;
        };
    }

    @Override
    protected String getButtonText() {
        return "List Payments";
    }
}
