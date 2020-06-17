package net.bloemsma.graphql.query

import graphql.schema.*
import graphql.schema.GraphQLFieldDefinition.newFieldDefinition
import graphql.schema.GraphQLList.list
import graphql.schema.GraphQLSchema.newSchema
import graphql.schema.idl.SchemaPrinter

open abstract class SchemaChanger(val schema: GraphQLSchema) {
    val typesToModify: MutableSet<GraphQLNamedType> = schema.allTypesAsList.toMutableSet()
    val modifiedTypes: MutableMap<String, GraphQLTypeReference> = mutableMapOf()
    abstract fun additionalTypes(): Set<GraphQLType>

    fun GraphQLType.change(): GraphQLType = when (this) {
        is GraphQLNamedType -> {
            typesToModify.add(this)
            modifiedTypes.computeIfAbsent(name) { GraphQLTypeReference.typeRef(name) }
        }
        else -> this
    }

    fun GraphQLNamedType.change(): GraphQLNamedType = when (this) {
        is GraphQLObjectType -> change()
        else -> this
    }

    open fun change() = newSchema(schema).change().build().also {
        println(SchemaPrinter(SchemaPrinter.Options.defaultOptions()).print(it))
    }

    open fun GraphQLSchema.Builder.change(): GraphQLSchema.Builder = apply {
        clearAdditionalTypes()
        query(schema.queryType?.change())
        mutation(schema.mutationType?.change())
        subscription(schema.subscriptionType?.change())
        typesToModify.forEach { it.change() }
        additionalTypes((modifiedTypes - schema.queryType?.name - schema.mutationType?.name - schema.subscriptionType?.name).values.toSet() + additionalTypes())
    }


    open fun GraphQLObjectType.change(): GraphQLObjectType =
        GraphQLObjectType.newObject(this).apply { change(this@change) }.build()

    open fun GraphQLObjectType.Builder.change(original: GraphQLObjectType) {
        replaceFields(original.fieldDefinitions.map { it.change() })
    }

    open fun GraphQLFieldDefinition.change() = newFieldDefinition(this).apply { this.change(this@change) }.build()
    open fun GraphQLFieldDefinition.Builder.change(original: GraphQLFieldDefinition) {
        type(original.type.change() as GraphQLOutputType)
    }

    open fun GraphQLList.change(): GraphQLList = list(wrappedType.change())
}
