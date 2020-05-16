package net.bloemsma

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
// which is expected to have numerical fields called 'a' and 'b'.
// Finally {eq: 3} and {gt: 5} are operators in a numerical context with literal parameters.

class FilterFunction(
    /** The context in which this function will operate, i.e. the type of data in the result to be transformed.*/
    contextQlType: GraphQLOutputType,
    /** The result of this operator when executing, for example Boolean when in a filter.
     * But it could be something other, like an Integer for an add operator.*/
    resultClass: KClass<*>,
    /** All available operators in the system.*/
    operators: OperatorRegistry,
    /** Something that can resolve nested operators. */
    function: (GraphQLOutputType, KClass<*>) -> GraphQLInputType
) {
    private val predicateName = "${contextQlType.makeName()}__to__" + resultClass.simpleName

    val ref = GraphQLTypeReference.typeRef(predicateName)
    val parmQlType: GraphQLInputType by lazy {
        GraphQLInputObjectType.newInputObject().apply {
            name(predicateName)
            operators
                .applicableTo(resultClass, contextQlType)
                .forEach {
                    it.makeField(contextQlType, this, function)
                }
        }
            .build()

    }
}

class AddQueryToSchema(val operators: OperatorRegistry) : GraphQLTypeVisitorStub() {
    val functions: MutableMap<String, FilterFunction> = mutableMapOf()

    fun functionFor(contextType: GraphQLOutputType, resultClass: KClass<*>): FilterFunction {
        return functions.computeIfAbsent(contextType.makeName()) { _ ->
            FilterFunction(contextType, resultClass, operators, { a: GraphQLOutputType, b: KClass<*> ->
                functionFor(a, b).ref
            })
        }
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
                            val filterFunction = functionFor(predicateType, Boolean::class)
                            // the following two lines add the type as an additional type to the schema
                            // this is because otherwise only the reference is used everywhere
                            val root = context.parentNodes.last()
                            root.withNewChildren(root.childrenWithTypeReferences.transform {
                                it.children(
                                    "addTypes",
                                    functions.values.map { it.parmQlType }
                                )
                            })
                            println("Added type ${filterFunction.parmQlType}")
                            arg.type(filterFunction.ref)
                        }
                        // can't use a directive because it's declared globally and
                        // therefore the argument type is the same everywhere
                        .build()
                    println("into $newNode")
                    return TreeTransformerUtil.changeNode(context, newNode)
                }
            }
        }
        return super.visitGraphQLFieldDefinition(node, context)
    }

}

private fun GraphQLType.makeName(): String = when (this) {
    is GraphQLObjectType -> name
    is GraphQLEnumType -> name
    is GraphQLScalarType -> name
    is GraphQLList -> "_List_of_${wrappedType.makeName()}"
    else -> "Cannot make name for $this"
}

private fun filterable(node: GraphQLFieldDefinition): Boolean {
    val listType = node.type as? GraphQLList ?: return false
    // The following skips all built-in reflection queries.
    // Should the following just be hardcoded names?
    val nonNullType = listType.wrappedType as? GraphQLNonNull ?: return true
    return when (val objectType = nonNullType.wrappedType) {
        is GraphQLObjectType -> !objectType.name.startsWith("__")
        is GraphQLEnumType -> !objectType.name.startsWith("__")
        else -> false
    }
}


fun GraphQLType.effectiveType(): GraphQLType = when (this) {
    is GraphQLNonNull -> wrappedType.effectiveType()
    else -> this
}

fun GraphQLType.testableType(): GraphQLOutputType? = when (this) {
    is GraphQLNonNull -> wrappedType.testableType()
    is GraphQLList -> this;
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

