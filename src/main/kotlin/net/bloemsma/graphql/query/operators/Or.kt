package net.bloemsma.graphql.query.operators

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.Operator
import net.bloemsma.graphql.query.Query
import net.bloemsma.graphql.query.QueryFunction
import net.bloemsma.graphql.query.SchemaFunction
import kotlin.reflect.KClass

class OrOfFields : Operator<Boolean> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class && contextType is GraphQLObjectType

    override fun makeField(
        from: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        query.field {
            it.name("_OR").type(GraphQLList(function(from, Boolean::class).reference()))
        }
    }

    override fun compile(
        param: Query,
        schemaFunction: SchemaFunction<Boolean>,
        context: GraphQLOutputType
    ): QueryFunction<Boolean>? =
        TODO("Not yet implemented")

    override val name: String = "_OR"
}