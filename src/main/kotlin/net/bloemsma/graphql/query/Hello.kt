package net.bloemsma.graphql.query

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.Scalars.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass

fun main() {
    val oldSchema = SchemaGenerator().makeExecutableSchema(
        SchemaParser().parse(
            """
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
        ),
        RuntimeWiring.newRuntimeWiring().codeRegistry(
            GraphQLCodeRegistry.newCodeRegistry().dataFetcher(
                FieldCoordinates.coordinates("Query", "myresult"),
                DataFetcher { _ ->
                    listOf(
                        mapOf("x" to "wai", "y" to 3, "z" to listOf(mapOf("bar" to 5))),
                        mapOf("x" to "iks", "y" to 5, "z" to listOf(mapOf("bar" to 6)))
                    )
                })
        ).build()
    )

    val schemaPrinter =
        SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeDirectives(false).includeIntrospectionTypes(false))
    println(schemaPrinter.print(oldSchema))

//    val transformedSchema =
//        SchemaTransformer.transformSchema(oldSchema, AddQueryToSchema(ops))
//    println("=========================")
//    println(schemaPrinter.print(transformedSchema))

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
              myresult(_filter: {x: {eq: "x"}, y: {gt: 3, lt: 9}}) {
                ixxi: x
                y
                z {bar}
              } 
            }""".trimMargin()
        )
    )
    println(executionResult.toSpecification())
}
