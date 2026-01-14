package no.sikt.graphitron.configuration;

public class OptionalSelect {
    private boolean onSubqueryReferences = false;

    public void setOnExternalFields(boolean onExternalFields) {
        this.onExternalFields = onExternalFields;
    }

    public void setOnSubqueryReferences(boolean onSubqueryReferences) {
        this.onSubqueryReferences = onSubqueryReferences;
    }

    private boolean onExternalFields = false;

    public OptionalSelect() {
    }

    public boolean onSubqueryReferences() {
        return onSubqueryReferences;
    }

    public boolean onExternalFields() {
        return onExternalFields;
    }
}