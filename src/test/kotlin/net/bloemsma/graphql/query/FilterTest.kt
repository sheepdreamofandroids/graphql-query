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
            println("checking $this")
            field<List<*>>("result") {
                println("checking list  $this")
                hasSize(1)
                at(0) {
                    field<Int>("b") { equals(0) }
                }
            }
        }


    @Test
    fun `check negation`() = """ {
              myresult(_filter: { y: {not: {gt: 3, lt: 9}}}) {
                ixxi: x
                y
                z {bar}
              } 
            }""".check(testSchema.graphQL) {
        println("checking $this")
        field<List<*>>("myresult") {
            println("checking list  $this")
            hasSize(1)
            at(0) {
                field<Int>("y") { equals(5) }
            }
        }
    }


    @Test
    fun `just printing`() {
        val executionResult: ExecutionResult = testSchema.graphQL.execute(
            ExecutionInput.newExecutionInput(
                """
            {
              myresult(_filter: { y: {gt: 3, lt: 9}}) {
                ixxi: x
                y
                z {bar}
              } 
            }""".trimMargin()
            )
        )
        println(executionResult.toSpecification())

    }
}

