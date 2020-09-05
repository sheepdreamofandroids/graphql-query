package net.bloemsma.graphql.query.operators

import graphql.language.ArrayValue
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.Operator
import net.bloemsma.graphql.query.Query
import net.bloemsma.graphql.query.QueryFunction
import net.bloemsma.graphql.query.Result
import net.bloemsma.graphql.query.SchemaFunction
import net.bloemsma.graphql.query.Variables
import kotlin.reflect.KClass

class Or : Operator<Boolean> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class && contextType is GraphQLObjectType

    override fun makeField(
        contextType: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.field {
            it.name("or").type(GraphQLList(function(contextType, Boolean::class).reference()))
        }
    }

    override fun compile(
        param: Query,
        schemaFunction: SchemaFunction<Boolean>,
        contextType: GraphQLOutputType
    ): QueryFunction<Boolean>? =
        (param as ArrayValue).values.map {
            schemaFunction.functionFor(contextType, Boolean::class).compile(null, it, contextType)
        }.let { orComponents ->
            { r: Result?, v: Variables -> orComponents.any { it(r, v) } }
        }

    override val name: String = "or"
}