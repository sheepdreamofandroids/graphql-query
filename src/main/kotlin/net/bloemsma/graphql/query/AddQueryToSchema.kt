package net.bloemsma.graphql.query

import graphql.language.Argument
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
class SchemaFunction(
    /** The context in which this function will operate, i.e. the type of data in the result to be transformed.*/
    contextQlType: GraphQLOutputType,
    /** The result of this operator when executing, for example Boolean when in a filter.
     * But it could be something other, like an Integer for an add operator.*/
    resultClass: KClass<*>,
    /** All available operators in the system.*/
    ops: OperatorRegistry,
    /** Something that can resolve nested operators. */
    function: (GraphQLOutputType, KClass<*>) -> GraphQLInputType
) {
    private val signatureName = "${contextQlType.makeName()}__to__" + resultClass.simpleName

    val ref: GraphQLTypeReference = GraphQLTypeReference.typeRef(signatureName)
    val operators: Iterable<Operator<*>> = ops.applicableTo(resultClass, contextQlType)

    val parmQlType: GraphQLInputType by lazy {
        // lazy to avoid infinite recursion
        GraphQLInputObjectType.newInputObject().apply {
            name(signatureName)
            for (it in operators) {
                it.makeField(contextQlType, this, function)
            }
        }.build()

    }

    override fun toString(): String {
        return "Function for $signatureName"
    }

    fun compile(argument: Argument): QueryTimeFunction =
        operators.find { it.name == argument.name||argument.name=="_filter" }
            ?.compile
            ?.invoke(argument.value, this)
            ?: throw Exception("Oops")
}

class AddQueryToSchema(val operators: OperatorRegistry) : GraphQLTypeVisitorStub() {
    val functions: MutableMap<String, SchemaFunction> = mutableMapOf()

    fun functionFor(contextType: GraphQLOutputType, resultClass: KClass<*>): SchemaFunction {
        return functions.computeIfAbsent(contextType.makeName()) { _ ->
            SchemaFunction(
                contextType,
                resultClass,
                operators,
                { a: GraphQLOutputType, b: KClass<*> ->
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
                            // the following two lines add the type as an additional type to the schema this is because
                            // everywhere only the reference is used
                            context.addAdditionalTypes(functions.values.map { it.parmQlType })
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

    /**Add the type as an additional type to the schema because everywhere only the reference is used.
     * Otherwise the reference would point to nothing.*/
    private fun TraverserContext<GraphQLSchemaElement>.addAdditionalTypes(additionalTypes: List<GraphQLInputType>) {
        val root = parentNodes.last()
        root.withNewChildren(root.childrenWithTypeReferences.transform {
            it.children("addTypes", additionalTypes)
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

