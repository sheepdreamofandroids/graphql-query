package net.bloemsma

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType

fun GraphQLSchema.addQueries(): GraphQLSchema =
    transform {
        it.clearAdditionalTypes()
//        it.additionalTypes(additionalTypes.map { it.addQuery() }.toSet())
        it.query(queryType.transform { it.addQuery() })
    }

private fun GraphQLObjectType.Builder.addQuery()  {
//return this.
}

//fun GraphQLType.addQuery(): GraphQLType =
