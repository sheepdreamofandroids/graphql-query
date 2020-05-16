package net.bloemsma

import graphql.schema.*
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import kotlin.reflect.KClass


class FunctionInfo(
    contextQlType: GraphQLOutputType,
    resultClass: KClass<*>,
    operators: OperatorRegistry,
    function: (GraphQLOutputType, KClass<*>) -> GraphQLInputType
) {
    val typeName = contextQlType.makeName()
    val predicateName = "${typeName}__to__" + resultClass.simpleName
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
    val functions: MutableMap<String, FunctionInfo> = mutableMapOf()
    val parsers: MutableMap<String, FilterParser> = mutableMapOf()


    fun GraphQLOutputType.function(kClass: KClass<*>): FunctionInfo {
        val typeName = makeName()
        val predicateName = "${typeName}__to__" + kClass.simpleName
//        val functionInfo = FunctionInfo(this, kClass)
        return functions.computeIfAbsent(typeName) { typeName ->
//                    functions.add(typeName)
//                    println("Creating type $predicateName")
            //                val x: FilterParser = ops.map { it.makeParser }
//            val qlInputObjectType = GraphQLInputObjectType.newInputObject()
//                .name(predicateName)
//                .also { query ->
//                    operators
//                        .applicableTo(kClass, this)
//                        .forEach {
//                            it.makeField(this, query) { a, b ->
//                                a.function(b).parmQlType
//                            }
//                        }
////                    when (this) {
////                            is GraphQLObjectType -> fieldDefinitions.forEach { field ->
////                                query.field {
////                                    it.name(field.name)
////                                    it.type(field.type.function(kClass))
////                                }
////                            }
////                        is GraphQLList -> {
////                            query.field { it.name("size").type(Scalars.GraphQLInt.function(kClass)) }
////                                query.field {
////                                    wrappedType.testableType()?.run {
////                                        it.name("any").type(this.function(kClass))
////                                    }
////                                    it
////                                }
////                        }
////                        else -> {
////                        }
////                }
////                    query.field {
////                        it.name("_OR")
////                        it.type(GraphQLList.list(function(kClass).parmQlType))
////                    }
////                    query.field {
////                        it.name("_NOT")
////                        it.type(function(kClass))
////                    }
//                }
//                .build()

            FunctionInfo(this, kClass, operators, { a: GraphQLOutputType, b: KClass<*> ->
                a.function(b).parmQlType
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
                        .argument {
                            it.name("_filter")
                            it.type(predicateType.function(Boolean::class).parmQlType)
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

