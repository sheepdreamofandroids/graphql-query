package net.bloemsma.graphql.query.operators

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.*
import kotlin.reflect.KClass

/** 'any' operator that tests whether at least one element of a list satisfies a predicate*/
class AnyOfList : Operator<Boolean> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class && contextType is GraphQLList && contextType.wrappedType.testableType() != null

//    override fun <T : Any> produce(resultType: KClass<T>, contextType: GraphQLOutputType): Iterable<Operator<T>> {
//        return listOf(object : Operator<Boolean> {} as Operator<T>)
//    }

    override fun makeField(
        from: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        query.field {
            (from as GraphQLList).wrappedType.testableType()?.run {
                it.name("any").type(function(this, Boolean::class).reference())
            }
        }
    }

    override val compile: (param: Query, schemaFunction: SchemaFunction<Boolean>, context: GraphQLOutputType) -> QueryFunction<Boolean>? =
        { param: Query, schemaFunction: SchemaFunction<Boolean>, context: GraphQLOutputType ->
            val predicate: QueryPredicate = schemaFunction.functionFor(context, Boolean::class)
                .compile(name, param, context);
            { r: Result?, v: Variables ->
                (r as Iterable<Result?>).any { predicate(it, v) }
            }
        }
    override val name: String = "_ANY"
}