package net.bloemsma.graphql.query

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.SchemaTransformer
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
        val schemaFunction: SchemaFunction<*> = functions.computeIfAbsent(contextType.makeName()) {
            lazy {
                SchemaFunction(
                    contextType,
                    resultClass,
                    operators,
                    { a: GraphQLOutputType, b: KClass<*> -> functionFor(a, b) }
                )
            }
        }.value
        @Suppress("UNCHECKED_CAST") // it was filtered on resultClass
        return schemaFunction as SchemaFunction<R>
    }

    fun transform(schema: GraphQLSchema) = object : SchemaChanger(schema) {
        override fun GraphQLFieldDefinition.Builder.change(original: GraphQLFieldDefinition) {
            this.changeDefault(original)
            original.type.filterableType()?.let { listType: GraphQLList ->
                listType.wrappedType.testableType()?.let { predicateType ->
                    if (!predicateType.isBuiltInReflection()) {
                        val schemaFunction = functionFor(predicateType, Boolean::class)
                        argument { arg ->
                            arg.name("_filter")
                            arg.type(schemaFunction.reference())
                        }
                        logln { "Modified field ${original.name} of type ${original.type}: Added filter for $schemaFunction." }
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


}

fun GraphQLType.makeName(): String = when (this) {
    is GraphQLNamedType -> name
    is GraphQLList -> "List_of_${wrappedType.makeName()}"
    is GraphQLNonNull -> "NonNull_of_${wrappedType.makeName()}"
    else -> "Cannot make name for $this"
}


fun GraphQLType.testableType(): GraphQLOutputType? = when (this) {
    is GraphQLNonNull -> this
    is GraphQLList -> this
    is GraphQLObjectType -> this
    is GraphQLEnumType -> this
    is GraphQLScalarType -> this
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

