package net.bloemsma.graphql.query

import graphql.schema.GraphQLType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class SchemaChangerTest {
    @Test
    fun `SchemaChanger without function overrides changes nothing`() {
        val original = testSchema.schemaPrinter.print(testSchema.oldSchema)
        val transformedSchema = object : SchemaChanger(testSchema.oldSchema) {
            override fun additionalTypes(): Set<GraphQLType> = emptySet()
        }.change()
        val transformed = testSchema.schemaPrinter.print(transformedSchema)
        transformed shouldBe original
    }

    @Test
    fun `Ineffective filters are not added`() {
        val original = testSchema.schemaPrinter.print(testSchema.oldSchema)
        val transformedSchema = AddQueryToSchema(OperatorRegistry(emptyList())).transform(testSchema.oldSchema)
        val transformed = testSchema.schemaPrinter.print(transformedSchema)
        transformed shouldBe original
    }

    @Test
    fun `Filters are added for default operators`() {
        val original = testSchema.schemaPrinter.print(testSchema.oldSchema)
        val transformedSchema = AddQueryToSchema(defaultOperators).transform(testSchema.oldSchema)
        val transformed = testSchema.schemaPrinter.print(transformedSchema)
        transformed shouldNotBe original
    }
}