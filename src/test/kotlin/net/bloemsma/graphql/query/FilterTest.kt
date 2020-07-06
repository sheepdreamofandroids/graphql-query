package net.bloemsma.graphql.query

import assertk.assertions.hasSize
import org.junit.Test

class FilterTest {

    @Test
    fun `check filter`() {
        "{result(count: 2, _filter: {int: {lt: 1}}) { int }}"
            .check(testSchema.graphQL) {
                field<List<*>>("result") {
                    hasSize(1)
                    at(0) {
                        field<Int>("int") { equals(0) }
                    }
                }
            }
    }

    @Test
    fun `check negation`() {
        "{result(count: 2, _filter: {int: {not: {lt: 1}}}) { int }}"
            .check(testSchema.graphQL) {
                field<List<*>>("result") {
                    hasSize(1)
                    at(0) {
                        field<Int>("int") { equals(1) }
                    }
                }
            }
    }

    @Test
    fun `fragment selection can be used to filter as long as the original name is used`() {
        """
        {
          result(count: 3, _filter: {int: {lt: 1}}) {
            ...r
          }
        }

        fragment r on result {
          int
        }
    """.trimIndent().check(testSchema.graphQL) {
            field<List<*>>("result") {
                hasSize(1)
                at(0) {
                    field<Int>("int") { equals(0) }
                }
            }
        }

    }

    @Test
    fun `list of nonnull can be tested`() = """
         {
          result(count: 6, _filter:{nonNullStringArray:{_ANY:{lt:"3"}}}) {
            int
            nonNullStringArray(count:3)
          }
        }
    """.check(testSchema.graphQL) {
        field<List<*>>("result") {
            hasSize(3)
            at(2) {
                field<Int>("int") { equals(2) }
            }
        }
    }
}

object testSchema : TestSchema()
