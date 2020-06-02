package net.bloemsma.graphql.query

import assertk.assertions.hasSize
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import kotlin.test.Test

class HelloTest {

    val unfilteredResult = listOf(
        mapOf("x" to "wai", "y" to 3, "z" to listOf(mapOf("bar" to 5))),
        mapOf("x" to "iks", "y" to 5, "z" to listOf(mapOf("bar" to 6)))
    )
    val originalSchema = """
                type Query {
                  myresult(simpleArg: String): [result]
                }
        
                type result {
                  b: Byte
                  x: String
                  y: Int
                  z: [foo]
                }
                
                type foo {
                  bar: Int
                  }
                """
    val oldSchema = SchemaGenerator().makeExecutableSchema(
        SchemaParser().parse(originalSchema),
        RuntimeWiring.newRuntimeWiring().codeRegistry(
            GraphQLCodeRegistry.newCodeRegistry().dataFetcher(
                FieldCoordinates.coordinates("Query", "myresult"),
                DataFetcher { _ -> unfilteredResult })
        ).build()
    )

    val schemaPrinter = SchemaPrinter(
        SchemaPrinter.Options
            .defaultOptions()
            .includeDirectives(false)
            .includeIntrospectionTypes(false)
    )

    val graphQL = GraphQL.newGraphQL(oldSchema).instrumentation(
        FilterInstrumentation(
            ops, "_filter", SchemaPrinter(
                SchemaPrinter.Options.defaultOptions().includeDirectives(false).includeIntrospectionTypes(false)
            )
        )
    ).build()

    val executionResult = graphQL.execute(
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

    @Test
    fun `check filter`() = """ {
              myresult(_filter: { y: {gt: 3, lt: 9}}) {
                ixxi: x
                y
                z {bar}
              } 
            }""".check(graphQL) {
        println("checking $this")
        field<List<*>>("myresult") {
            println("checking list  $this")
            hasSize(1)
            at(0) {
                field<Int>("y") { equals(3) }
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
            }""".check(graphQL) {
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
//        println(schemaPrinter.print(oldSchema))
        println(executionResult.toSpecification())

    }
}

