![Build master](https://github.com/sheepdreamofandroids/graphql-query/workflows/Build%20master/badge.svg)
# GraphQL-Query
Instrument a graphql-java server with filters on lists

This is an extension to the excellent [graphql-java](https://github.com/graphql-java/graphql-java) that adds a parameter to each list field that allows to filter the elements of that list using a simple predicate.

Given this schema:
```
type Query {
  result: [result]
}

type result {
  int: Int
  string: String
}
```
You could write the query:
```
{
  result(_filter: {
      int: {gt: 15),
      string: {eq: "hello"}
  }) {
    int
    string
  }
}
```
This would retrieve the list of results as usual and then remove everything where int is not greater than 15 or string not equal to "hello". 

See in the file "TestSchema.kt" how to add FilterInstrumentation to your GraphQL object:
```kotlin
val graphQL: GraphQL = GraphQL
    .newGraphQL(oldSchema)
    .instrumentation(FilterInstrumentation(ops, "_filter", schemaPrinter))
    .build()
```

## Limitations
- Fields used in the _filter argument have to be selected, either directly or using a fragment, using their original name. Otherwise the data to test on is simply not found.
- No calculations can be done, only comparisons of fields against literals.
- There are very few operators.
- No aggregations.
- No pagination.
- The code needs some serious cleanup!
- It should be easier to add new operators.
- NOT ready for production.