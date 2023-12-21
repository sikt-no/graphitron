# Graphitron
## A GraphQL resolver implementation generator
Graphitron creates complete or partial resolver implementations from GraphQL-schemas using Java and jOOQ.
This is done with the provided set of directives for making the necessary connections between types and
fields in the schema and their equivalents in the DB.

## Special prerequisites
Graphitron builds on other software for querying databases and doing the basic conversion of schema types to Java types.
* jOOQ - Graphitron does not generate pure Java/SQL, and instead creates jOOQ implementations.
* GraphQL-codegen - Graphitron assumes this plugin has been run on the schema beforehand.

## Maven Settings
### Goals
The _generate_ Maven goal allows Graphitron to be called as part of the build pipeline. It generates all the classes
that are set to generate in the schema. Additionally, the _watch_ goal can be used locally to watch GraphQL files
for changes, and regenerate code without having to re-run generation manually each time. This feature is still experimental.

### Configuration
In order to find the schema files and any custom methods, Graphitron also provides some configuration options.
The options are the same for both goals.

#### General settings
* _outputPath_ - The location where the code will be generated.
* _outputPackage_ - The package path of the generated code.
* _schemaFiles_ - Set of schema files which should be used for the generation process.
* _generatedSchemaCodePackage_ - The location of the graphql-codegen generated classes.
* _jooqGeneratedPackage_ - The location of the jOOQ generated code.
* _recordValidation_ - Controls whether generated mutations should include validation of JOOQ records through the Jakarta Bean Validation specification.
  * _enabled_ - Flag indicating if Graphitron should generate record validation code
  * _schemaErrorType_ - Name of the schema error to be returned in case of validation violations.
    If null while _enabled_ is true all validation violations will instead cause
    'AbortExecutionExceptions' to be thrown, leading to top-level GraphQL errors.
    Also, if the given error is not present in the schema as a returnable error for a specific mutation,
    validation violations on this mutation will cause top-level GraphQL errors.

#### Code references
* _externalReferences_ - List of references to classes that can be applied through certain directives.
* _globalRecordTransforms_ - List of transforms that should be applied to all records. The transform _name_ value must
be present in _externalReferences_. The _scope_ value specifies which mutations should be affected, but currently only
_ALL_MUTATIONS_ is available. Since this points to a class, a _method_ must also be specified.
* _extensions_ - Graphitron classes allow for extensions, allowing plugin users to provide their own implementations to control the code generation process. 
Currently, all extensions are instantiated via [ExtendedFunctionality](src/main/java/no/fellesstudentsystem/graphitron/configuration/ExtendedFunctionality.java), limiting extension to specific classes.
We plan to enhance this: all generator classes will be made extensible for wider customization options.

Example of referencing a class through the configuration:
```xml
<externalReferences>
  <reference> <!--The name of this outer element does not matter.-->
    <schemaName>CUSTOMER_TRANSFORM</schemaName>
    <path>some.path.CustomerTransform</path>
  </reference>
</externalReferences>
```

Example of applying a global transform in the POM:
```xml
<globalRecordTransforms>
  <reference>
    <name>CUSTOMER_TRANSFORM</name> <!-- The name of the reference. -->
    <method>someTransformMethod</method> <!-- Method in the referenced class to be applied. -->
    <scope>ALL_MUTATIONS</scope> <!-- Only ALL_MUTATIONS is supported right now. -->
  </reference>
</globalRecordTransforms>
```

Example of extending/customizing certain classes:
```xml
  <!-- The elements should contain fully-qualified class names -->
  <extensions>
    <element>
      <extendedClass>no.fellesstudentsystem.graphitron.validation.ProcessedDefinitionsValidator</extendedClass>
      <extensionClass>no.fellesstudentsystem.graphitron.validation.FSProcessedDefinitionsValidator</extensionClass>
    </element>
    <element>
      <extendedClass>no.fellesstudentsystem.graphitron.definitions.objects.InputDefinition</extendedClass>
      <extensionClass>no.fellesstudentsystem.graphitron.definitions.objects.FSInputDefinition</extensionClass>
    </element>
  </extensions>
```

## Directives
### Common directives
#### splitQuery directive
Applying this to a type reference denotes a split in the generated query/resolver.
In other words, this will require the specification of a resolver for the annotated field.
Should match the splitting directive used by the graphql-codegen-plugin(TODO: link readme) so that the generated
interfaces match the classes produced by Graphitron. Fields in the Query and Mutation types do not require this directive, as
they are always considered start points for resolvers.

In this example, the code generation would create the classes _ATypeDBQueries_ and _ATypeGeneratedResolver_, containing
the resolver code required to fetch _OtherType_ given _AType_. _ATypeGeneratedResolver_ will be an implementation of
_ATypeResolver_(created by graphql-codegen). Note that this example would not result in working code as we have not
specified any tables to map to yet.
```graphql
type AType {
  otherType: OtherType @splitQuery # Build new resolver/query for this field.
}

type OtherType {
  name: String
}
```

#### notGenerated directive
Set this on any query or mutation type reference that should result in a new resolver in order to
cancel generation of it.
Since graphql-codegen still creates interfaces for these, they will have to be implemented manually.
If a class generated by Graphitron has missing implementations, an abstract class will be generated instead which can be
further extended with the missing methods.

```graphql
type Query {
  aQuery: AType @notGenerated # Do not generate this query automatically.
}

type AType {
  otherType: OtherType @splitQuery @notGenerated # Require new resolver for this field, but do not generate it automatically.
}

type OtherType {
  name: String
}
```

#### field directive
By default, Graphitron assumes each field not annotated with the **notGenerated** or **splitQuery** directives to have a name
equal to the column it corresponds to in the jOOQ table. The **field** directive overrides this behaviour. Specifying
the _name_-parameter allows for using schema names that are not connected to the names of jOOQ fields.
This directive applies to normal type fields, input type fields, arguments and enum values.

```graphql
type Query {
  query(
    argument: String @field(name: "ACTUAL_ARGUMENT_NAME") # @field applied on an argument.
  ): SomeType
}

type SomeType {
  value: String @field(name: "ACTUAL_VALUE_NAME") # @field applied on a standard field.
}

input SomeInput {
  value: String @field(name: "ACTUAL_VALUE_NAME") # @field applied on an input field.
}

enum SomeEnum { # @field applied on enum fields. Each of these must correspond to a jOOQ field.
  E0 @field(name: "ACTUAL_E0")
  E1 @field(name: "ACTUAL_E1")
  E2 @field(name: "ACTUAL_E2")
}
```

For determining which table the field should be taken from,
see the [table](#table-directive) and [reference](#reference-directive) directives.

### Tables, joins and records
#### table directive
The **table** directive links the object type or input type to a table in the database. Any **field**-directives within
this type will use this table as the source for the field mapping. This targets jOOQ generated classes, so
the _name_ parameter must match the table name in jOOQ if it differs from the database. The _name_ parameter is optional,
and does not need to be specified if the type name already equals the table name.

In the example below the generator would apply a jOOQ implicit join between the two tables when building the query.
Note that this can only work if there is only one foreign key between the tables. For example, given tables from the
schema example below, the result will be `TABLE_A.table_b()`. If more than one key exists, a more complex configuration
is required.

```graphql
type TABLE_A @table { # Table name matches the type name, name is unnecessary.
  someOtherType: OtherType
}

type OtherType @table(name: "TABLE_B") {
  name: String
}
```

If a table type contains other types that do not set their own tables, the previous table type is used instead.
This also applies to enum types.

#### reference directive
There are, of course, many cases where the connection between two tables is more complex.
In such cases, Graphitron requires some extra parameters to make the right connections.
This is done through the **reference** directive, which contains the following parameters:

* _table_ - This defaults to the table of the type that is referenced by the field the directive is set on.
Setting this overrides whatever table may be set there. This must match a table name from jOOQs _Table_ class.
* _key_ - If there are multiple foreign keys from one type to another, then this parameter is required for defining which
key that should be used. This must match a key name from jOOQs _Keys_ class.
* _condition_ - This parameter is used to place an additional constraint on the two tables, by referring to the correct [entry](#code-references)
in the POM XML. In the cases where there is no way to deduce the key between the tables and the _key_ parameter is not set,
this condition will be assumed to be an _on_ condition to be used in a join operation between the tables.
The result will be a left join if the field is optional, otherwise a standard join.
* _via_ - Invokes extra steps of the logic that all the previous parameters already use,
allowing the usage of joins through tables which are not types in the schema.
This parameter only specifies the extra steps to be taken in addition to the usual functionality og the previous parameters.

Note that joins only apply to the field they are set on. Graphitron either sets separate aliases or uses implicit joins to
manage several simultaneous joins from one table to another. If the field is a scalar type,
it can be linked to a jOOQ column in another table using this directive. If the field points to a type, all fields within this
referred type will have access to the join operation.

The following examples will assume that this configuration is set: 
```xml
<externalReferences>
  <reference>
    <schemaName>CUSTOMER_CONDITION</schemaName>
    <path>some.path.CustomerCondition</path>
  </reference>
</externalReferences>
```

For the first example we will apply this simple condition method on tables that do have a direct connection.
```java
class CustomerCondition {
    static Condition addressJoin(Customer customer, Address address) { … }
}
```

The method returns a jOOQ condition, which will be appended after the where-statement for the query.

_Schema_:
```graphql
type Customer @table {
  addresses: [Address!]! @splitQuery @reference(condition : {name: "CUSTOMER_CONDITION", method: "addressJoin"})
}
```

_Generated result_:
```java
.and(some.path.CustomerCondition.addressJoin(CUSTOMER, ADDRESS))
```

The condition is thus an additional constraint applied on both tables.
In a slightly different case where the tables are not directly connected, the join will behave differently.
If the two tables did not have any foreign keys between them (or there are multiple, which one to use was not specified)
the generated result would follow a pattern like the code below.

```java
.join(ADDRESS)
.on(some.path.CustomerCondition.addressJoin(CUSTOMER, ADDRESS))
```

Providing only a key will yield a similar result. Note that in this example the key and the reference directive itself
are redundant since there is only one key between the tables. Assume the _Address_ type has the **table** directive set.

_Schema_:
```graphql
type Customer @table {
  addresses: [Address!]! @splitQuery @reference(key: "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY")
}
```

_Generated result_:
```java
.join(ADDRESS)
.onKey(CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
```

Providing both a key and a condition will result in a sum of both the first and previous examples,
meaning both a join on a key and one additional condition will be applied.

Using the _via_ parameter will create additional occurrences of what the previous examples have already shown.
Note that this parameter only works by adding additional steps before the other parameters,
meaning they most likely have to be set as well. The same rules apply to the intermediate steps as to other join operations.
If there is only one key between two tables, only the table reference is required.
The example below is a simple illustration of this. Assume the _Film_ type has the **table** directive set.

_Schema_:
```graphql
type Payment @table {
  # The path here is PAYMENT -> RENTAL -> INVENTORY -> FILM
  film: Film! @splitQuery @reference(via: [{table: "RENTAL"}, {table: "INVENTORY"}])
}
```

First, Graphitron defines a few aliases for these joins. Currently, this creates one alias per step.

```java
var payment_rental = PAYMENT.rental().as("…");
var payment_rental_inventory = payment_rental.inventory().as("…");
var payment_rental_inventory_film = payment_rental_inventory.film().as("…");
```

Then, the alias is applied where necessary. This line is taken from the generated query.

```java
select.optional("title", payment_rental_inventory_film.TITLE).as("title")
```

### Query conditions
To either apply additional conditions or override some of the conditions added by default, use the **condition** directive.
It can be applied to both input parameters and resolver fields, and the scope of the condition will match the element it is put on.
It provides the following parameter options:

* _condition_ - Reference name and method name of a reference defined in an [entry](#code-references) in the POM XML.
* _override_ - If true, disables the default checks that are added to all arguments, otherwise add the new condition in
addition to the default ones.

#### Example: Setup
The following examples will assume this configuration exists:

```xml
<externalReferences>
  <reference>
    <schemaName>CITY_CONDITION</schemaName>
    <path>some.path.CityCondition</path>
  </reference>
</externalReferences>
```

#### Example: No _override_ on input parameter
Add this condition in addition to the ones automatically applied for this parameter.
The method must have the table and the input parameter type as parameters.

_Schema_:
```graphql
cityNames: [String!] @field(name: "CITY") @condition(condition: {name: "CITY_CONDITION", method: "cityMethod"})
```

_Resulting code_:
```java
.and(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : noCondition())
.and(some.path.CityCondition.cityMethod(CITY, cityNames))
```

#### Example: No _override_ on field with input parameters
Add this condition in addition to the ones automatically applied for the parameters.
The method must have the table and all input parameter types for this field as parameters.

_Schema_:
```graphql
cities(
    countryId: String! @field(name: "COUNTRY_ID"),
    cityNames: [String!] @field(name: "CITY")
): [City] @condition(condition: {name: "CITY_CONDITION", method: "cityMethod"})
```

_Resulting code_:
```java
.where(CITY.COUNTRY_ID.eq(countryId))
.and(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : noCondition())
.and(some.path.CityCondition.cityMethod(CITY, countryId, cityNames))
```

#### Example: Both field and parameters
Remove none of the automatically generated checks, but add both conditions as shown in the two previous examples.
In other words the two cases do not interfere with each other.

#### Example: With _override_ on input parameter
Replace the automatically generated checks for this parameter with this condition.
The method must have the table and the input parameter type as parameters.

_Schema_:
```graphql
cityNames: [String!] @field(name: "CITY") @condition(condition: {name: "CITY_CONDITION", method: "cityMethod"}, override: true)
```

_Resulting code_:
```java
.and(some.path.CityCondition.cityMethod(CITY, cityNames))
```

#### Example: With _override_ on field with input parameters
Replace all the automatically generated checks for this field with this condition.
The method must have the table and all input parameter types for this field as parameters.

_Schema_: 
```graphql
cities(
    countryId: String! @field(name: "COUNTRY_ID"),
    cityNames: [String!] @field(name: "CITY")
): [City] @condition(condition: {name: "CITY_CONDITION", method: "cityMethodAllElements"}, override: true)
```

_Resulting code_:
```java
.where(some.path.CityCondition.cityMethodAllElements(CITY, countryId, cityNames))
```

#### Example: With _override_ on both field and parameters
Both manually defined conditions are included, but nothing else. Note that if override is set on the field condition
the _override_ value on the parameter becomes irrelevant, since the one on the field already removes all
the default checks.

_Schema_:
```graphql
cities(
    countryId: String! @field(name: "COUNTRY_ID"),
    cityNames: [String!] @field(name: "CITY") @condition(condition: {name: "CITY_CONDITION", method: "cityMethod"}, override: true)
): [City] @condition(condition: {name: "CITY_CONDITION", method: "cityMethodAllElements"}, override: true)
```

_Resulting code_:
```java
.where(some.path.CityCondition.cityMethod(CITY, cityNames))
.and(some.path.CityCondition.cityMethodAllElements(CITY, countryId, cityNames))
```

### Enums
Enums can be mapped in two ways. The **field** directive is already covered [here](#field-directive).
An alternative method is to set up a Java enum instead, for example through a jOOQ converter. These can be referenced
using the **enum** directive, by pointing to the appropriate [entry](#code-references).

```graphql
enum SomeEnum @enum(enumReference: {name: "THE_ENUM_REFERENCE"}) {
  E0
  E1
  E2
}
```

### Mutation generation
While fetching data can cover many cases, mutations have more limitations when generated through Graphitron,
as mutations can take many inputs which should be saved to multiple tables. Automatic generation of the entire resolver
is currently only viable for simple cases where one input type representing one type of jOOQ record is handled, and
optionally returned when the operation is complete. In addition, Graphitron unfortunately currently operates on
assumptions related to ID-fields. The response types are thus limited to returning IDs and Node-types for the time being.

Use the **mutationType** directive and the accompanying _typeName_ parameter to denote a mutation that should be fully
generated. To specify which table should be affected, the **table** directive is used just like for the usual types used
for queries. As usual, **field** may also be applied to adjust the mapping of individual fields.

```graphql
type Mutation {
  editCustomerInputAndResponse(input: EditInput!): EditResponse! @mutation(typeName: UPDATE)
  editCustomerWithCustomerResponse(input: EditInput!): EditResponseWithCustomer! @mutation(typeName: UPDATE)
}

input EditInput @table(name: "CUSTOMER") { # Use @table to specify which jOOQ record/table this corresponds to.
    id: ID!
    firstName: String @field(name : "FIRST_NAME") # Use @field to adjust the mapping to jOOQ fields.
    email: String
}

type EditResponse {
    id: ID! # Note, mutations need to work with types that have 'id' fields.
}

type EditResponseWithCustomer {
    id: ID!
    customer: Customer # This points to a Node type, so that it can be resolved using an ID.
}

type Customer implements Node @table { # Implements Node, is a Node type.
    id: ID!
    firstName: String! @field(name: "FIRST_NAME")
}
```

If all required fields for an insert or upsert operation are not set in the input type, a warning will be generated.
In the future this may change to an exception instead, as an incomplete set of required fields will result in a resolver
that compiles, but will always fail when executed.

Note that mutations need either the **mutationType** or the **service** directive set, but not both, in order to be generated.
Mutations that should not be generated should have the **notGenerated** directive set.

### Mutation services
More complex cases are supported through the **service** directive. It points to a class [entry](#code-references)
in the POM XML. The method is either specified through the directive or assumed to be the same as the field name.
The directive invokes the creation of code that calls the specified class, rather than generating a query automatically.
This allows the use of multiple record types at once, more complex return types and a certain level of exception handling.

#### Nested input structures
Multiple layers of input types are also supported, but comes with its own limitations.
Most notably, all records are sent to the service as a flat list of unorganised parameters,
meaning that all the hierarchy information is lost when reading them in the service.
It is therefore recommended to avoid using this feature. The following example will illustrate this limitation.

_Schema_:
```graphql
edit(someInput: InputA!): ID! @service(service: {name: "SERVICE_REFERENCE"}) # Assumes method name is the same as field.

input InputA @table {
  b: InputB
}

input InputB @table { ... }
```

_Generated code_:
```java
var editResult = service.edit(inputARecord, inputBRecord); // Sequential, independent of the input structure.
```

#### Response mapping
Graphitron inspects the return type of the service method to decide how it should be mapped to the schema response type.

* If the mutation returns a node type and the method returns a jOOQ record, the ID assumed to be in the record is used
to look up the type through the same query that is usually used by calls to the node interface.
* Should the mutation return a scalar value, the method's return value is also treated like a scalar.
This has only been tested for strings and IDs, but may also work for other types.
* A special case is invoked when the mutation does not return a node type or scalar, where a special data class will be required
for the return type. The service method must return a static class (contained within the service class, the generator can currently
only find it there) that can provide all the data needed for the desired mapping through get-methods.
The get methods must follow the pattern `get[FieldName]`.
  * Note that this field name can be overridden by the **field** directive as well.

Wrapping any of the response types in lists should also be unproblematic. The following example demonstrates the general
use of custom service return classes.

_Schema:_
```graphql
type Mutation {
  editCustomer(
    # someValue: String # It is also allowed to put an extra input here when using services.
    editInput: EditCustomerInput!
  ): Customer! @service(service: {name: "SERVICE_CUSTOMER"}) # Returning just an ID is allowed as well.

  editCustomerWithResponse(
    editInput: EditCustomerInput!
  ): EditCustomerResponse! @service(service: {name: "SERVICE_CUSTOMER", method: "editCustomerAndRespond"}) # Example of a case where the method name does not match the field name.
}

input EditCustomerInput @table(name: "CUSTOMER") { # @table specifies the jOOQ table/record to use.
  id: ID!
  firstName: String @field(name : "FIRST_NAME") # @table specifies the expected name, either in the jOOQ table or in the custom return class.
}

type EditCustomerResponse {
  customer: Customer # Some node type.
}
```

_Required service code_:
```java
public class CustomerService {
    // The only field that is required (except whatever the database requires) is the ID.
    public CustomerRecord editCustomer(CustomerRecord person) { … }

    // EditCustomerResponse is an instance of a static class within the service.
    public EditCustomerResponse editCustomerAndRespond(CustomerRecord person) { … }

    public static class EditCustomerResponse {
        public CustomerRecord getCustomer() { … }
    }
}
```

Nesting of return types is also allowed, and functions in a more orderly fashion than nesting of input types.
This example shows the more complex case, where a nested custom return class is used.

_Schema_:
```graphql
edit(id: ID!): ReturnA! @service(service: {name: "EDIT_SERVICE"})

type ReturnA {
  returnB: ReturnB
}

type ReturnB {
  someData: String @field(name: "INTERESTING_DATA")
}
```

_Required service code_:
```java
public class EditSomethingService {
    public ReturnA edit(String id) { … } // The service method that should be called. Note that the 'id' here corresponds to the 'id' in the schema.
    
    public static class ReturnA {
        public ReturnB getReturnB() { … } // Must have a method that returns something that can be mapped to 'ReturnB' in the schema.
    }
    
    public static class ReturnB {
        public String getInterestingData() { … } // Must have a method that returns something that can be mapped to 'someData' in the schema.
    }
}
```

#### Error handling (subject to change)
Graphitron allows for simple error handling when using services. In the schema a type is an error type if it implements
the _Error_ interface and has the error **directive** set. Unions of such types are also considered error types.
The error reference must be in the response type to be automatically mapped, and it must refer to an [entry](#code-references)
in the POM XML. Only the first error to be thrown will be returned, as this uses a try-catch to map which error should be returned.

_Schema_:
```graphql
type Mutation {
  editCustomer(id: ID!): EditCustomerPayload! @service(service: {name: "SERVICE_CUSTOMER"})
}

type EditCustomerPayload {
  id: ID!
  errors: [SomeError!]!
}

# Note that @error uses the method parameter differently than other directives. It is not strictly required, but if set should return a string.
type SomeError implements Error @error(error: {name: "EXCEPTION_ILLEGAL", method: "getCauseField"}) {
  path: [String!]!
  message: String!
}
```

There will be generated one catch block per error that can be returned. In this case we have only one.

_Generated code_:
```java
try {
    editCustomerResult = customerService.editCustomer(id);
} catch (SomethingIllegalException e) {
    var error = new SomeError();
    error.setMessage(e.getMessage());
    var cause = e.getCauseField(); // This section will not be generated if this method is not set in the schema.

    // This map is constructed automatically if there are fields that can be the cause.
    var causeName = Map.of().getOrDefault(cause != null ? cause : "", "undefined");
    error.setPath(List.of(("Mutation.editCustomer." + causeName).split("\\.")));
    editCustomerErrorsList.add(error); // Update error list that is automatically defined somewhere above.
}
```

## Special interfaces
Currently, Graphitron reserves two interface names for special purposes, _Node_ and _Error_.

### Node
The _Node_ interface should contain a mandatory ID field. Any type that implements this interface will have a
_Node_ resolver generated, and can be used as return types in mutations.
This is designed to be compatible with [Global Object Identification](https://graphql.org/learn/global-object-identification/).

```graphql
interface Node {
  id: ID!
}
```

### Error
An interface used to enforce certain fields for mutations that use the **service** directive.

```graphql
interface Error {
  path: [String!]!
  message: String!
}
```

This may be removed/changed in the near future as it may not be flexible enough.

## Graphitron integration tests
For internal testing purposes Graphitron uses predefined input schemas combined with expected file results.
When altering the generator, these files likely have to be adjusted as well. These test schemas can also be read as
further examples on how to use the various directives.

Graphitron uses the [Sakila test database](https://www.jooq.org/sakila) to generate the jOOQ types needed for tests.
These are generated to `src/test/java` when running maven. These files are ignored by Git, and they are only generated
when they do not exist already or the property `testdata.schema.version` in _pom.xml_ is updated. In other words,
updating the property will refresh the jOOQ types.
This is typically only done when altering the database to add new tables or keys.
