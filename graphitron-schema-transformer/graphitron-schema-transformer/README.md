# Schema Transformer

Denne modulen brukes for å skrive om skjemaet vårt for å håndtere vanlig boilerplate, for eksempel Relay Connection-greier.

## Directives

Direktivene for denne modulen skal følge navnekonvensjonen "asX" hvor X er et navn som indikerer ønsket transformasjon.

#### asConnection

Relay Connections har mye boilerplate, derfor har vi laget en liten makro som ekspanderer til det vi vanligvis trenger.

Direktivet er definert i [directives.graphqls](src%2Fmain%2Fresources%2Fschema%2Fdirectives.graphqls) og brukes slik:

```graphql
interface Node { id: ID! }

type Query {
    someType(
        param: String!
    ): [SomeType] @asConnection
}

type SomeType implements Node {
    id: ID!
    field: String!
}
```

Da genereres følgende skjema:

```graphql
interface Node {
    id: ID!
}

type PageInfo {
    hasPreviousPage: Boolean!
    hasNextPage: Boolean!
    startCursor: String
    endCursor: String
}

type Query {
    someType(param: String!, first: Int = 100, after: String): QuerySomeTypeConnection
}

type QuerySomeTypeConnection {
    edges: [QuerySomeTypeConnectionEdge]
    pageInfo: PageInfo
    nodes: [SomeType]
    totalCount: Int
}

type QuerySomeTypeConnectionEdge {
    cursor: String
    node: SomeType
}

type SomeType implements Node {
    id: ID!
    field: String!
}
```

Dersom man ønsker å generere en annen default-verdi for `first`, så kan man bruke `defaultFirstValue`-argumentet til `@asConnection`-direktivet.