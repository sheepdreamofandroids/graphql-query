package net.bloemsma.graphql.query.operators

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.*
import kotlin.reflect.KClass

class NonNull : OperatorProducer {
    override fun <R : Any> produce(
        resultType: KClass<R>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<R>> = if (contextType is GraphQLNonNull) {
        val innerType = contextType.wrappedType as GraphQLOutputType
        operatorRegistry
            .applicableTo(resultType, innerType)
            .map {
                object : Operator<R> {
                    override val name = it.name

                    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
                        it.canProduce(resultType, innerType)

                    override fun makeField(
                        from: GraphQLOutputType,
                        into: GraphQLInputObjectType.Builder,
                        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
                    ) {
                        it.makeField(innerType, into, function)
                    }

                    override val compile: (param: Query, schemaFunction: SchemaFunction<R>, context: GraphQLOutputType) -> QueryFunction<R>?
                        get() = { p, s, c -> it.compile(p, s, innerType) }
                }
            }
    } else
        emptyList()
}