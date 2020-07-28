package net.bloemsma.graphql.query.operators

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.Operator
import net.bloemsma.graphql.query.OperatorProducer
import net.bloemsma.graphql.query.OperatorRegistry
import net.bloemsma.graphql.query.Query
import net.bloemsma.graphql.query.QueryFunction
import net.bloemsma.graphql.query.Result
import net.bloemsma.graphql.query.SchemaFunction
import net.bloemsma.graphql.query.Variables
import net.bloemsma.graphql.query.getField
import net.bloemsma.graphql.query.showingAs
import kotlin.reflect.KClass

// TODO generify to more than boolean
class AndOfFields : OperatorProducer {
    override fun <T : Any> produce(
        resultType: KClass<T>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<T>> =
        if (/*resultType == Boolean::class &&*/ contextType is GraphQLObjectType)
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
    private val resultType: KClass<R>
) : Operator<R> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        contextType == graphQLObjectType

    override fun makeField(
        contextType: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.description("This is true when all fields are true (AND).")
        (contextType as GraphQLObjectType).fieldDefinitions.forEach { field ->
            into.field {
                it.name(field.name)
                it.type(function(field.type, Boolean::class).reference())
            }

        }
    }

    override fun compile(param: Query, schemaFunction: SchemaFunction<R>, contextType: GraphQLOutputType): QueryFunction<R>?
    {
        val context = graphQLObjectType.getFieldDefinition(name).type
        return schemaFunction
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