# Graphitron
This is a code generation tool that implements GraphQL schemas by tying
schemas to underlying database models. Graphitron creates complete or partial data fetcher implementations from GraphQL schemas
using Java and [jOOQ](https://www.jooq.org/).

## Features
* Generate the GraphQL code and database queries needed to resolve a GraphQL schema with a set of directives
  * In many cases, writing backend code can be skipped entirely!
  * Need more advanced queries? Graphitron provides options for setting custom conditions, sorting, queries and more,
  while still taking care of the GraphQL-side of things
  * If needed, code generation can be skipped for individual data fetchers
* Supports Apollo Federation
* Error handling

## Usage
See the [example project](https://github.com/sikt-no/graphitron/blob/main/graphitron-example) for a complete example of how to set up
and use Graphitron, which also includes a [README.md](https://github.com/sikt-no/graphitron/blob/main/graphitron-example/README.md)
file detailing the setup and usage of the example project.
This project is also the home of our integration tests, which ensure that Graphitron generates data fetchers that run as expected.

### Code interface
Graphitron provides a static generated class (`graphitron.Graphitron`) for accessing the generated results in a user-friendly way.
This will be available after the first run of graphitron-java-codegen. A [nodeIdStrategy](#global-node-identification)
can be passed to most of these methods.

Here are a few examples of how one can retrieve schema-related code for further use:

```java
// Get the schema with all wiring included. 
GraphQLSchema schema = Graphitron.getSchema(nodeIdStrategy);
```

```java
// Get the wiring for the generated code.
RuntimeWiring runtimeWiring = Graphitron.getRuntimeWiring(nodeIdStrategy);
```

```java
// Get the wiring for the generated code as a builder. Equivalent to the getRuntimeWiring, but skips the .build() call at the end. 
RuntimeWiring.Builder runtimeWiringBuilder = Graphitron.getRuntimeWiringBuilder(nodeIdStrategy);
```

```java
// Get the type registry. You need this to build a GraphQLSchema.
TypeDefinitionRegistry registry = Graphitron.getTypeRegistry();
```

## Maven Settings
### Goals
The _generate_ Maven goal allows Graphitron to be called as part of the build pipeline. It generates all the classes
that are set to generate in the schema.

Additionally, the _watch_ goal can be used locally to watch GraphQL files
for changes, and regenerate code without having to re-run generation manually each time.
The _watch_ feature is incomplete and currently has low utility.

### Configuration
In order to find the schema files and any custom methods, Graphitron also provides some configuration options.
The options are the same for both goals.

#### General settings
* `outputPath` - The location where the code will be generated.
* `outputPackage` - The package path of the generated code.
* `schemaFiles` - Set of schema files which should be used for the generation process.
* `userSchemaFiles` - Set of schema files to provide to the user.
* `generatedSchemaCodePackage` - The location of the graphql-codegen generated classes.
* `jooqGeneratedPackage` - The location of the jOOQ generated code.
* `externalReferences` - See [Code references](#code-references).
* `externalReferenceImports` - See [Code references](#code-references).
* `globalRecordTransforms` - See [Code references](#code-references).
* `extensions` -  See [Code references](#code-references).
* `maxAllowedPageSize` - The maximum number of items that can be returned from "Cursor Connections Specification" based data fetchers. And thus also the database query limit.
* `scalars` - Extra scalars that can be used in code generation and that will be added automatically to the wiring. Reflection is used to find all the scalar definitions of the provided class(es).
* `makeKickstart` - Flag indicating if Graphitron should generate code compatible with graphql-kickstart. This is deprecated and should not be used for new projects.
* `recordValidation` - Controls whether generated mutations should include validation of JOOQ records through the Jakarta Bean Validation specification.
  * `enabled` - Flag indicating if Graphitron should generate record validation code
  * `schemaErrorType` - Name of the schema error to be returned in case of validation violations and IllegalArgumentExceptions.
    If null while `enabled` is true all validation violations and IllegalArgumentExceptions will instead cause
    _AbortExecutionExceptions_ to be thrown, leading to top-level GraphQL errors.
    Also, if the given error is not present in the schema as a returnable error for a specific mutation,
    validation violations and IllegalArgumentExceptions on this mutation will cause top-level GraphQL errors.

See the [pom.xml](https://github.com/sikt-no/graphitron/blob/main/graphitron-example/graphitron-example-spec/pom.xml)
of _graphitron-example-spec_ for an example on how to configure these settings.

#### Code references

In some instances you need to link custom java code to Graphitron's generated data fetchers.
In order for Graphitron to find the external Java code it needs to be listed in the maven configuration described in this section.

* _externalReferences_ - List of references to classes that can be applied through certain directives. Note that this is being deprecated in favor of using the className in the directives.
* _externalReferenceImports_ - List of packages that should be searched for classNames used in directives.
* _extensions_ - Graphitron classes can be modified with extensions, allowing plugin users to provide their own implementations to modify the code generation process.
  Currently, all extensions are instantiated via [ExtendedFunctionality](https://github.com/sikt-no/graphitron/blob/main/graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/configuration/ExtendedFunctionality.java), limiting extension to specific classes.

Example of referencing a class through the configuration:
```xml
<externalReferences>
  <element> <!--The name of this outer element does not matter.-->
    <name>CUSTOMER_TRANSFORM</name>
    <fullyQualifiedClassName>some.path.CustomerTransform</fullyQualifiedClassName>
  </element>
</externalReferences>
```

Example of importing a package through the configuration:
```xml
<externalReferenceImports>
    <package>some.path</package>
</externalReferenceImports>
```

Example of applying a global transform in the POM:
```xml
<globalRecordTransforms>
  <element>
    <fullyQualifiedClassName>some.path.CustomerTransform</fullyQualifiedClassName>
    <method>someTransformMethod</method> <!-- Method in the referenced class to be applied. -->
    <scope>ALL_MUTATIONS</scope> <!-- Only ALL_MUTATIONS is supported right now. -->
  </element>
</globalRecordTransforms>
```

Example of extending/customizing certain classes:

```xml
  <!-- The elements should contain fully-qualified class names -->
<extensions>
  <element>
    <extendedClass>no.sikt.graphitron.validation.ProcessedDefinitionsValidator</extendedClass>
    <extensionClass>no.sikt.graphitron.validation.FSProcessedDefinitionsValidator</extensionClass>
  </element>
  <element>
    <extendedClass>no.sikt.graphitron.definitions.objects.InputDefinition</extendedClass>
    <extensionClass>no.sikt.graphitron.definitions.objects.FSInputDefinition</extensionClass>
  </element>
</extensions>
```

## Directives
> Note that several of the code examples below use unaliased jOOQ tables for readability, while the code Graphitron generates only uses aliased tables.
### Common directives
#### splitQuery directive
Applying this to a type reference denotes a split in the generated jOOQ query.
This means that we want a new, explicit data fetcher for the annotated field.
Fields in the Query and Mutation types, as well as fields with arguments, do not require this directive as
they always require explicit data fetchers.

In this example, the code generation creates the classes _ATypeDBQueries_ and _ATypeGeneratedDataFetcher_, containing
the data fetcher code required to fetch _OtherType_ given _AType_.
Note that this example would not work in practice as we have not specified any tables to map to yet.
```graphql
type AType {
  otherType: OtherType @splitQuery # Build new data fetcher/query for this field.
}

type OtherType {
  name: String
}
```
The actual code generated by Graphitron will be discussed in the next sections.

#### notGenerated directive
Set this on any query or mutation field that Graphitron would usually generate a new data fetcher for in order to
cancel of it.
Since graphql still requires the data fetchers to resolve the fields,
they will have to be implemented and [wired manually](https://javadoc.io/static/com.graphql-java/graphql-java/10.0/graphql/schema/idl/RuntimeWiring.html).
If one is not specified, the field will always return null.

```graphql
type Query {
  aQuery: AType @notGenerated # Do not generate this query automatically.
}

type AType {
  otherType: OtherType @splitQuery @notGenerated # Require a new data fetcher for this field, but do not generate it automatically.
}

type OtherType {
  name: String
}
```

#### field directive
By default, Graphitron assumes each field not annotated with the **notGenerated** or **splitQuery** directives to have a name
equal to the column it corresponds to in the jOOQ table or service record. 
Specifying the _name_-parameter in the **field** directive overrides this behaviour.
This directive applies to regular type fields, input type fields, arguments and enum values.
For determining which table the field name should be taken from, see the [table](#table-directive) and [reference](#reference-directive) directives.


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

The _javaName_-parameter is an obscure special case for when a table/jOOQ-record needs to interact with a Java record.
It acts as a second override, defaulting to the value of _name_ when not set. A concrete example of where this parameter
is required is when using records with conditions or queries in general where the contents of the record must be
matched to jOOQ table fields.

```graphql
type Query {
  query(argument: SomeInput): SomeType
}

input SomeInput @record(record: {name: "SOME_RECORD"}) {
  value: String @field(name: "ACTUAL_VALUE_NAME", javaName: "ACTUAL_JAVA_RECORD_FIELD_NAME")
}

type SomeType @table { … }
```

### Tables, joins and records
#### table directive
The **table** directive links the object, interface or input type to a jOOQ table and record. Any **field**-directives within
this type will use this table as the source for the field mapping. This targets jOOQ generated classes, so
the _name_ parameter must match the table name in jOOQ if it differs from the database. The _name_ parameter is optional,
and does not need to be specified if the type name already equals the table name.

In the example below the generator would apply an implicit path join on the key between the two tables when building the subquery for the reference.
Note that this can only work if there is only one foreign key between the tables. For example, given tables from the
schema example below, the result will be `TABLE_A.table_b()`. If more than one key exists, a more complex configuration
is required, see [reference](#reference-directive).

_Schema_:
```graphql
type Table_A @table { # Table name matches the type name, name is unnecessary.
  someOtherType: OtherType
}

type OtherType @table(name: "TABLE_B") {
  name: String
}
```

_Generated result (excluding row mapping and aliases)_:
```java
select(
        field(
            select(TABLE_A.table_b().NAME)
            .from(TABLE_A.table_b())
        )
        .from(TABLE_A)
```

If a table type contains other types that do not set their own tables, the previous table type is used instead.
This also applies to enum types.

#### reference directive
There are, of course, many cases where the connection between two tables is more complex.
In such cases, Graphitron requires some extra parameters to make the right connections.
This is done through the **reference** directive, which contains the following parameters:

* _references_ - This parameter contains an ordered list of reference elements that is used to compose the path from
the table of this type to the table corresponding to the type of the field.
* _reference element_:
  * _table_ - This defaults to the table of the type that is referenced by the field the directive is set on.
Setting this overrides whatever table may be set there. This must match a table name from jOOQs _Table_ class.
  * _key_ - If there are multiple foreign keys from one type to another, then this parameter is required for defining which
key that should be used. This must match a key name from jOOQs _Keys_ class.
  * _condition_ - This parameter is used to place an additional constraint on the two tables, by referring to the correct [entry](#code-references)
in the POM XML. In the cases where there is no way to deduce the key between the tables and the _key_ or _table_ parameter is not set,
this condition will be assumed to be an _on_ condition to be used in a join operation between the tables.

Note that joins only apply to the field they are set on, as it is only applied to the field's subquery. If the field is a scalar type,
it can be linked to a jOOQ column in another table using this directive. If the field is a GraphQL type, all fields within the
type will use the resulting join operation.

Also note that setting both the _table_ and _key_ parameters in the same _reference element_ is redundant if there is
only one path between the tables, since either is enough to make the join.

The following examples will assume that this configuration is set so that Graphitron can find the referenced classes: 
```xml
<externalReferenceImports>
  <package>some.path</package>
</externalReferenceImports>
```

For the first example we will apply this simple condition method on tables that _do_ have a direct connection.
```java
class CustomerCondition {
    static org.jooq.Condition addressJoin(Customer customer, Address address) { … }
}
```

The method returns a jOOQ condition, which will be added to the where conditions for the subquery.

_Schema_:
```graphql
type Customer @table {
  addresses: [Address!]! @splitQuery @reference(references: [{condition : {className: "CustomerCondition", method: "addressJoin"}}])
}
```

_Generated result_:
```java
.where(some.path.CustomerCondition.addressJoin(CUSTOMER, CUSTOMER.address()))
```

The condition is thus an additional constraint applied on both tables.
In a slightly different case where the tables are not directly connected, the join will behave differently.
If the two tables do not have any foreign keys between them, or there are multiple, and the one to use was not specified,
the generated result would follow a pattern like the code below.

```java
.from(CUSTOMER)
.join(ADDRESS)
.on(some.path.CustomerCondition.addressJoin(CUSTOMER, ADDRESS))
```

Providing only a key will yield the same result. Note that in this example the key and the reference directive itself
are redundant since there is only one key between the tables. Assume the _Address_ type has the **table** directive set.

_Schema_:
```graphql
type Customer @table {
  addresses: [Address!]! @splitQuery @reference(references: [{key: "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY"}]) 
}
```

_Generated result_:
```java
.from(CUSTOMER.address())
```

Providing both a key and a condition will result in a sum of both the first and previous examples,
meaning both a join on a key and one additional condition will be applied.

Using multiple _reference elements_ will create additional joins like the previous examples have already shown.
The example below is a simple illustration of this. Assume the _Film_ type has the **table** directive set.

_Schema_:
```graphql
type Payment @table {
  # The path here is PAYMENT -> RENTAL -> INVENTORY -> FILM
  film: Film! @splitQuery @reference(references: [{table: "RENTAL"}, {table: "INVENTORY"}])
}
```

First, Graphitron defines aliases for these joins, one per step.

```java
var payment = PAYMENT;
var payment_rental = payment.rental();
var payment_rental_inventory = payment_rental.inventory();
var payment_rental_inventory_film = payment_rental_inventory.film();
```

Then, the aliases are applied where necessary. This is the generated subquery for the references. 
Assume that the Film type contains a field named "title".

```java
select(select.optional("title", payment_rental_inventory_film.TITLE))
.from(payment_rental)
.join(payment_rental_inventory)
.join(payment_rental_inventory_film)
```

### Query conditions
To either apply additional conditions or override the conditions added by default, use the **condition** directive.
It can be applied to both input parameters and data fetcher fields, and the scope of the condition will match the element it is put on.
It provides the following parameter options:

* _condition_ - Reference class and method name (see [code references](#code-references))
* _override_ - If true, disables any default checks that are added to the affected arguments, otherwise add the new condition in
addition to the default ones.

If the condition should take a list of nested input types, they need to correspond to records. 
This can be achieved by either using the **table** directive or the **record** directive.
The former will result in a mapping to a jOOQ record, while the latter will produce a mapping to the referenced Java record.
Thus, the condition will take the created record as parameter, allowing for more complex conditions.
Worth noting here is that jOOQ records can not contain other records.
Also, to use a **record** directive on a nested input all the preceding inputs must also be Java records.

There are concrete examples of how conditions and Java records may look in our test project files,
such as [CustomerConditions](https://github.com/sikt-no/graphitron/blob/main/graphitron-example/graphitron-example-service/src/main/java/no/sikt/graphitron/example/service/conditions/CustomerConditions.java)
and [CustomerJavaInput](https://github.com/sikt-no/graphitron/blob/main/graphitron-example/graphitron-example-service/src/main/java/no/sikt/graphitron/example/service/records/CustomerJavaInput.java).

#### Example: Setup
The following examples will assume this configuration exists:

```xml
<externalReferenceImports>
  <package>some.path</package>
</externalReferenceImports>
```

#### Example: No _override_ on input parameter
Adds this condition in addition to the conditions automatically generated for this parameter.
The method must have the table and the input parameter type as parameters.

_Schema_:
```graphql
cityNames: [String!] @field(name: "CITY") @condition(condition: {className: "CityCondition", method: "cityMethod"})
```

_Resulting code_:
```java
.where(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : noCondition())
.and(some.path.CityCondition.cityMethod(CITY, cityNames))
```

#### Example: No _override_ on field with input parameters
Adds this condition in addition to the conditions automatically generated for this parameter.
The method must have the table and all input parameter types for this field as parameters.

_Schema_:
```graphql
cities(
    countryId: String! @field(name: "COUNTRY_ID"),
    cityNames: [String!] @field(name: "CITY")
): [City] @condition(condition: {className: "CityCondition", method: "cityMethod"})
```

_Resulting code_:
```java
.where(CITY.COUNTRY_ID.eq(countryId))
.and(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : noCondition())
.and(some.path.CityCondition.cityMethod(CITY, countryId, cityNames))
```

#### Example: Both field and parameters
Removes none of the automatically generated conditions, but add conditions for both the field and parameter as shown in the two previous examples.

#### Example: With _override_ on input parameter
Replaces the automatically generated conditions for the input parameter with the specified condition.
The method must have the table and the input parameter type as parameters.

_Schema_:
```graphql
cityNames: [String!] @field(name: "CITY") @condition(condition: {className: "CityCondition", method: "cityMethod"}, override: true)
```

_Resulting code_:
```java
.where(some.path.CityCondition.cityMethod(CITY, cityNames))
```

#### Example: With _override_ on field with input parameters
Replaces the automatically generated conditions for the field with the specified condition.
The method must have the table and all input parameter types for this field as parameters.

_Schema_: 
```graphql
cities(
    countryId: String! @field(name: "COUNTRY_ID"),
    cityNames: [String!] @field(name: "CITY")
): [City] @condition(condition: {className: "CityCondition", method: "cityMethodAllElements"}, override: true)
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
    cityNames: [String!] @field(name: "CITY") @condition(condition: {className: "CityCondition", method: "cityMethod"}, override: true)
): [City] @condition(condition: {className: "CityCondition", method: "cityMethodAllElements"}, override: true)
```

_Resulting code_:
```java
.where(some.path.CityCondition.cityMethod(CITY, cityNames))
.and(some.path.CityCondition.cityMethodAllElements(CITY, countryId, cityNames))
```

#### Example: Conditions on input type fields
In the following example we have conditions on input type parameters, where some have the _override_ value set to _true_.
Conditions placed on the outer levels of a nested input type, will extract all the scalar values within the type and use them as arguments.
The default conditions will still be generated for the inner levels of a nested input type when they are not overridden by a parent condition.

_Schema_:
```graphql
type Query {
  query(
    staff: StaffInput! @condition(condition: {name: "STAFF_CONDITION", method: "staff"})
  ) : [Staff]
}

input StaffInput {
  info: ContactInfoInput!
  active: Boolean!
}

input ContactInfoInput {
  name: NameInput! @condition(condition: {name: "STAFF_CONDITION", method: "name"}, override: true)
  jobEmail: EmailInput! @condition(condition: {name: "STAFF_CONDITION", method: "email"})
}

input NameInput {
  firstname: String! @field(name: "FIRST_NAME") @condition(condition: {name: "STAFF_CONDITION", method: "firstname"})
  lastname: String! @field(name: "LAST_NAME") @condition(condition: {name: "STAFF_CONDITION", method: "lastname"})
}

input EmailInput {
  email: String!
}
```

_Resulting code_:
```java
.where(some.path.StaffCondition.firstname(STAFF, staff.getInfo().getName().getFirstname()))
.and(some.path.StaffCondition.lastname(STAFF, staff.getInfo().getName().getLastname()))
.and(STAFF.EMAIL.eq(staff.getInfo().getJobEmail().getEmail()))
.and(STAFF.ACTIVE.eq(staff.getActive()))
.and(some.path.StaffCondition.staff(STAFF, staff.getInfo().getName().getFirstname(), staff.getInfo().getName().getLastname(), staff.getInfo().getJobEmail().getEmail(), staff.getActive()))
.and(some.path.StaffCondition.name(STAFF, staff.getInfo().getName().getFirstname(), staff.getInfo().getName().getLastname()))
.and(some.path.StaffCondition.email(STAFF, staff.getInfo().getJobEmail().getEmail()))
```

#### Example: Condition using flat record configuration
This is the simplest case, when we have only one layer of input types.
In the example below, the input type will be mapped to a jOOQ record that corresponds to the table.
This has the advantage of not needing to define and reference a Java record, but the result is equivalent.

_Schema_:
```graphql
cities(
    cityInput: CityInput!
): [City] @condition(condition: {className: "CityCondition", method: "cityMethodAllElements"}, override: true)

input CityInput @table(name: "CITY") {
    countryId: String! @field(name: "COUNTRY_ID")
}
```

#### Example: Condition using nested record configurations
Currently, if one layer input is a record then the entire preceding structure must also be records.
This is because the mapping from DTOs to records happens before the query is executed. The most relevant details to note here is that while jOOQ records
are allowed inside a Java record definition, they must always be on "leaf" input types that do not have further record nesting.
Additionally, jOOQ records can, of course, not contain Java records. 

This pattern is required when using nested input types that use lists of the inner input types.
Note the extra parameter for the [**field**](#field-directive) directive since we are using Java records in fetch queries.

_Schema_:
```graphql
cities(
    cityInput: CityInput1!
): [City] @condition(condition: {className: "CityCondition", method: "cityMethodAllElements"}, override: true)

# Can not skip record here even if we only want one of the other input types in our condition.
input CityInput1 @record(record: {name: "LAYER_1_RECORD"}) {
    # A condition can also be placed here, but this may be redundant given the condition above in this case.
    cityNames: [String!] @field(name: "CITY", javaName: "javaCityNamesField") @condition(condition: {className: "CityCondition", method: "cityMethod"}, override: true)
    countryId: String! @field(name: "COUNTRY_ID", javaName: "javaCountryField")
    city2: [CityInput2]
    city3: [CityInput3]
}

input CityInput2 @record(record: {name: "LAYER_2_RECORD"}) {
    countryId: String! @field(name: "COUNTRY_ID", javaName: "javaCountryField2")
}

input CityInput3 @table(name: "CITY") {
    countryId: String! @field(name: "COUNTRY_ID")
}
```

_Resulting code_:
```java
.where(some.path.CityCondition.cityMethodAllElements(CITY, cityInputRecord))
.and(some.path.CityCondition.cityMethod(CITY, cityInputRecord.getCityNames()))
```

#### Example: Schema with listed input types and condition set _on_ listed input field
Graphitron does not support more than one level of listed input types. To be able to generate code for the cases
where we initially would generate several levels of lists, we instead must declare an override condition before the
second listed input in the hierarchy. Then we must write subsequent checks manually in the method specified for the
condition. In this case we specify an override condition _on_ a field holding a list of input types. The condition method
will then be passed this list of input types and further checks on this list will be done in the method.

```graphql
type Query {
  query(
    inputs1: [Input1] @condition(condition: {name: "RECORD_FETCH_STAFF_CONDITION", method: "input1"}, override: true)
  ) : [Staff]
}

input Input1 @record(record: {name: "JAVA_RECORD_STAFF_INPUT1"}) {
  names: [NameInput]
  active: Boolean
}

input NameInput @record(record: {name: "JAVA_RECORD_STAFF_NAME"}) {
  firstname: String! @field(name: "FIRST_NAME")
  lastname: String! @field(name: "LAST_NAME")
}
```

_Resulting_code_:
```java
.where(some.path.RecordStaffCondition.input1(STAFF, inputs1RecordList))
```

#### Example: Schema with listed input types and condition set on input field _inside_ a list input
Here we have a schema of several levels of input types and several of these are lists. 
Since Graphitron does not support more than one level of listed inputs, we must specify an override condition before
the second listed input in the hierarchy. 
The difference from the previous example is that the condition is set on a field inside a listed input and before the
second listed input.

_Schema_:
```graphql
type Query {
  query(
    input3: Input3!
  ) : [Staff]
}

input Input3 @record(record: {name: "JAVA_RECORD_STAFF_INPUT3"}) {
  inputs2: [Input2]
}

input Input2 @record(record: {name: "JAVA_RECORD_STAFF_INPUT2"}) {
  input1: Input1 @condition(condition: {name: "STAFF_CONDITION", method: "input1"}, override: true)
}

input Input1 @record(record: {name: "JAVA_RECORD_STAFF_INPUT1"}) {
  names: [NameInput]
  active: Boolean
}

input NameInput @record(record: {name: "JAVA_RECORD_STAFF_NAME"}) {
  firstname: String! @field(name: "FIRST_NAME")
  lastname: String! @field(name: "LAST_NAME")
}
```

_Resulting_code_:
```java
.where(
       input3Record.getInputs2() != null && input3Record.getInputs2().size() > 0 ?
       DSL.row(DSL.trueCondition()).in(
               input3Record.getInputs2().stream().map(internal_it_ ->
                       DSL.row(some.path.StaffCondition.input1(STAFF, internal_it_.getInput1()))
               ).toList()
       ) : DSL.noCondition()
)
```

### Enums
Enums can be mapped in two ways. The **field** directive is already covered [here](#field-directive).
An alternative method is to set up a Java enum instead through a jOOQ converter. These can be referenced
using the **enum** directive, by pointing to the appropriate [entry](#code-references).

```graphql
enum SomeEnum @enum(enumReference: {name: "THE_ENUM_REFERENCE"}) {
  E0
  E1
  E2
}
```

### Differences between mutations and queries
Mutations have some limitations when generated through Graphitron, as mutations can potentially take many inputs which may be related to multiple tables.
Automatic generation of the entire data fetcher is currently only viable for simple cases where one input type representing one type of jOOQ record is handled.
This limitation may be removed in the future for mutations other than delete mutations.

Use the **mutationType** directive and the accompanying _typeName_ parameter to denote a mutation that should be fully
generated. To specify which table should be affected, the **table** directive is used just like for the usual types used
for queries. Unlike for queries, the directive is always required on input types in order for Graphitron to know which records should be mutated.
As usual, **field** may also be applied to adjust the mapping of individual fields.

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
    id: ID!  # Note, delete mutations need the return type to have a field of type "ID".
}

type EditResponseWithCustomer {
    customer: Customer
}
```

If all required fields for an insert or upsert operation are not set in the input type, a warning will be generated.
In the future this may change to an exception instead, as an incomplete set of required fields will result in a data fetcher
that compiles, but will always fail when executed.

Note that mutations need either the **mutationType** or the **service** directive set, but not both, in order to be generated.

### Services
The **service** directive allows for full customization of any database operations while still generating data fetchers.
The directive points to a class [entry](#code-references) in the POM XML.
The method name is either specified through the directive or is assumed to be the same as the field name.

Note that any directives that would usually alter the database operation, such as conditions, are ignored for services.

There are concrete examples of how services may look in our test project files,
such as [CustomerService](https://github.com/sikt-no/graphitron/blob/main/graphitron-example/graphitron-example-service/src/main/java/no/sikt/graphitron/example/service/CustomerService.java).

_Schema:_
```graphql
type Query {
  getCustomer: Customer! @service(service: {name: "SERVICE_CUSTOMER"})

  fetchCustomer(
    id: ID!
  ): Customer! @service(service: {name: "SERVICE_CUSTOMER", method: "getCustomer"}) # Example of a case where the method name does not match the field name.
}

type Mutation {
  editCustomer1(
    editInput: EditCustomerInput!
  ): Customer! @service(service: {name: "SERVICE_CUSTOMER"}) # Returning just an ID is allowed as well.

  editCustomer2(
    editInput: EditCustomerInput!
  ): Customer! @service(service: {name: "SERVICE_CUSTOMER", method: "editCustomerAndRespond"}) # Example of a case where the method name does not match the field name.
}

input EditCustomerInput @table(name: "CUSTOMER") { # @table specifies the jOOQ table/record to use as input to the service.
  id: ID!
  firstName: String @field(name : "FIRST_NAME") # @field specifies the expected field name either in the jOOQ table or in the custom return class.
}
```

#### Resolving splitQuery fields after services
Resolving **splitQuery** fields after a service is currently only supported for services returning jOOQ records.
To enable Graphitron to resolve a **splitQuery** field after a service, the returned record must include all the key fields required for the next query.
Currently, only primary keys of the previous object are used when resolving a **splitQuery** field.

_Schema:_
```graphql
type Query {
  customerService: Customer @service(service: {className: "no.sikt.graphitron.example.service.CustomerService", method: "customer"})
}

type Customer @table {
  address: Address @splitQuery
}
```
For Graphitron to be able to resolve the `address` field in the `Customer` type in this example, 
the jOOQ record returned from the service must contain the primary key of the `CUSTOMER` table, in this case `CUSTOMER_ID`.

#### Nested input structures
Multiple layers of jOOQ input types are also supported, but comes with its own limitations.
Most notably, all records are sent to the service as a flat list of unorganised parameters.
This means that all the hierarchy information is lost when reading them in the service.
It is therefore recommended to avoid using this feature, and use [Java records](#input-types-with-java-records) instead.
The following example will illustrate this limitation.

_Schema_:
```graphql
edit(someInput: InputA!): ID! @service(service: {name: "SERVICE_REFERENCE"}) # Assumes method name is the same as field.

input InputA @table {
  b: InputB
}

input InputB @table { … }
```

_Generated code_:
```java
var editResult = service.edit(inputARecord, inputBRecord); // Sequential, independent of the input structure.
```

#### Input types with Java records
When using services, custom input types may also be used. It is a referenced class [entry](#code-references),
which must contain appropriate _set_ methods. The name of the method used for setting the values is assumed to be the
same as a fields name in the schema, unless the **field** directive overrides this. These classes can be nested, listed
and may contain JOOQ-records.

_Schema:_
```graphql
customer(editInput: EditCustomerInput!): ID! @service(service: {name: "SERVICE_CUSTOMER"})

input EditCustomerInput @record(record: {name: "JAVA_RECORD_CUSTOMER"}) { # @record specifies the Java record to use. Setting @table here would not do anything.
  id: ID! # We need a method with the name "setId" in the record class.
  first: String @field(name: "FIRST_NAME") # We need a method with the name "setFirstName" in the record class. Overridden by @field.
}
```

_Required service code_:
```java
public class CustomerService {
    // The correct java class is defined through the code reference in the configuration.
    public String customer(JavaCustomerRecord person) { … }
}
```

#### Context variables
Using the _contextArguments_ parameter on the **service** or **condition** directives, one can specify which context values should be passed to the referenced method.
The names of the context values correspond to the keys of the values as found in the
[`graphql.GraphQLContext`](https://javadoc.io/static/com.graphql-java/graphql-java/15.0/graphql/GraphQLContext.html) for the current operation.
Graphitron will fetch these values and try to cast them to the matching datatypes in the method arguments.
Note that context arguments are always placed at the end of the method arguments, in the order specified by the _contextArguments_ parameter.

_Schema:_
```graphql
customer(name: String): Customer @service(service: {name: "SERVICE_CUSTOMER"}, contextArguments: "someCtxField")
```

_Required service code_:
```java
public class CustomerService {
    // The context value will be cast to String as the last parameter type here is String.
    // This inference is necessary because the GraphQLContext does not retain the type information.
    // Context arguments always come last in the signature, even after pagination-related parameters.
    public String customer(String name, String someCtxField) { … }
}
```

#### Response mapping
By default, Graphitron inspects the return type of the service methods to decide how it should be mapped to the
schema response type.

_Schema:_
```graphql
type Mutation {
  editCustomerWithResponse(
    id: ID!
  ): EditCustomerResponse! @service(service: {name: "SERVICE_CUSTOMER", method: "editCustomerAndRespond"})
}

type EditCustomerResponse @record(record: {name: "JAVA_RECORD_CUSTOMER"}) {
  id: ID! # We need a method with the name "getId" in the record class.
  first: String @field(name: "FIRST_NAME") # We need a method with the name "getFirstName" in the record class. Overridden by @field.
  customer: Customer # Some node type.
}
```

_Required service code_:
```java
public class CustomerService {
    // The only field that is required (except whatever the database requires) is the ID. The correct java class is defined through the code reference in the configuration.
    public JavaCustomerRecord editCustomer(String id) { … }
}
```

There are some rules that services must follow. Services that are _not_ in a root type must return a map.
The map should use the record ID as key, and the value corresponds to the return type of the previous data fetcher.

These rules vary slightly when using pagination.
All paginated fields are treated as listed.
In other words, root level services should return a list.
If the service is not at the root level it should return a map with the ID as key and the value should be set to a list of records.

Schema pagination wrapping is handled automatically, but pagination parameters must be manually applied to any queries
that are used in the service.

_Schema:_
```graphql
type Query {
  getCustomer(id: ID!): CustomerWrapper! @service(service: {name: "SERVICE_CUSTOMER"})
  getCustomerPaginated(id: ID!, first: Int = 100, after: String): CustomerWrapperConnection! @service(service: {name: "SERVICE_CUSTOMER"})
}

type CustomerWrapperConnection { … }

type CustomerWrapperConnectionEdge { … }

type CustomerWrapper @record(record: {name: "JAVA_RECORD_CUSTOMER_WRAPPER"}) {
  id: ID! # We need a method with the name "getId" in the record class.
  first: String @field(name: "FIRST_NAME") # We need a method with the name "getFirstName" in the record class. Overridden by @field.
  customer: Customer # Some node type.
}
```

_Required service code_:
```java
public class CustomerService {
    public JavaCustomerWrapper getCustomer(String id) { … }
    public List<JavaCustomerWrapper> getCustomerPaginated(String id, int pageSize, String after) { … }
}
```

If the queries were placed on a query not in the Query type:

_Required service code_:
```java
public class CustomerService {
    public Map<String, JavaCustomerWrapper> getCustomer(String id) { … }
    public Map<String, List<JavaCustomerWrapper>> getCustomerPaginated(String id, int pageSize, String after) { … }
}
```

Nesting of return types is also allowed.
This example shows the more complex case, where two nested Java return record classes are used.
Such a setup can also be applied to input types.

_Schema_:
```graphql
something(id: ID!): ReturnA! @service(service: {name: "SOMETHING_SERVICE"}) # Query or mutation.

type ReturnA @record(record: {name: "RECORD_A"}) {
  returnB: ReturnB
}

type ReturnB @record(record: {name: "RECORD_B"}) {
  someData: String @field(name: "INTERESTING_DATA")
}
```

_Required service code_:
```java
public class SomethingService {
    public ReturnA something(String id) { … } // The service method that should be called. Note that the 'id' here corresponds to the 'id' in the schema.
}
```

_Required record code_:
```java
public class ReturnA {
  public ReturnB getReturnB() { … } // Must have a method that returns something that can be mapped to 'ReturnB' in the schema.
}

public class ReturnB {
  public String getInterestingData() { … } // Must have a method that returns something that can be mapped to 'someData' in the schema.
}
```

### Error handling
Graphitron allows for simple error handling. In the schema a type is an error type if it implements
the _Error_ interface and has the **error** directive set. Unions of such types are also considered error types.

The **error** directive serves to map specific Java exceptions to GraphQL errors. This directive is applied to error types 
in the schema and accepts a list of handlers with parameters, specifying how various exceptions should be mapped.

Here is an example of how the **error** directive can be used:

```graphql
type MyError implements Error @error(handlers:
[
  {
    handler: DATABASE,
    code: "20997",
    description: "You are not allowed to do this like that"
  },
  {
    handler: GENERIC,
    className: "org.example.YouAreNotAllowedException",
    matches: "stop doing this",
    description: "You are not allowed to do this like that"
  }
]) {
  path: [String!]!
  message: String!
}
```

In this instance, certain exceptions are mapped to be handled as _MyError_. The parameters inside the handler object are explained as follows:
- `handler` - Determines the error handler to use. Presently, there are two options available, DATABASE and GENERIC.
- `className` -  Specifies the fully qualified name of the exception class. This field is required for the GENERIC handler and defaults to `org.jooq.exception.DataAccessException` if not provided for the DATABASE handler.
- `code` - For the `DATABASE` handler this is the database error code associated with the exception. Not in use for the GENERIC handler.
- `matches` - Can be used to specify a string that the exception message must contain in order to be handled.
- `description` - A description of the error to be returned to the user. If not provided, the exception message will be used instead.

### External field
The **externalField** directive indicates that the annotated field is
retrieved using a static extension method implemented in Java.
This is typically used when the field's value requires custom logic implemented in Java.

Requirements:

- Method has to include the table class it's extending as its single parameter - additional parameters is not supported.
- Method needs to return the generic type `Field`
- Parameter type of the generic type needs to match scalar type used in your field in the GraphQL schema, i.e: `Field<TYPE_MATCHING_SCALAR_TYPE>`
- Code reference should be added to `externalReferenceImports` in your `pom.xml`. See [code references](#code-references) for details on configuration.
- The `type` where **externalField** is used needs to have a table associated to it

#### Example
Say you have a GraphQL schema like this, where you have defined a type with an associated database table, which returns a one field:

```graphql
type Film @table(name: "FILM") {
    isEnglish: Boolean @externalField
}
```

You want to write custom logic for the `isEnglish`-field and therefore add the **externalField**-directive.
The example below show such custom logic can be implemented:

```java
public static Field<Boolean> isEnglish(Film film) {
    return DSL.iif(
            film.ORIGINAL_LANGUAGE_ID.eq(1),
            inline(true),
            inline(false)
    );
}
```
The static extension method is required to have the table class it's extending as a parameter, which is `Film` in this example.
It also needs to return the generic type `Field`, with the actual type within matching the scalar type from the schema.
In this example you see that the scalar type in the schema is `String`, which means that the generic type needs to look
like this `Field<String>`.

The file containing this method needs to be discoverable for Graphitron.
You need to provide the path for the file within the tag `externalReferenceImports` in your `pom.xml`:
```xml
<externalReferenceImports>
  <package>some.path.MyClass</package>
</externalReferenceImports>
```
Graphitron will then use this custom logic in the select part of the database query, as shown below:

```java
public class QueryDBQueries {
    public static Film queryForQuery(DSLContext ctx, SelectionSet select) {
        var _film = FILM.as("film_2952383337");
        return ctx
                .select(DSL.row(some.path.MyClass.isEnglish(_film)).mapping(Functions.nullOnAllNull(Film::new)))
                .from(_film)
                .fetchOne(it -> it.into(Film.class));
    }
}
```

Note that jOOQ records do not have space for extra fields, and any usage of external fields where such fields must be stored in a jOOQ record will fail.

### Interface queries
Graphitron supports generating queries for two types of interfaces.

#### Single table interfaces
Single table interfaces are interfaces where every implementation is in the same table, and the type of the interface is determined by a discriminator column. 
Graphitron supports generating queries for this type of interface on the Query-type.

In order to define a single table interface and its types, the **discriminate** and **discriminator** directives are used, in addition to the [**table**](#table-directive) directive.

For an interface to be considered a single table interface, both the **table** and **discriminate** directives must be set:
```graphql
interface Employee @table(name: "EMPLOYEE") @discriminate(on: "EMPLOYEE_TITLE") {
  …
}
```

The **discriminate** directive determines the discriminator column for the interface.
In the example above, the column `EMPLOYEE_TITLE` in table `EMPLOYEE` is the discriminator column.

Each implementation of the interface must have the **table** and **discriminator**-directives set:

```graphql
type TechLead implements Employee @table(name: "EMPLOYEE") @discriminator(value: "TECH_LEAD") {
  …
}
```
The **table** directive parameter must be the same as for the interface, and the **discriminator** directive indicates which value the discriminator column has if the row is of type `TechLead`. 
In other words, if `EMPLOYEE.EMPLOYEE_TITLE` is `"TECH_LEAD"`, the row is of type `TechLead`.

With the example above, Graphitron can generate queries like these in the Query-type:

```graphql
type Query {
  employees: [Employee]
  employees(first: Int = 100, after: String): EmployeeConnection
}
```

Queries with input and [query conditions](#Query-conditions) are also supported.

- Types implementing multiple single table interfaces are supported if they have the same discriminator value for all interfaces
- The discriminator column must return a string type
- Every field in the interface must have the same configuration in every implementing type
  - For example, overriding **field** configuration from the interface is currently not supported
- Other fields sharing the same name must also have the same configuration across the implementing types

#### Multi table interfaces
Multi table interfaces are interfaces where the implementations are spread across tables, and a row's type is determined by
its table. Graphitron supports generating queries for this type of interface on the Query-type.

No special directives are required on the interface definition. Any directives on fields in the interface will be ignored, and should instead be placed on the fields in the implementing type.

```graphql
interface Titled {
  title: String
}
```

For every implementing type, the [**table**](#table-directive) directive is required.

```graphql
type Film implements Titled @table(name: "FILM") {
  title: String @field(name: …)
  …
}

type Book implements Titled @table(name: "BOOK") {
  title: String
  …
}
```

With the example above, Graphitron can generate queries like these in the Query-type:

```graphql
type Query {
  titled: [Titled]
  titled(first: Int = 100, after: String): TitledConnection
}
```

### Multitable Unions
Graphitron supports Union queries on the Query type. All the union's subtypes need to have the [**table**](#table-directive) directive set.

_Schema setup:_
```graphql
union LanguageStaffUnion = Language | Staff

type Language @table {
  name: String
  …
}

type Staff @table {
  id: Int @field(name: "STAFF_ID") 
  …
}

```
_Example:_
```graphql
type Query {
  languageOrStaff: [LanguageStaffUnion]
}
```

#### Query conditions

[Conditions](#Query-conditions) on queries returning multi table interfaces are also supported. 
Since the table is passed as a parameter to the condition method and each implementing type has a different table, 
a unique method for each implementation is necessary.
These methods must all share the same method signature, except for the first table parameter.

```graphql
type Query {
  titled(prefix: String): [Titled] @condition(condition: {className: "TitledCondition", method: "titledMethod"})
}
```

```java
class TitledCondition {
  static Condition titledMethod(Film film, String prefix) {…}
  static Condition titledMethod(Book book, String prefix) {…}
}
```

### Other directives
#### lookupKey directive
Lookup is a special case of fetching data, which can be generated using the **lookupKey**-directive.
For each element that is requested, either an object or null will be returned, and they will be the same order as in the request.
In order to determine which inputs should identify such an element, keys have to be set explicitly in the schema.
This is where the **lookupKey**-directive comes in. If at least one key is set with this directive, the query will
automatically become a lookup. Only arguments for root-level fields or their referenced input types can be keys.
There are some constraints that must be respected when using this directive:

* All keys must be one-dimensional listed types. It does not make sense to invoke this logic for fetching single objects.
* More than one key may be used at once, but each key must always have the same number of values.
  In addition, each value in a key must be correlated with the values of any other keys at the same indexes.
  This can be enforced by wrapping the keys in input types.

The keys can be wrapped with input types and can be set on input type references, but they must always end up being a one-dimensional list.
In other words, a list of input types which itself contains a list of keys will not work, and the key values themselves can never be lists.
See examples below.

```graphql
type Query {
  # These are OK.
  goodQuery0(argument0: [String] @lookupKey, argument1: String): [SomeType] # Fields without key set will still be used in the query as usual.
  goodQuery1(argument: [In] @lookupKey): [SomeType] # In these cases key is applied to all fields in input type.
  goodQuery2(argument: [InKey]): [SomeType]
  goodQuery3(argument: [InKey] @lookupKey): [SomeType] # Double key does not matter.
  goodQuery4(argument: InList @lookupKey): [SomeType]
  goodQuery5(argument: InKeyList): [SomeType]
  goodQuery6(argument: InKeyList @lookupKey): [SomeType] # Double key does not matter

  goodQuery7(argument0: [String] @lookupKey, argument1: [Int] @lookupKey): [SomeType] # Can have as many keys as you want.
  goodQuery8(argument: [InNested] @lookupKey): [SomeType] # Input can be nested. Every field in there will be a key.
  goodQuery9(argument: [InNestedKey]): [SomeType]

  # These are not OK.
  badQuery0(argument: [InList] @lookupKey): [SomeType] # Two layers of lists.
  badQuery1(argument: [InKeyList]): [SomeType] # Two layers of lists.
}

input In {
  field0: String
  field1: Int
  field2: ID
}

input InList {
  field: [String]
}

input InKey {
  field: String @lookupKey
}

input InKeyList {
  field: [String] @lookupKey
}

input InNested {
  field: In
}

input InNestedKey {
  field: In @lookupKey # Every field in the input type becomes a key.
}
```

#### orderBy directive
Incorporating the **orderBy** functionality in your GraphQL schema allow API users to sort query results based on specific fields.

_Step 1: Define Order Input Types_

Firstly, define an input type for **orderBy**. This input type must include a `direction` field and a `orderByField` field:

```graphql
input FilmOrder {
    direction: OrderDirection!
    orderByField: FilmOrderByField!
}
```

The `OrderDirection` enum specifies the sort order:

```graphql
enum OrderDirection {
    ASC
    DESC
}
```

_Step 2: Define Order By fields_

Next, define the `OrderByField` enum. This should include all the fields on which sorting should be allowed.
Each of these fields must be backed by a database index to optimize the query performance.
The **index** directive indicates the corresponding database index:

```graphql
enum FilmOrderByField {
    LANGUAGE @index(name : "IDX_FK_LANGUAGE_ID")
    TITLE @index(name : "IDX_TITLE")
}
```

Note: A single OrderByField can involve more than one field in the database, e.g.: `STORE_ID_FILM_ID @index(name : "idx_store_id_film_id")`

To expose these indexes to Graphitron through jOOQ, ensure index code generation is enabled in jOOQ's generator config:

```xml
<database>
    <includeIndexes>true</includeIndexes>
    …
</database>
```

Graphitron will look for the indexes using their names as specified by the **index** directive.
Exceptions will be thrown if no matching index is found for the corresponding database table.

_Step 3: Add orderBy argument to Query_

Add the `orderBy` argument to your query. Use the **orderBy** directive to indicate that the input should be handled according to the _orderBy_-functionality:

```graphql
type Query {
    films(orderBy: FilmOrder @orderBy, first: Int = 100, after: String): FilmConnection
}
```

## Special interfaces
Currently, Graphitron reserves two interface names for special purposes, namely _Node_ and _Error_.

### Node
The _Node_ interface must contain an ID field. Any type that implements this interface will have a
_Node_ data fetcher generated.
This is designed to be compatible with [Global Object Identification](https://graphql.org/learn/global-object-identification/).

```graphql
interface Node {
  id: ID!
}
```

### Error
An interface used to enforce certain fields that use the **service** directive.

```graphql
interface Error {
  path: [String!]!
  message: String!
}
```

This may be removed/changed in the near future as it may not be flexible enough.

## Global node identification
If your schema includes [Global Object Identification](https://graphql.org/learn/global-object-identification/),
you need to have an instance of a class extending [NodeIdStrategy](https://github.com/sikt-no/graphitron/tree/main/graphitron-common/src/main/java/no/sikt/graphql/NodeIdStrategy.java), 
optionally overriding methods for custom ID encoding/decoding, and pass it to the wiring builder in order to resolve `node` queries on your server.

```java
@Singleton
public class MyNodeIdStrategy extends NodeIdStrategy {
  // Optionally override methods for custom ID encoding/decoding
}
```

Pass it to the runtime wiring:
```java
Graphitron.getRuntimeWiring(nodeIdStrategy);
```

### node directive
The **node** directive marks a type as globally identifiable by its ID, following your custom node ID strategy. To use this directive, the type must:

- Have the [table](#table-directive) directive.
- Implement the [Node](#node) interface.

The **node** directive supports two parameters for ID configuration:

- _typeId_: Sets the type identifier which is embedded in the ID and used to determine the correct type given an ID. Defaults to the GraphQL type name.
- _keyColumns_: The ordered table columns to include in the ID. If omitted, Graphitron uses the table's primary key.
  - Note: If the primary key changes, the generated ID will also change. Therefore, in order to ensure stable IDs, we recommend hard coding the primary key columns.

Given this schema:
```graphql
type Customer implements Node @node @table {
  id: ID!
}
```
Graphitron passes the default arguments to the _createId_ method in your node strategy:

```java
nodeIdStrategy.createId("Customer", CUSTOMER.getPrimaryKey().getFieldsArray())
```

And with a custom configuration like this:
```graphql
type Customer implements Node @node(typeId: "C", keyColumns: ["CUSTOMER_ID"]) @table {
  id: ID!
}
```

Graphitron will instead pass these arguments:

```java
nodeIdStrategy.createId("C", CUSTOMER.CUSTOMER_ID)
```

### nodeId directive
> **Note:** This directive is not currently supported when combined with [services](#services).

The **nodeId** directive can be placed on fields and arguments to indicate that they represent globally unique IDs, according to your node ID strategy. 
This directive requires one parameter:

- `typeName` — The name of the globally identifiable type the ID refers to.
  - This type must have the [**node**](#node-directive) directive.

For types with the **node** directive, the _id_ field implicitly has **nodeId**, so you do not need to add it. The following schemas are equivalent:

```graphql
type Customer implements Node @node @table {
  id: ID!
}
```
```graphql
type Customer implements Node @node @table {
  id: ID! @nodeId(typeName: "Customer")
}
```

#### Referencing another type's ID
It is possible to reference another type's node ID. This is done by combining the **nodeId** directive with the [reference](#reference-directive) directive.
Additionally, the _typeName_ may also imply a reference to another table when it doesn't match the field's containing type.

For example, if you have a `Customer` type that has a field referring to an `Address` ID, you can use the following schema:

```graphql
type Customer implements Node @node @table {
  id: ID!
  addressId: ID @nodeId(typeName: "Address") # Implicit reference to the 'ADDRESS' table
}

type Address implements Node @node @table {
  id: ID!
}
```

## Graphitron integration tests
For internal testing purposes Graphitron uses predefined input schemas combined with expected file results.
When altering the generator, these files likely have to be adjusted as well. These test schemas can also be read as
further examples on how to use the various directives.

Graphitron uses the [Sakila test database](https://www.jooq.org/sakila) to generate the jOOQ types needed for tests.
These are generated to `src/test/java` when running maven. These files are ignored by Git, and they are only generated
when they do not exist already or the property `testdata.schema.version` in _pom.xml_ is updated. In other words,
updating the property will refresh the jOOQ types.
This is typically only done when altering the database to add new tables or keys.
