package net.bloemsma.graphql.query

import assertk.Assert
import assertk.all
import assertk.assertThat
import assertk.assertions.isNotNull
import graphql.ExecutionResult

fun ExecutionResult.check(body: Assert<Result>.() -> Unit) = assertThat ( this.getData<Result>() ).body()

fun <T : Any> Assert<List<T?>?>.at(i: Int, body: Assert<T>.() -> Unit) =
    isNotNull().transform { it[i] }.isNotNull().all(body)

fun <T : Any> Assert<Any?>.field(s: String, body: Assert<T>.() -> Unit) =
    isNotNull().transform { it.getField(s) as T? }.isNotNull().all(body)
