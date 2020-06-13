package net.bloemsma.graphql.query.operators

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.*
import kotlin.reflect.KClass

class AnyOfList : Operator<Boolean> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class && contextType is GraphQLList

    override fun makeField(
        from: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        query.field {
            (from as GraphQLList).wrappedType.testableType()?.run {
                it.name("any").type(function(this, Boolean::class).ref)
            }
        }
    }

    override val compile: (param: Query, schemaFunction: SchemaFunction<Boolean>) -> QueryPredicate
        get() = TODO("Not yet implemented")
    override val name: String = "_ANY"
}