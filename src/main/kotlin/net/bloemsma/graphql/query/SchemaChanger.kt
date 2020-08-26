package net.bloemsma.graphql.query

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldDefinition.newFieldDefinition
import graphql.schema.GraphQLList
import graphql.schema.GraphQLList.list
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLNonNull.nonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchema.newSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.idl.SchemaPrinter

abstract class SchemaChanger(val schema: GraphQLSchema) {
    val originalObjects: MutableMap<String, GraphQLObjectType> = mutableMapOf()
    val referencedObjects: MutableMap<String, GraphQLObjectType> = mutableMapOf()
    val references: MutableMap<String, GraphQLTypeReference> = mutableMapOf()
    abstract fun additionalTypes(): Set<GraphQLType>

    fun GraphQLType.change(): GraphQLType = when (this) {
        is GraphQLObjectType -> {
            originalObjects[name] = this
            references.computeIfAbsent(name) { GraphQLTypeReference.typeRef(name) }
        }
        is GraphQLList -> list(wrappedType.change())
        is GraphQLNonNull -> nonNull(wrappedType.change())
        is GraphQLScalarType -> this
        is GraphQLEnumType -> this

        else -> throw Exception("extend me!")
    }

    open fun change() = newSchema(schema).change().build()
        .logDebug { SchemaPrinter(SchemaPrinter.Options.defaultOptions()).print(it) }

    open fun GraphQLSchema.Builder.change(): GraphQLSchema.Builder = apply {
        clearAdditionalTypes()
        val query = schema.queryType?.change()
        query(query)
        val mutation = schema.mutationType?.change()
        mutation(mutation)
        val subscription = schema.subscriptionType?.change()
        subscription(subscription)
        while (originalObjects.size > referencedObjects.size) {
            (originalObjects - referencedObjects.keys).forEach {
                referencedObjects[it.key] = it.value.change()
            }
        }
        additionalTypes(referencedObjects.values.toSet() + additionalTypes())
    }


    open fun GraphQLObjectType.change(): GraphQLObjectType =
        GraphQLObjectType.newObject(this).apply { change(this@change) }.build()

    open fun GraphQLObjectType.Builder.change(original: GraphQLObjectType) {
        replaceFields(original.fieldDefinitions.map { it.change() })
    }

    open fun GraphQLFieldDefinition.change() = newFieldDefinition(this).apply { this.change(this@change) }.build()
    open fun GraphQLFieldDefinition.Builder.change(original: GraphQLFieldDefinition) {
        changeDefault(original)
    }

    // Ugly workaround for https://youtrack.jetbrains.com/issue/KT-11488
    protected fun GraphQLFieldDefinition.Builder.changeDefault(original: GraphQLFieldDefinition) {
        type(original.type.change() as GraphQLOutputType)
    }

    open fun GraphQLList.change(): GraphQLList = list(wrappedType.change())
}
