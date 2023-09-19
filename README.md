# Graphitron - GraphQL resolver implementation generator 
Kodegeneratoren benytter seg av annoterte GraphQL-skjema for å ferdigstille/generere resolverimplementasjoner som blir
brukt til å besvare forespørsler fra klienter.

## Skjema
Annoteringen skjer via direktiver direkte i GraphQL-skjemaet. Direktivene må legges inn i skjemaet som Graphitron kjøres
mot, per default er dette i _schema_ i modulen _fs-graphql-spec_ under _resources/schema_. I schema er direktivene og deres
parametre beskrevet med kommentarer. Ytterligere forklaring med enkelte eksempler finnes nedenfor i denne readme-fila.

De viktigste direktivene er _splitQuery_, _notGenerated_ og _table_. 

**SplitQuery**-direktivet forteller maven-pluginen (io.github.kobylynskyi) at den skal lage et interface for en
resolver-metode på feltet der direktivet er plassert. 

**NotGenerated** settes på et felt for å instruere generatoren om å IKKE generere noen metode for dette feltet.
Å sette _splitQuery_ i kombinasjon med _notGenerated_ gir oppførsel slik den hadde vært uten Graphitron,
altså at det blir generert et interface som må implementeres.

**Table** knytter et GraphQL-objekt til en tabell i KjerneAPI. Dersom objektet har et annet navn enn tabellen i KjerneAPI,
brukes _name_-parameteret for å angi navnet på tabellen det skal knyttes mot.
Objekt som i tillegg implementerer node-interfacet får også en implementasjon for dette. Alle slike noder kan hentes ut basert på ID i API-et, ved
hjelp av node-resolveren. Dette er i tråd med GraphQLs mønster for [Global Object Identification](https://graphql.org/learn/global-object-identification/).

## Bygg
Generatoren er satt opp for å bygges via vanlig maven pipeline, og kan også kjøres via main-metoden i
[GraphQLGenerator](src/main/java/no/fellesstudentsystem/graphitron/GraphQLGenerator.java).
For å bygge via maven er det bare å kjøre `mvn clean install` i enten rotmappen til prosjektet eller generatorens rotmappe.
Merk at hvis det skal kjøres fra generatorens rotmappe, må man forsikre seg om at både **KjerneAPI**-tabellene og **GraphQL**-objektene er bygget først.

## Arv og manuell resolverimplementasjon
De genererte klassene blir abstrakte dersom en eller flere metoder ikke er implementert (_notGenerated_ er i bruk),
klassene må da utvides med manuelle implementasjoner. Det er dermed også mulig å overskrive genererte metoder om det 
skulle være ønskelig, for eksempel i forbindelse med testing. Merk at man ikke kan overskrive en klasse der alt er 
generert siden den ikke er abstrakt. GraphQL vil da klage på at det finnes to resolvere for samme forbindelse.

## Funksjonalitet og direktiver for Queries
### Tables & Joins
#### Automatic reference detection
Generatoren klarer å utlede mange av databaseforbindelsene via typene i skjemaet som er annotert med _table_-direktivet.
Ved bruk av reflection på koden generert av jOOQ i KjerneAPI, sjekkes det både for implisitte joins og eventuelle `get(Tabellnavn)Id`-metoder
som peker til måltabellen.

#### Manually defined references
Det er likevel tilfeller i resolverkoden der det ikke er så enkelt å dedusere hvordan koblingen skal være.
For slike tilfeller har vi direktivet **reference**. Det tar inn følgende parametere:

* _table_ - Overskriver tabellen i det som refereres til. Lite brukt akkurat nå.
* _key_ - Hvis tabellen har mer enn en FK til det som refereres til, må den oppgis her. Da blir denne nøkkelen brukt for å lage koblingen.
* _condition_ - Hvis man ønsker å begrense resultatet videre kan man oppgi en condition her. Det må være et innslag i enumen
[GeneratorCondition](../kjerneapi/src/main/java/no/fellesstudentsystem/kjerneapi/conditions/GeneratorCondition.java).
Hvis det ikke går å utlede en kobling, og _key_ ikke er oppgitt, blir dette tolket som en condition for bruk i en join-operasjon 
for å få til koblingen. Det blir til en left join hvis feltet ikke er påkrevd.

Merk at ett join-steg kun gjelder for feltet det er satt på.
En type kan ha flere joins definert på samme tabell for forskjellige felt, ettersom joins alltid blir gjort via alias i den genererte koden.
Hvis et felt peker til en annen type, vil alle felt i det refererte typen bli omfattet av join-operasjonen.

### Field Mapping
For alle felter som ikke er markert med _notGenerated_ forsøker generatoren å linke spesifikasjonen til korrekt SQL mot KjerneAPI.
Selve databasefeltene blir antatt å hete det samme som typene i skjemaet, med unntak av når de blir overskrevet av **column**-direktivet.
_Column_-direktivet har også et par ekstra parametere for spesialtilfeller:

* _table_ - Overskriver hvilken tabell dette feltet hentes fra.
Kan være enklere å oppgi en en referanse hvis bare det ene feltet skal hentes derfra.
* _key_ - Hvis _table_ er oppgitt brukes denne nøkkelen til å finne koblingen mellom de to tabellene.

Felt som har _splitQuery_-direktivet blir ikke tatt med, siden det genereres egne resolvere for disse.

### Conditions
Direktivet **condition** muliggjør mer avanserte begrensninger for spørringen.
Det kan settes på input-parametere eller for hele feltet som har input parametere.
Følgende parametere er tilgjengelige:

* _name_ - Navnet på en condition definert i KjerneAPI. Det må være et innslag i enumen
  [GeneratorCondition](../kjerneapi/src/main/java/no/fellesstudentsystem/kjerneapi/conditions/GeneratorCondition.java).
* _override_ - Skal det forhindres at sjekkene som blir lagt til automatisk tas med?
Hvis ja, blir kun den oppgitte condition med, ellers legges conditionen på som et tillegg til de øvrige sjekkene. Se eksemplene nedenfor.

#### Case: No _override_ on input parameter
Legg til denne conditionen i tillegg til de sjekkene som vanligvis legges på.
Til metoden sendes den aktuelle tabellen samt denne parameteren.
  
Eksempel:
```graphql
cityNames: [String!] @column(name: "CITY") @condition(name: "TEST_CITY_NAMES")
```
```java
TEST_CITY_NAMES(CityTestConditions.class, "cityNames", City.class, List.class)
```
Resultat:
```java
.and(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : noCondition())
.and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityNames(CITY, cityNames))
```
#### Case: No _override_ on field with input parameters
Legg til denne conditionen i tillegg til de sjekkene som vanligvis legges på.
Til metoden sendes den aktuelle tabellen og alle parametere til dette feltet.

Eksempel:
```graphql
fieldCondition(
    countryId: String! @column(name: "COUNTRY_ID"),
    cityNames: [String!] @column(name: "CITY")
): [City] @condition(name: "TEST_CITY_ALL")
```
```java
TEST_CITY_ALL(CityTestConditions.class, "cityAll", City.class, String.class, List.class)
```
Resultat:
```java
.where(CITY.COUNTRY_ID.eq(countryId))
.and(cityNames != null && cityNames.size() > 0 ? CITY.CITY.in(cityNames) : noCondition())
.and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityAll(CITY, countryId, cityNames))
```
#### Case: Both field and parameters
Summen av punktene over. Resultatet blir at begge conditionene tas med.
#### Case: With _override_ on input parameter
Bytt ut de sjekkene som vanligvis hadde blitt satt for denne parameteren med denne conditionen.
Til metoden sendes den aktuelle tabellen samt denne parameteren.

Eksempel:
```graphql
cityNames: [String!] @column(name: "CITY") @condition(name: "TEST_CITY_NAMES", override: true)
```
```java
TEST_CITY_NAMES(CityTestConditions.class, "cityNames", City.class, List.class)
```
Resultat:
```java
.and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityNames(CITY, cityNames))
```
#### Case: With _override_ on field with input parameters
Bytt ut de sjekkene som vanligvis hadde blitt satt for alle parameterne til feltet med denne conditionen.
Til metoden sendes den aktuelle tabellen og alle parametere til dette feltet.

Eksempel:
```graphql
cities(
    countryId: String! @column(name: "COUNTRY_ID"),
    cityNames: [String!] @column(name: "CITY")
): [City] @condition(name: "TEST_CITY_ALL", override: true)
```
```java
TEST_CITY_ALL(CityTestConditions.class, "cityAll", City.class, String.class, List.class)
```
Resultat:
```java
.where(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityAll(CITY, countryId, cityNames))
```
#### Case: With _override_ on both field and parameters
Både conditions satt på feltet og på parametere blir med, men ingenting annet. 
Merk at hvis _override_ er satt på en condition på feltet, har ikke verdien av _override_ på parameterene noen effekt.

Eksempel:
```graphql
cities(
    countryId: String! @column(name: "COUNTRY_ID"),
    cityNames: [String!] @column(name: "CITY") @condition(name: "TEST_CITY_NAMES", override: true)
): [City] @condition(name: "TEST_CITY_ALL", override: true)
```
```java
TEST_CITY_NAMES(CityTestConditions.class, "cityNames", City.class, List.class),
TEST_CITY_ALL(CityTestConditions.class, "cityAll", City.class, String.class, List.class)
```
Resultat:
```java
.where(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityNames(CITY, cityNames))
 and(no.fellesstudentsystem.graphitron.conditions.CityTestConditions.cityAll(CITY, countryId, cityNames))
```

## Functionality & Directives for Mutations
### Services
Mutasjoner må knyttes til java-metoder via service-klassene definert i
[GeneratorService](../kjerneapi-service/src/main/java/no/fellesstudentsystem/codegenenums/GeneratorService.java).
Dette gjøres ved hjelp av **service**-direktivet. Metoden må ha samme navn og samme antall parametre som mutasjonsfeltet.
Det er nødvendig å sette på _notGenerated_ hvis _service_ ikke er satt.

Siden service peker på klassen, finnes rett metode via reflection. Det betyr i praksis at i den oppgitte servicen,
må det finnes en metode med samme navn som mutation-feltet, og som tar likt antall parametere. Gitt at man ikke kan ha
to felt med samme navn i en type, leter generatoren bare etter navn og rett antall parametere, og sjekker ikke typene til parameterene.
I tillegg finnes det flere varianter av hva man kan returnere. Generatoren sammenligner typen som metoden returnerer og
schema for å finne en vei til det ønskede resultatet.

* Hvis mutation returnerer en type som implementerer node, og selve metoden returnerer en record, bruker den _ID_ fra record
for å slå opp og returnere typen via node-interfacet.
* Hvis mutation returnerer en skalar, antas det at metoden også returnerer en skalar og denne returneres.
Det kan typisk være ID, men andre typer kan også gå. Merk at dette er ikke testet for andre typer enn _String_ og _ID_.
* Hvis mutation returnerer en type som ikke implementerer node, må servicen returnere en instans av en klasse som gir ut feltene i typen.
Merk at klassen antas å være en statisk klasse inni selve service klassen, og generatoren kan ikke finne den hvis den plasseres et annet sted akkurat nå.
Videre antas det at det finnes get-metoder for hvert felt.
Navngivingen antas å være lik mellom objektet og GraphQL-typen hvis ikke _column_ benyttes, se neste delkapittel.

Noen ekstra ting å merke seg:

* Union typer støttes ikke på returtype. Dette kommer snart.
* Alle typene nevnt i punktene over kan pakkes inn i listevarianter.
* Selection sets blir benyttet som vanlig ved henting via node-interfacet.
* Hvis feltet i returtypen returnerer node, blir bare ID brukt for oppslag i node-interface for det objektet.

#### Nested Structures
Ved nøstede strukturer, altså typer i typer, støttes på input og output til en viss grad, men har en praktisk begrensing.
Alle record-objektene som skal til service blir til ett flatt sett med parametere. Strukturene kan være pakket inn i lister avhengig av hva
som er definert i skjemaet. Denne begrensningen kan gjøre det vanskeligere å vite hvilke data fra ett lag som hører til
hvilke i et annet. Derfor er ikke dyp nøsting av input anbefalt for bruk med generatoren akkurat nå. Dette kan endre seg når/hvis
vi finner et bedre design for dette. Begrensningen for input er illustrert under.

```graphql
edit(input: InputA!): ID! @service(name: "SERVICE")

input InputA @record {
  b: InputB
}

input InputB @record { }
```
```java
var endreNoeResult = service.edit(inputARecord, inputBRecord); // Sekvensielt uavhengig av struktur.
```

Dette blir fort litt mindre oversiktelig hvis disse inputene blir pakket inn i lister, fordi da sendes det også inn helt likt som vist over.
Sammenhengene mellom lagene i skjemaet vil bli vanskeligere å finne fram igjen i servicen.

Returtypen støtter derimot nøsting på en litt mer ryddig måte, der returobjektet må ha tilsvarende klassestruktur som skjemaet.
Regelen her er at for hver type som brukes i returtypen i skjemaet, må det finnes en klasse i servicen.
Hvis en type har en annen type inni seg, må klassen også levere ut en et objekt via en get-metode som tilsvarer den indre typen.
Se eksemplet under på hvordan det ser ut i praksis.

```graphql
edit(id: ID!): ReturnA! @service(name: "SERVICE")

type ReturnA {
  returnB: ReturnB
}

type ReturnB { }
```
```java
public class TestCustomerService {

  public ReturnA edit(String id) { }

  public static class ReturnA {
    public ReturnB getReturnB() { } // Må ha en metode som gir ut noe som kan mappes til den indre typen ReturnB.
  }

  public static class ReturnB { }
}
```

### Field Mapping
På grunn av at node interfacet krever at typer tar med ID-felt, introduseres det et nytt direktiv for å mappe input-typer
opp mot jOOQ records. Direktivet **record** anvendes helt likt som _table_, men kan bare settes på input-typer.
Hvis input-typen blir brukt i en _Query_-spørring, vil ikke direktivet ha noen effekt. Direktivet _column_
(skal endre navn senere) gjenbrukes her for å koble enkeltfelt opp til jOOQ-navn, slik at records kan konstrueres.
For returtyper som ikke implementerer node, brukes også _column_ for å mappe navnene på feltene i returtypen mot
navnene i returobjektet i servicen.
Hvis navnet i skjema er likt som navnet i jOOQ, trenger man som tidligere ikke oppgi direktivet.
Dette gjelder både _record_- og _column_-direktivene.

### Example
Hvis vi har dette skjemaet:

```graphql
editCustomerAdresse(
  id: ID!
  input: EditAdresseInput!
): EditPersonAdresseRespons! @service(name: "SERVICE_PERSONPROFIL") # Kunne vært PersonProfil eller ID også.

input EditPersonProfilFolkeregistrertAdresseInput @record(table: "PERSON") { # Angir hvilken record som skal mappes til.
  co: String! @column(name: "ADRLIN1_HJEMSTED") # Angir hvilket felt i record som skal mappes til.
  gate: String! @column(name: "ADRLIN2_HJEMSTED")
  postnummerOgSted: String! @column(name: "ADRLIN3_HJEMSTED")
  land: String! @column(name: "ADRESSELAND_HJEMSTED")
}

type EditPersonAdresseRespons {
  personProfil: PersonProfil # legg på @column(name: "person") hvis dette skal mappes "person" i returklassen. Da må metoden hete "getPerson" i stedet.
}
```

Trenger vi et service som har en av følgende signaturer:

```java
// Hvor EditPersonAdresseRespons er en instans av en statisk klasse inni servicen som inneholder en PersonRecord, og som har en metode som heter "getPersonProfil".
public EditPersonAdresseRespons editCustomerAdresse(String id, PersonRecord person)

// Den returnerte recorden her trenger bare ID satt, siden det er bare den som blir brukt når objektet hentes i resolveren.
public PersonRecord editCustomerAdresse(String id, PersonRecord person)
```

Resolveren hadde blitt tilpasset automatisk om mutation returnerte en _PersonProfil_ i stedet for en _EditPersonAdresseRespons_.
Dette er uavhengig av hvordan selve service-metoden er satt opp.
I dette tilfellet er det anbefalt å heller inkludere ID inni input-typen, ellers er den ikke satt på recorden når den kommer inn i servicen.
Det vil virke uansett, men hvis det er satt opp slik som dette vil sannsynligvis det første som skjer inni servicen være å sette ID-en på record-en.
Det er også uheldig å ha ID-en slik fordi det er ikke like klart hvilket KjerneAPI-view ID-en hører til.
Dette er likevel tillatt for å ikke å kreve endring på eksisterende skjema for at det skal virke.

### Error Handling
En feiltype er definert ved at den implementerer _Error_-interfacet og oppgir _error_-direktivet. Unioner av slike er også tilatte.
Feil til direktivet oppgis på samme måte som til conditions og enums, bare fra enumen
[GeneratorException](../kjerneapi-service/src/main/java/no/fellesstudentsystem/codegenenums/GeneratorException.java).
Når en respons spesifiserer en type som innheholder felt som er av en _Error_-type, vil Graphitron automatisk fange opp dette.
Akkurat nå brukes try-catch for å fange opp feil, som betyr at vi får bare ut maks en feilmelding per servicekall. Dette kan endre seg i framtiden.
Feil mappes til rett GraphQL-type og plasseres i responsen slik som man skulle forvente.

En spørring med feil kan se slik ut:

```graphql
type Mutation {
  editCustomer(id: ID!): EditPersonPayload! @service(name: "SERVICE_PERSON")
}

type EditPersonPayload {
  id: ID!
  errors: [SomeError!]!
}

type SomeError implements Error @error(name: "EXCEPTION_ULOVLIG") {
  path: [String!]!
  message: String!
}
```

Vi forventer da at noe som dette finnes i _GeneratorException_ enumen:

```java
EXCEPTION_ULOVLIG(UlovligException.class)
```

Her er `UlovligException.class` en exception-klasse som vi forventer at kan kastes fra det som _SERVICE_PERSON_ peker til.
Deretter burde vi se at Graphitron produserer noe som dette:

```java
try {
    editCustomerResult = personService.editCustomer(id);
} catch (UlovligException e) {
    var error = new SomeError();
    error.setMessage(e.getMessage());
    var cause = e.getCauseField();
    var causeName = Map.of().getOrDefault(cause != null ? cause : "", "undefined");
    error.setPath(List.of(("Mutation.editCustomer." + causeName).split("\\.")));
    editCustomerErrorsList.add(error);
}
```

Informasjonen om "cause", altså feltet som forårsakert feilen, brukes bare hvis feilen har den hardkodede metoden som heter _getCauseField_.
Dette kan endre seg til å bli mer intuitivt i fremtiden. Når det finnes et felt som har skyld i feilen, vil map-en i eksemplet over
inneholde alle felt som ble funnet i responsen, slik at de kan mappes ut fra databasefelt til felt i skjemaet.

Hvis der er flere mulige feil ved at det er flere feil-felt eller det oppgis unioner av feiltyper, vil det bli flere _catch_-blokker, en per feiltype.

## Testing
Testene for modulen sjekker det genererte resultatet opp mot predefinerte filer.
Derfor må man også ofte tilpasse testene når man gjør endringer i generatoren som påvirker den genererte koden.

Testene kjøres mot genererte JOOQ-klasser basert på [Sakila test-databasen](https://www.jooq.org/sakila).
JOOQ-klassene blir generert i mapppen `src/test/java` ved kjøring via mvn. Denne mappen er ignorert i _.gitignore_ og filene
genereres kun dersom de ikke eksisterer eller dersom man trenger å få dem regenerert.
Regenerering tvinges fram ved å endre skjemaversjon via propertien `testdata.schema.version` i _pom.xml_.

Testene har separate GraphQL-skjema for å være uavhengige av endringer i prosjektets skjema, og ved endringer i direktivene må disse også oppdateres.
