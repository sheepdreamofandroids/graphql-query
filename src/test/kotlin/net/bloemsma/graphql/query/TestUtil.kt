package net.bloemsma.graphql.query

import graphql.ExecutionResult
import graphql.GraphQL
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf

fun ExecutionResult.check(body: Result.() -> Unit) = this.getData<Result>().body()

fun String.check(graphQL: GraphQL, body: Result.() -> Unit) =
    graphQL.execute(this).getData<Result>().body()

fun <T : Any> List<T?>?.at(i: Int, body: T.() -> Unit) {
    this.shouldNotBeNull()
    val element = this[i]
    element.shouldNotBeNull()
    element.body()
}

inline fun <reified T : Any> Any?.field(s: String, body: T.() -> Unit) {
    this.shouldNotBeNull()
    val field = getField(s)
    field.shouldNotBeNull()
    field.shouldBeInstanceOf<T> { it.body() }
}
