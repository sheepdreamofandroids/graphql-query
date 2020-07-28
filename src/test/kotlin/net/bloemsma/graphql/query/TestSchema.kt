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
import graphql.schema.idl.TypeDefinitionRegistry

/** A schema with datafetchers that can be used to easily create all kinds of test scenarios */
open class TestSchema(
    val originalSchema: String = """
                type Query {
                  result(count: Int): [result!]
                  nullableResult(count: Int, nullEvery:Int): [result]
                }
        
                type result {
                  int(plus: Int, times: Int): Int
                  "calculate a number and inserts it into the template replacing {}"
                  string(plus: Int, times: Int, template: String): String
                  intArray(plus: Int, times: Int, count: Int, step: Int): [Int]
                  nonNullStringArray(plus: Int, times: Int, count: Int, step: Int, template: String): [String!]
                  nullableResults(count:Int, nullEvery:Int): [result]
                  nonNullableResults(count:Int): [result!]
                }
                """,
    val types: TypeDefinitionRegistry = SchemaParser().parse(originalSchema)
) {
    val oldSchema: GraphQLSchema = SchemaGenerator().makeExecutableSchema(
        types,
        RuntimeWiring.newRuntimeWiring().codeRegistry(
            GraphQLCodeRegistry.newCodeRegistry()
                .dataFetcher(
                    FieldCoordinates.coordinates("Query", "result"),
                    DataFetcher { env ->
                        val count: Int = env.getArgument("count") ?: 10
                        (0..count - 1).toList()
                    })
                .dataFetcher(
                    FieldCoordinates.coordinates("Query", "nullableResult"),
                    DataFetcher { env ->
                        val count: Int = env.getArgument("count") ?: 10
                        val nullEvery: Int = env.getArgument("nullEvery") ?: 3
                        (0..count - 1).map { if (it % nullEvery == 0) null else it }
                    })
                .dataFetcher(
                    FieldCoordinates.coordinates("result", "int"),
                    DataFetcher { env ->
                        val plus: Int = env.getArgument("plus") ?: 0
                        val times: Int = env.getArgument("times") ?: 1
                        (env.getSource<Int>() * times + plus).toInt()
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
                .dataFetcher(
                    FieldCoordinates.coordinates("result", "nonNullStringArray"),
                    DataFetcher { env ->
                        val plus: Int = env.getArgument("plus") ?: 0
                        val times: Int = env.getArgument("times") ?: 1
                        val count: Int = env.getArgument("count") ?: 10
                        val step: Int = env.getArgument("step") ?: 1
                        val template: String = env.getArgument("template") ?: "{}"
                        val start = (env.getSource<Int>() * times + plus)
                        (0..count - 1).map { template.replace("{}", (it * step + start).toString()) }
                    })
                .dataFetcher(
                    FieldCoordinates.coordinates("result", "nullableResults"),
                    DataFetcher { env ->
                        val count: Int = env.getArgument("count") ?: 10
                        val nullEvery: Int = env.getArgument("nullEvery") ?: 3
                        (0..count - 1).map { if (it % nullEvery == 0) null else it }
                    })
                .dataFetcher(
                    FieldCoordinates.coordinates("result", "nonNullableResults"),
                    DataFetcher { env ->
                        val count: Int = env.getArgument("count") ?: 10
                        (0..count - 1).toList()
                    })
        ).build()
    )

    val schemaPrinter = SchemaPrinter(
        SchemaPrinter.Options
            .defaultOptions()
            .includeDirectives(false)
            .includeIntrospectionTypes(false)
    )

    val graphQL: GraphQL = GraphQL
        .newGraphQL(oldSchema)
        .instrumentation(FilterInstrumentation())
        .build()

}