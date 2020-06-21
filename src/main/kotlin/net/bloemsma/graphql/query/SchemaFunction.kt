package net.bloemsma.graphql.query

import graphql.language.ObjectValue
import graphql.schema.*
import kotlin.reflect.KClass

/** Represents a function for one particular type in the schema.*/
class SchemaFunction<R : Any>(
    /** The context in which this function will operate, i.e. the type of data in the result to be transformed.*/
    val contextQlType: GraphQLOutputType,
    /** The result of this operator when executing, for example Boolean when in a filter.
     * But it could be something other, like an Integer for an add operator.*/
    resultClass: KClass<R>,
    /** All available operators in the system.*/
    ops: OperatorRegistry,
    /** Something that can resolve nested operators. */
    //TODO just make it a function and subtype, so it can be generified
    private val function: (GraphQLOutputType, KClass<*>) -> SchemaFunction<*>
) {
    private val signatureName = "${contextQlType.makeName()}__to__" + resultClass.simpleName

    private val operators: Map<String, Operator<R>> =
        ops.applicableTo(resultClass, contextQlType).associateBy { it.name }
    val parmQlType: GraphQLInputType by lazy {
        // lazy to avoid infinite recursion
        GraphQLInputObjectType.newInputObject().apply {
            name(signatureName)
            for (it in operators.values) {
                it.makeField(contextQlType, this, function)
            }
        }.build()

    }

    val ref = GraphQLTypeReference.typeRef(signatureName)

    private var wasReferenced = false
    fun reference() = ref
    fun referenc2e() = if (wasReferenced) {
        // all subsequent references including recursive use a named reference
        println("typeref to $signatureName")
        GraphQLTypeReference.typeRef(signatureName)
    } else { // only the very first reference uses the actual type so it is referenced exactly once
        println("acutally using $signatureName")
        wasReferenced = true
        parmQlType
    }

    override fun toString(): String {
        return "Function for $signatureName"
    }

    fun compile(name: String?, value: Query, context: GraphQLOutputType): QueryFunction<R> =
        (value as? ObjectValue)?.objectFields
            ?.mapNotNull { dataField ->
                operators[dataField.name]
                    ?.compile
                    ?.invoke(dataField.value, this, context)
            }
            ?.let { effectiveOps ->
                when (effectiveOps.size) {
                    0 -> throw GraphQlQueryException(
                        "Empty object",
                        value.sourceLocation
                    )
                    1 -> effectiveOps[0]
                    else -> {
                        { c: Result?, v: Variables ->
                            // TODO only makes sense for predicates otherwise need different join function like ADD or MULT
                            effectiveOps.all { it(c, v) as Boolean } as R
                        }.showingAs { effectiveOps.joinToString(prefix = "AND(", separator = ", ", postfix = ")") }
                    }
                }
            }
            ?: throw GraphQlQueryException(
                "Empty object",
                value.sourceLocation
            )

    fun <T : Any> functionFor(type: GraphQLOutputType, kClass: KClass<T>): SchemaFunction<T> =
        function(type, kClass).also { println("Got $this") } as SchemaFunction<T>
}

private fun GraphQLOutputType.objectField(name: String): GraphQLFieldDefinition? =
    (this as? GraphQLObjectType)?.fieldDefinitions?.find { it.name == name }
