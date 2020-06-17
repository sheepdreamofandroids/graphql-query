package net.bloemsma.graphql.query

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

class AddQueryToSchema(private val operators: OperatorRegistry) {
    val functions: MutableMap<String, Lazy<SchemaFunction<*>>> = mutableMapOf()
    fun <R : Any> functionFor(contextType: GraphQLOutputType, resultClass: KClass<R>): SchemaFunction<R> {
        //TODO key must contain result type
        return functions.computeIfAbsent(contextType.makeName()) {
            lazy {
                SchemaFunction(
                    contextType,
                    resultClass,
                    operators,
                    { a: GraphQLOutputType, b: KClass<*> -> functionFor(a, b) }
                )
            }
        }.value as SchemaFunction<R>
    }

    fun transform(
        schema: GraphQLSchema
    ): GraphQLSchema {
        return SchemaTransformer.transformSchema(schema, object : GraphQLTypeVisitorStub() {
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
                                    arg.type(functionFor(predicateType, Boolean::class).reference())
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
        })
            .also {
                println("Found these functions: $functions")
//                println(schemaPrinter.print(it))
            }
    }

    fun transform2(schema: GraphQLSchema) = object : SchemaChanger(schema) {
        override fun GraphQLFieldDefinition.Builder.change(original: GraphQLFieldDefinition) {
            original.type.filterableType()?.let { listType: GraphQLList ->
                listType.wrappedType.testableType()?.let { predicateType ->
                    if (!predicateType.isBuiltInReflection()) {
                        val schemaFunction = functionFor(predicateType, Boolean::class)
                        argument { arg ->
                            arg.name("_filter")
                            arg.type(schemaFunction.reference())
                        }
                        println("Modified field ${original.name} of type ${original.type}: Added filter for $schemaFunction.")
//                        additionalTypes.add(schemaFunction.parmQlType)
                    }
                }
            }
        }

        override fun additionalTypes(): Set<GraphQLInputType> {
            // when retrieving parmQlType, new types might be materialized into functions
            // so keep retrieving until size doesn't change anymore
            do {
                val size = functions.size
                functions.values.toList().forEach { it.value.parmQlType }
            } while (functions.size > size)
            return functions.values.map { it.value.parmQlType }.toSet()
        }
    }.change()


    /** Function types are always referenced by name. Therefore they have to be added as additional types to the schema
     * or else those names would be unknown.
     */
    private fun updateAdditionalTypes(context: TraverserContext<GraphQLSchemaElement>) {
//        val root = context.parentNodes.last()
//        root.withNewChildren(root.childrenWithTypeReferences.transform {
//            it.children("addTypes", functions.values.map { it.value.parmQlType })
//        })
    }

}

fun GraphQLType.makeName(): String = when (this) {
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

