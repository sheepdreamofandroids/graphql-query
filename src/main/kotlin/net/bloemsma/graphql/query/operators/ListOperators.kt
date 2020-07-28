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

class ListOperators : OperatorProducer {
    override fun <R : Any> produce(
        resultType: KClass<R>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<R>> = if (!contextType.canProduce()) listOf() else mutableListOf<Operator<R>>().apply {
        if (resultType == Boolean::class) {
            add(ListOp("any", Boolean::class, Boolean::class) { predicate ->
                { r: Result?, v: Variables ->
                    (r as Iterable<Result?>).any { predicate(it, v) }
                }
            } as Operator<R>)
            add(ListOp("all", Boolean::class, Boolean::class) { predicate ->
                { r: Result?, v: Variables ->
                    (r as Iterable<Result?>).all { predicate(it, v) }
                }
            } as Operator<R>)
        }
        if (resultType == Number::class) {
            add(ListOp("sum", Double::class, Double::class) { fn ->
                { r: Result?, v: Variables ->
                    (r as Iterable<Result?>).sumByDouble { fn(it, v) }
                }
            } as Operator<R>)
        }
        if (resultType == Int::class) {
            add(ListOp("size", Any::class, Int::class) { fn ->
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
    private val body: (QueryFunction<O>) -> (Result?, Variables) -> O
) : Operator<O> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType.isSubclassOf(requiredResultType) && contextType.toTest() != null

    override fun makeField(
        contextType: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        query.field {
            (contextType as GraphQLList).wrappedType.testableType()?.run {
                it.name(name).type(function(this, outputType).reference())
            }
        }
    }

    override fun compile(
        param: Query,
        schemaFunction: SchemaFunction<O>,
        contextType: GraphQLOutputType
    ): QueryFunction<O>? {
        val fn: QueryFunction<O> = schemaFunction.functionFor(contextType.toTest()!!, outputType)
            .compile(name, param, contextType);
        return body(fn)
    }

}

private fun GraphQLOutputType.canProduce() =
    this.toTest() != null

private fun GraphQLOutputType.toTest() =
    (this as? GraphQLList)?.wrappedType?.testableType()