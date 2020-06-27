package net.bloemsma.graphql.query.operators

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.*
import kotlin.reflect.KClass

class AndOfFields : OperatorProducer {
    override fun <T : Any> produce(
        resultType: KClass<T>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<T>> =
        if (resultType == Boolean::class && contextType is GraphQLObjectType)
            contextType.fieldDefinitions.map {
                ObjectFieldOp(
                    contextType,
                    it,
                    resultType
                )
            }
        else
            emptyList()
}

class ObjectFieldOp<R : Any>(
    private val graphQLObjectType: GraphQLObjectType,
    fieldDefinition: GraphQLFieldDefinition,
    resultType: KClass<R>
) : Operator<R> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        contextType == graphQLObjectType

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.description("This is true when all fields are true (AND).")
        (from as GraphQLObjectType).fieldDefinitions.forEach { field ->
            into.field {
                it.name(field.name)
                it.type(function(field.type, Boolean::class).reference())
            }

        }
    }

    override val compile: (param: Query, schemaFunction: SchemaFunction<R>, context: GraphQLOutputType) -> QueryFunction<R>? =
        { param: Query, schemaFunction: SchemaFunction<R>, _ ->
            val context = graphQLObjectType.getFieldDefinition(name).type
            schemaFunction
                .functionFor(context, resultType)
                .compile(null, param, context)
                .let { func ->
                    { c: Result?, v: Variables ->
                        func(c?.getField(name), v)
                    }.showingAs { "($name) " }
                }
        }

    override val name: String = fieldDefinition.name
}