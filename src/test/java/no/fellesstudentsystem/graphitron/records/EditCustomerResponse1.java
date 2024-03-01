package no.fellesstudentsystem.graphitron.records;

import java.util.List;

public class EditCustomerResponse1 {
    public String getId() {
        return "";
    }

    public String getFirstName() {
        return "";
    }

    public String getSecretEmail() {
        return "";
    }

    public EditCustomerResponse2 getEditResponse2() {
        return new EditCustomerResponse2();
    }

    public List<EditCustomerResponse3> getEditResponse3() {
        return List.of(new EditCustomerResponse3());
    }
}
