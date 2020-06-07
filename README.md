# GraphQL-Query
Instrument a graphql-java server with filters on lists

This is an extension to graphql-java that adds a parameter to each list field that allows to filter the elements of that list using a simple predicate.

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

See in the file "TestSchema.kt" how to add FilterInstrumentation to your GraphQL object.
