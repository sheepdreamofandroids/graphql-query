package net.bloemsma

import graphql.Scalars
import graphql.language.Value
import graphql.schema.*
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import kotlin.reflect.KClass

typealias FilterParser = (Value<*>) -> Modifier

class FunctionInfo(data: GraphQLOutputType, kClass: KClass<*>,val qlInputObjectType) {
    val typeName = data.makeName()
    val predicateName = "${typeName}__to__" + kClass.simpleName
    val ref = GraphQLTypeReference.typeRef(predicateName)
}

class AddQueryToSchema(val operators: OperatorRegistry) : GraphQLTypeVisitorStub() {
    val functions: MutableMap<String, FunctionInfo> = mutableMapOf()
    val parsers: MutableMap<String, FilterParser> = mutableMapOf()


    fun GraphQLOutputType.function(kClass: KClass<*>): GraphQLInputType {
        val typeName = makeName()
//        val predicateName = "${typeName}__to__" + kClass.simpleName
        val functionInfo = FunctionInfo(this, kClass)
        return functions.computeIfAbsent(typeName) { typeName ->
//                    functions.add(typeName)
//                    println("Creating type $predicateName")
            //                val x: FilterParser = ops.map { it.makeParser }
            val qlInputObjectType = GraphQLInputObjectType.newInputObject()
                .name(predicateName)
                .also { query ->
                    operators.operator(kClass, this).forEach { it.makeField(this, query) { a, b -> a.function(b) } }
                    when (this) {
//                            is GraphQLObjectType -> fieldDefinitions.forEach { field ->
//                                query.field {
//                                    it.name(field.name)
//                                    it.type(field.type.function(kClass))
//                                }
//                            }
                        is GraphQLList -> {
                            query.field { it.name("size").type(Scalars.GraphQLInt.function(kClass)) }
//                                query.field {
//                                    wrappedType.testableType()?.run {
//                                        it.name("any").type(this.function(kClass))
//                                    }
//                                    it
//                                }
                        }
                        else -> {
                        }
                    }
                    query.field {
                        it.name("_OR")
                        it.type(GraphQLList.list(function(kClass)))
                    }
                    query.field {
                        it.name("_NOT")
                        it.type(function(kClass))
                    }
                }
                .build()
            qlInputObjectType
            FunctionInfo(this,kClass,qlInputObjectType)
        }.ref
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
                            it.type(predicateType.function(Boolean::class))
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

