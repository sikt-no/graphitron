package no.sikt.graphitron.example.frontgen.components;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;

public abstract class QueryComponent extends VerticalLayout {

    protected final QueryBackedView queryView;

    public QueryComponent(QueryBackedView queryView) {
        this.queryView = queryView;
    }

    public abstract void load();

    public abstract String getButtonText();

    public abstract Runnable getLoadAction();
}