package net.bloemsma.graphql.query

import assertk.assertions.hasSize
import io.kotest.core.spec.style.FreeSpec

class FilterTest : FreeSpec({

    "check filter" {
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

    "check negation" {
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

    "fragment selection can be used to filter as long as the original name is used" {
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
})

object testSchema : TestSchema()
