package net.bloemsma.graphql.query

import graphql.language.Value

typealias Variables = Map<String, *>
/** The result of a query before filtering */
typealias Result = Any
/** The query is represented as a tree of Value */
typealias Query = Value<*>

/** A function that is executed at query time */
typealias QueryFunction<R> = (Result?, Variables) -> R
typealias QueryPredicate = QueryFunction<Boolean>
// modifying the query result is destructive
typealias ResultModifier = QueryFunction<Unit>

