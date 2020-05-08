package net.bloemsma

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.Scalars.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass

fun main() {
    val oldSchema = SchemaGenerator().makeExecutableSchema(
        SchemaParser().parse(
            """
            type Query {
              myresult(simpleArg: String): [result]
            }
    
            type MyQuery {
              myresult(simpleArg: String): [result]
            }
            
            type result {
              b: Byte
              x: String
              y: Int
              z: [foo]
            }
            
            type foo {
              bar: Int
              }
            """
        ),
        RuntimeWiring.newRuntimeWiring().codeRegistry(
            GraphQLCodeRegistry.newCodeRegistry().dataFetcher(
                FieldCoordinates.coordinates("Query", "myresult"),
                DataFetcher { _ ->
                    listOf(
                        mapOf("x" to "y", "y" to 3, "z" to listOf(mapOf("bar" to 5))),
                        mapOf("x" to "x", "y" to 5, "z" to listOf(mapOf("bar" to 6)))
                    )
                })
        ).build()
    )

    val schemaPrinter =
        SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeDirectives(false).includeIntrospectionTypes(false))
    println(schemaPrinter.print(oldSchema))

//    val transformedSchema =
//        SchemaTransformer.transformSchema(oldSchema, AddQueryToSchema(ops))
//    println("=========================")
//    println(schemaPrinter.print(transformedSchema))

    val graphQL = GraphQL.newGraphQL(oldSchema).instrumentation(FilterInstrumentation(ops, "_filter")).build()
    val executionResult = graphQL.execute(
        ExecutionInput.newExecutionInput(
            """
            {
              myresult(_filter: {x: {eq: "x"}, y: {gt: 3}}) {
                ixxi: x
                y
                z {bar}
              } 
            }""".trimMargin()
        )
    )
    println(executionResult.toSpecification())
}

class OperatorRegistry(val operators: Iterable<Operator>) {
    //    private val map<KClass<*>,
    fun operator(resultType: KClass<*>, context: GraphQLOutputType): Iterable<Operator> =
        operators.filter { it.canProduce(resultType, context) }
}

interface Operator {
    fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType): Boolean
    fun makeField(into: GraphQLInputObjectType.Builder)
}

class SimpleOperator(
    val name: String,
    val resultClass: KClass<*>,
    val fieldType: GraphQLInputType,
    val parameterType: GraphQLInputType
) : Operator {
    override fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType): Boolean {
        return resultType == resultClass && fieldType == inputType
    }

    override fun makeField(into: GraphQLInputObjectType.Builder) {
        into.field { it.name(name).type(parameterType) }
    }
}

inline fun <reified O, reified F : Any, reified I : Any> operator(name: String, body: (F, I) -> O): Operator =
    SimpleOperator(
        name = name,
        resultClass = O::class,
        fieldType = F::class.toGraphQlInput(),
        parameterType = I::class.toGraphQlInput()
    )

val builtins: Map<KClass<*>, GraphQLScalarType> = mapOf(
    Boolean::class to GraphQLBoolean,
    Byte::class to GraphQLByte,
    Short::class to GraphQLShort,
    Int::class to GraphQLInt,
    Long::class to GraphQLLong,
    Double::class to GraphQLFloat,
// WTF?    Double::class to GraphQLDouble,
    BigInteger::class to GraphQLBigInteger,
    BigDecimal::class to GraphQLBigDecimal,
    Char::class to GraphQLChar,
    String::class to GraphQLString
)

val ops = OperatorRegistry(
    builtins.map { (resultType, parameterType) -> SimpleOperator("eq", Boolean::class, parameterType, parameterType) }
            + builtins.map { (resultType, parameterType) ->
        SimpleOperator(
            "gt",
            Boolean::class,
            parameterType,
            parameterType
        )
    }
            + builtins.map { (resultType, parameterType) ->
        SimpleOperator(
            "gte",
            Boolean::class,
            parameterType,
            parameterType
        )
    }
            + builtins.map { (resultType, parameterType) ->
        SimpleOperator(
            "lt",
            Boolean::class,
            parameterType,
            parameterType
        )
    }
            + builtins.map { (resultType, parameterType) ->
        SimpleOperator(
            "lte",
            Boolean::class,
            parameterType,
            parameterType
        )
    }

)

fun <T : Any> KClass<T>.toGraphQlInput(): GraphQLScalarType =
    builtins[this] ?: throw Exception("$this cannot be mapped to a GraphQLInputType")


class AddQueryToSchema(val operators: OperatorRegistry) : GraphQLTypeVisitorStub() {
    val functions: MutableSet<String> = mutableSetOf()

    fun GraphQLOutputType.function(kClass: KClass<*>): GraphQLInputType {
        val typeName = makeName()
        val predicateName = "${typeName}__to__" + kClass.simpleName
        return when {
            functions.contains(typeName) -> GraphQLTypeReference.typeRef(predicateName)
                .also { println("Refering to $predicateName") }
            else -> {
                functions.add(typeName)
                println("Creating type $predicateName")
                GraphQLInputObjectType.newInputObject()
                    .name(predicateName)
                    .also { query ->
                        operators.operator(kClass, this).forEach { it.makeField(query) }
                        when (this) {
                            is GraphQLObjectType -> fieldDefinitions.forEach { field ->
                                query.field {
                                    it.name(field.name)
                                    it.type(field.type.function(kClass))
                                }
                            }
                            is GraphQLList -> {
                                query.field { it.name("size").type(GraphQLInt.function(kClass)) }
                                query.field {
                                    wrappedType.testableType()?.run {
                                        it.name("any").type(this.function(kClass))
                                    }
                                    it
                                }
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
            }
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
}


