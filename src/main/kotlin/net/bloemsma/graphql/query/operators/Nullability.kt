package net.bloemsma.graphql.query.operators

import graphql.Scalars.GraphQLBoolean
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLNullableType
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.GraphQlQueryException
import net.bloemsma.graphql.query.Operator
import net.bloemsma.graphql.query.OperatorProducer
import net.bloemsma.graphql.query.OperatorRegistry
import net.bloemsma.graphql.query.Query
import net.bloemsma.graphql.query.QueryFunction
import net.bloemsma.graphql.query.Result
import net.bloemsma.graphql.query.SchemaFunction
import net.bloemsma.graphql.query.SuperSimpleOperator
import net.bloemsma.graphql.query.Variables
import net.bloemsma.graphql.query.asBoolean
import net.bloemsma.graphql.query.showingAs
import kotlin.reflect.KClass

class Nullability : OperatorProducer {
    override fun <R : Any> produce(
        resultType: KClass<R>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<R>> =
        if (contextType is GraphQLNullableType && resultType == Boolean::class) {
            // usually stuff is nullable and you can test for it
            listOf(
                object : SuperSimpleOperator<R>(
                    name = "isNull",
                    parameterType = GraphQLBoolean,
                    description = "Tests whether this field is null."
                ) {
                    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
                        true

                    override fun compile(
                        param: Query,
                        schemaFunction: SchemaFunction<R>,
                        context: GraphQLOutputType
                    ): QueryFunction<R>? =
                        { c: Result?, v: Variables ->
                            val shouldBeNull: Boolean =
                                param.asBoolean() ?: throw GraphQlQueryException("should not be possible", null)
                            (c == null) == shouldBeNull
                        }.showingAs { "isNull ${param.asBoolean()}" } as QueryFunction<R>
                }
            )
        } else if (contextType is GraphQLNonNull) {
            // sometimes it is not nullable and then you can do everything you can with nullable types except test for null
            // Other operator producers don't match with GraphQLNonNull so we make them match with the wrapped type here
            val innerType = contextType.wrappedType as GraphQLOutputType
            operatorRegistry.applicableTo(resultType, innerType) { it !is Nullability }
        } else
            emptyList()

}