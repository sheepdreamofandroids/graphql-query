package net.bloemsma.graphql.query

import graphql.GraphqlErrorException
import graphql.language.SourceLocation

class GraphQlQueryException(
    override val message: String?,
    sourceLocation: SourceLocation?
) : GraphqlErrorException(
    newErrorException()
        .sourceLocation(sourceLocation)
)