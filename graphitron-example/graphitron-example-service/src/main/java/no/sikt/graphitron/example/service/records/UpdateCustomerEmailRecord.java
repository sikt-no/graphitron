package no.sikt.graphitron.example.service.records;

public class UpdateCustomerEmailRecord {
  private String id;
  private String email;

  public UpdateCustomerEmailRecord() {
  }

  public UpdateCustomerEmailRecord(String id, String email) {
    this.id = id;
    this.email = email;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

}
