package net.bloemsma.graphql.query

import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter

class TestSchema {

    private val originalSchema = """
                type Query {
                  result(count: Int): [result]
                }
        
                type result {
                  byte(plus: Byte, times: Byte): Byte
                  "calculate a number and inserts it into the template replacing {}"
                  string(plus: Int, times: Int, template: String): String
                  intArray(plus: Int, times: Int, count: Int, step: Int): [Int]
                }
                """
    val s = """
                  i: Int
                  f: Float
                  as: [String]
                  ar: [result]
"""
    val oldSchema: GraphQLSchema = SchemaGenerator().makeExecutableSchema(
        SchemaParser().parse(originalSchema),
        RuntimeWiring.newRuntimeWiring().codeRegistry(
            GraphQLCodeRegistry.newCodeRegistry()
                .dataFetcher(
                    FieldCoordinates.coordinates("Query", "result"),
                    DataFetcher { env ->
                        val count: Int = env.getArgument("count") ?: 10
                        (0..count - 1).toList()
                    })
                .dataFetcher(
                    FieldCoordinates.coordinates("result", "byte"),
                    DataFetcher { env ->
                        val plus: Byte = env.getArgument("plus") ?: 0
                        val times: Byte = env.getArgument("times") ?: 1
                        (env.getSource<Int>() * times + plus).toByte()
                    })
                .dataFetcher(
                    FieldCoordinates.coordinates("result", "string"),
                    DataFetcher { env ->
                        val plus: Int = env.getArgument("plus") ?: 0
                        val times: Int = env.getArgument("times") ?: 1
                        val template: String = env.getArgument("template") ?: "{}"
                        val num = (env.getSource<Int>() * times + plus)
                        template.replace("{}", num.toString())
                    })
                .dataFetcher(
                    FieldCoordinates.coordinates("result", "intArray"),
                    DataFetcher { env ->
                        val plus: Int = env.getArgument("plus") ?: 0
                        val times: Int = env.getArgument("times") ?: 1
                        val count: Int = env.getArgument("count") ?: 10
                        val step: Int = env.getArgument("step") ?: 1
                        val start = (env.getSource<Int>() * times + plus)
                        (0..count - 1).map { it * step + start }
                    })
        ).build()
    )

    val unfilteredResult = listOf(
        mapOf("x" to "wai", "y" to 3, "z" to listOf(mapOf("bar" to 5))),
        mapOf("x" to "iks", "y" to 5, "z" to listOf(mapOf("bar" to 6)))
    )

    val schemaPrinter = SchemaPrinter(
        SchemaPrinter.Options
            .defaultOptions()
            .includeDirectives(false)
            .includeIntrospectionTypes(false)
    )

    val graphQL: GraphQL = GraphQL
        .newGraphQL(oldSchema)
        .instrumentation(FilterInstrumentation(ops, "_filter", schemaPrinter))
        .build()

}