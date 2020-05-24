package net.bloemsma.graphql.query

import graphql.language.Value

typealias Variables = Map<String, *>
/** The result of a query before filtering */
typealias Result = Any
/** The query is represented as a tree of Value */
typealias Query = Value<*>


// modifying the query result is destructive
typealias  ResultModifier = (Result, Variables) -> Unit
typealias  XPredicate = (Result) -> Boolean

typealias QueryPredicate = QueryFunction<Boolean>
typealias QueryFunction<R> = (Result, Variables) -> R
//interface QueryTimeFunction<R> : Function2<Any, Variables, R>
//typealias QueryTimePredicate = (Any, Variables) -> Boolean
// calculates a modifier from a query
//typealias FilterParser = (Value<*>) -> ResultModifier

