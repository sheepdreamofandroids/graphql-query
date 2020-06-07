package net.bloemsma.graphql.query

import graphql.language.ObjectValue
import graphql.schema.*
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import kotlin.reflect.KClass

// Some terminology:
// Every operator in the graphQL-query language operates on some "current" data in the graphQL-result and some data
// from the input object in the graphQL-query. That current data is called the context and the input object is the
// parameter.
// For example a filter "(_filter: {a: {eq: 3}, b: {gt: 5})" has an outer operator {_filter ...} whose context is
// a list of objects, the operator will will remove some entries from the list.
// The sub-operator{a: ..., b: ...} which is effectively an AND over it's sub-operators; it's context is some object
// which is expected to have fields called 'a' and 'b'.
// Finally {eq: 3} and {gt: 5} are operators expecting a numerical context with literal parameters.

/** A function for one particular type in the schema.*/
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

    val ref: GraphQLTypeReference = GraphQLTypeReference.typeRef(signatureName)
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

    override fun toString(): String {
        return "Function for $signatureName"
    }

    fun compile(name: String?, value: Query): QueryFunction<R> =
        (value as? ObjectValue)?.objectFields
            ?.mapNotNull {
                operators[it.name]
                    ?.compile
                    ?.invoke(it.value, this)
            }
            ?.let { effectiveOps ->
                when (effectiveOps.size) {
                    0 -> throw GraphQlQueryException("Empty object", value.sourceLocation)
                    1 -> effectiveOps[0]
                    else -> {
                        { c: Result, v: Variables ->
                            // TODO only makes sense for predicates otherwise need different join function like ADD or MULT
                            effectiveOps.all { it(c, v) as Boolean } as R
                        }.showingAs { effectiveOps.joinToString(prefix = "AND(", separator = ", ", postfix = ")") }
                    }
                }
            }
            ?: throw GraphQlQueryException("Empty object", value.sourceLocation)
//}
//                    ?.invoke(value, this)
//                    ?: throw Exception("Oops")

    fun <T : Any> functionFor(type: GraphQLOutputType, kClass: KClass<T>): SchemaFunction<T> =
        function(type, kClass).also { println("Got $this") } as SchemaFunction<T>
}

class AddQueryToSchema(private val operators: OperatorRegistry) : GraphQLTypeVisitorStub() {
    val functions: MutableMap<String, SchemaFunction<*>> = mutableMapOf()
    fun <R : Any> functionFor(contextType: GraphQLOutputType, resultClass: KClass<R>): SchemaFunction<R> {
        //TODO key must contain result type
        return functions.computeIfAbsent(contextType.makeName()) {
            SchemaFunction(
                contextType,
                resultClass,
                operators,
                { a: GraphQLOutputType, b: KClass<*> -> functionFor(a, b) }
//                this::functionFor as (GraphQLOutputType, KClass<*>) -> SchemaFunction<*>
            )
        } as SchemaFunction<R>
    }

    override fun visitGraphQLFieldDefinition(
        node: GraphQLFieldDefinition,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl {
        node.type.filterableType()?.let { listType: GraphQLList ->
            listType.wrappedType.testableType()?.let { predicateType ->
                if (!predicateType.isBuiltInReflection()) {
                    println("modified $node")
                    val newNode = GraphQLFieldDefinition.newFieldDefinition(node)
                        .argument { arg ->
                            arg.name("_filter")
                            arg.type(functionFor(predicateType, Boolean::class).ref)
                        }
                        // can't use a directive because it's declared globally and
                        // therefore the argument type is the same everywhere
                        .build()
                    println("into $newNode")
                    updateAdditionalTypes(context)
                    return TreeTransformerUtil.changeNode(context, newNode)
                }
            }
        }
        updateAdditionalTypes(context)
        return super.visitGraphQLFieldDefinition(node, context)
    }

    /** Function types are always referenced by name. Therefore they have to be added as additional types to the schema
     * or else those names would be unknown.
     */
    private fun updateAdditionalTypes(context: TraverserContext<GraphQLSchemaElement>) {
        val root = context.parentNodes.last()
        root.withNewChildren(root.childrenWithTypeReferences.transform {
            it.children("addTypes", functions.values.map { it.parmQlType })
        })
    }

}

private fun GraphQLType.makeName(): String = when (this) {
    is GraphQLNamedType -> name
    is GraphQLList -> "_List_of_${wrappedType.makeName()}"
    is GraphQLNonNull -> "_NonNull_of_${wrappedType.makeName()}"
    else -> "Cannot make name for $this"
}

private fun filterable(node: GraphQLFieldDefinition): Boolean {
    val listType = node.type as? GraphQLList ?: return false
    // The following skips all built-in reflection queries.
    // Should the following just be hardcoded names?
    val nonNullType = listType.wrappedType as? GraphQLNonNull ?: return true
    return when (val objectType = nonNullType.wrappedType) {
        is GraphQLNamedType -> !objectType.name.startsWith("__")
        else -> false
    }
}


fun GraphQLType.effectiveType(): GraphQLType = when (this) {
    is GraphQLNonNull -> wrappedType.effectiveType()
    else -> this
}

fun GraphQLType.testableType(): GraphQLOutputType? = when (this) {
    is GraphQLNonNull -> wrappedType.testableType()
    is GraphQLList -> this
    is GraphQLObjectType -> this
    is GraphQLEnumType -> this
    else -> null
}

fun GraphQLType.filterableType(): GraphQLList? = when (this) {
    is GraphQLNonNull -> wrappedType.filterableType()
    is GraphQLList -> this
    else -> null
}

fun GraphQLType.isBuiltInReflection(): Boolean = when (this) {
    is GraphQLObjectType -> name.startsWith("__")
    is GraphQLEnumType -> name.startsWith("__")
    else -> false
}

