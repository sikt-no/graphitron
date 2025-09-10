package no.sikt.graphitron.example.service.records;

public class UpdateCustomerEmailRecord {
  private Integer customerId;
  private String email;

  public UpdateCustomerEmailRecord() {
  }

  public UpdateCustomerEmailRecord(Integer customerId, String email) {
    this.customerId = customerId;
    this.email = email;
  }

  public Integer getCustomerId() {
    return customerId;
  }

  public void setCustomerId(Integer customerId) {
    this.customerId = customerId;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

}
