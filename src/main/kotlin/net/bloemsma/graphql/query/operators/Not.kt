package net.bloemsma.graphql.query.operators

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLOutputType
import net.bloemsma.graphql.query.*
import kotlin.reflect.KClass

class Not : Operator<Boolean> {
    override val name = "not"

    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.field {
            it.name("not")
            it.description("Negates it's argument.")
            it.type(function(from, Boolean::class).reference())
        }
    }

    override val compile: (param: Query, schemaFunction: SchemaFunction<Boolean>, context: GraphQLOutputType) -> QueryFunction<Boolean>? = { param: Query, schemaFunction: SchemaFunction<Boolean>, _ ->
        val innerPred =
            schemaFunction.functionFor(schemaFunction.contextQlType, Boolean::class)
                .compile(null, param, schemaFunction.contextQlType)
        ;{ r: Result?, v: Variables -> !innerPred(r, v) }
    }

}