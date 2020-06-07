package net.bloemsma.graphql.query

import assertk.assertions.hasSize
import graphql.ExecutionInput
import graphql.ExecutionResult
import kotlin.test.Test

class FilterTest {

    val testSchema = TestSchema()

    @Test
    fun `check filter`() = "{result(count: 2, _filter: {byte: {lt: 1}}) { byte }}"
        .check(testSchema.graphQL) {
            field<List<*>>("result") {
                hasSize(1)
                at(0) {
                    field<Int>("byte") { equals(0) }
                }
            }
        }


    @Test
    fun `check negation`() = "{result(count: 2, _filter: {byte: {not: {lt: 1}}}) { byte }}"
        .check(testSchema.graphQL) {
        field<List<*>>("result") {
            hasSize(1)
            at(0) {
                field<Int>("byte") { equals(1) }
            }
        }
    }
}

