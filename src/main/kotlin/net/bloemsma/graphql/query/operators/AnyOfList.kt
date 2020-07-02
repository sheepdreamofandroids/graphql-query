package net.bloemsma.graphql.query.operators

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.Operator
import net.bloemsma.graphql.query.OperatorProducer
import net.bloemsma.graphql.query.OperatorRegistry
import net.bloemsma.graphql.query.Query
import net.bloemsma.graphql.query.QueryFunction
import net.bloemsma.graphql.query.Result
import net.bloemsma.graphql.query.SchemaFunction
import net.bloemsma.graphql.query.Variables
import net.bloemsma.graphql.query.testableType
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

class AnyOfList : OperatorProducer {
    override fun <R : Any> produce(
        resultType: KClass<R>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<R>> = if (!canProduce(contextType)) listOf() else mutableListOf<Operator<R>>().apply {
        if (resultType == Boolean::class) {
            add(ListOp("_ANY", Boolean::class, Boolean::class) { predicate ->
                { r: Result?, v: Variables ->
                    (r as Iterable<Result?>).any { predicate(it, v) }
                }
            } as Operator<R>)
            add(ListOp("_ALL", Boolean::class, Boolean::class) { predicate ->
                { r: Result?, v: Variables ->
                    (r as Iterable<Result?>).all { predicate(it, v) }
                }
            } as Operator<R>)
        }
        if (resultType == Number::class) {
            add(ListOp("_SUM", Double::class, Double::class) { fn ->
                { r: Result?, v: Variables ->
                    (r as Iterable<Result?>).sumByDouble { fn(it, v) }
                }
            } as Operator<R>)
        }
        if (resultType == Int::class) {
            add(ListOp("_SIZE", Any::class, Int::class) { fn ->
                { r: Result?, v: Variables ->
                    (r as Iterable<Result?>).count()
                }
            } as Operator<R>)
        }
    }.toList()
}

/** 'any' operator that tests whether at least one element of a list satisfies a predicate*/
class ListOp<R : Any, O : Any>(
    override val name: String,
    val requiredResultType: KClass<R>,
    val outputType: KClass<O>,
    body: (QueryFunction<O>) -> (Result?, Variables) -> O
) : Operator<O> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType.isSubclassOf(requiredResultType) && (contextType as? GraphQLList)?.wrappedType?.testableType() != null

    override fun makeField(
        from: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        query.field {
            (from as GraphQLList).wrappedType.testableType()?.run {
                it.name(name).type(function(this, outputType).reference())
            }
        }
    }

    override val compile: (param: Query, schemaFunction: SchemaFunction<O>, context: GraphQLOutputType) -> QueryFunction<O>? =
        { param: Query, schemaFunction: SchemaFunction<O>, context: GraphQLOutputType ->
            val fn: QueryFunction<O> = schemaFunction.functionFor(context, outputType)
                .compile(name, param, context);
            body(fn)
        }

}

private fun canProduce(contextType: GraphQLOutputType) =
    (contextType as? GraphQLList)?.wrappedType?.testableType() != null