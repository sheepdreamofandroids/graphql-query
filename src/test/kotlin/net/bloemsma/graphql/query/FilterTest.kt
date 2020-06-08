package net.bloemsma.graphql.query

import assertk.assertions.hasSize
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

    @Test
    fun `fragment selection can be used to filter as long as the original name is used`() = """
        {
          result(count: 3, _filter: {byte: {lt: 1}}) {
            ...r
          }
        }

        fragment r on result {
          byte
        }
    """.trimIndent().check(testSchema.graphQL) {
        field<List<*>>("result") {
            hasSize(1)
            at(0) {
                field<Int>("byte") { equals(0) }
            }
        }
    }

}