package net.bloemsma.graphql.query

import graphql.language.ObjectValue
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLTypeReference
import kotlin.reflect.KClass

/** Represents a set of operators for the combination of a context type and a result type in the schema.
 *
 * For example for the context "Int" and result boolean there would be a SchemaFunction with operators like
 * "eq: Int", "gt: Int", "isNull: boolean".
 *
 * Each SchemaFunction corresponds to an input type with one field for each operator.
 */
class SchemaFunction<R : Any>(
    /** The context in which this function will operate, i.e. the type of data in the result to be transformed.*/
    val contextQlType: GraphQLOutputType,
    /** The result of this operator when executing, for example Boolean when in a filter.
     * But it could be something other, like an Integer for an addition.*/
    resultClass: KClass<R>,
    /** All available operators in the graphql schema.*/
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

    val ref: GraphQLTypeReference = GraphQLTypeReference.typeRef(signatureName)

    fun reference(): GraphQLTypeReference = ref

    override fun toString(): String {
        return "SchemaFunction $signatureName with ops (${operators.keys})"
    }

    fun compile(name: String?, value: Query, context: GraphQLOutputType): QueryFunction<R> =
        trace({ "$this.compile" }) {
            (value as? ObjectValue)?.objectFields
                ?.mapNotNull { dataField ->
                    trace({ "${dataField.name} -> ${this.javaClass.simpleName} .compile" }) {
                        operators[dataField.name]?.let { it.compile(dataField.value, this@SchemaFunction, context) }
                    }
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
        }

    fun <T : Any> functionFor(type: GraphQLOutputType, kClass: KClass<T>): SchemaFunction<T> =
        function(type, kClass).logDebug { "Got $this" } as SchemaFunction<T>
}

private fun GraphQLOutputType.objectField(name: String): GraphQLFieldDefinition? =
    (this as? GraphQLObjectType)?.fieldDefinitions?.find { it.name == name }
