package no.sikt.graphitron.configuration;

public class OptionalSelect {
    private boolean onExternalFields = false;
    private boolean onSubqueryReferences = false;

    public OptionalSelect() {
    }

    public boolean onExternalFields() {
        return onExternalFields;
    }

    public boolean onSubqueryReferences() {
        return onSubqueryReferences;
    }

    public void setOnExternalFields(boolean onExternalFields) {
        this.onExternalFields = onExternalFields;
    }

    public void setOnSubqueryReferences(boolean onSubqueryReferences) {
        this.onSubqueryReferences = onSubqueryReferences;
    }
}