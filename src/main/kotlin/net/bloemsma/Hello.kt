package net.bloemsma

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.Scalars.*
import graphql.language.Value
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
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

    val graphQL = GraphQL.newGraphQL(oldSchema).instrumentation(
        FilterInstrumentation(
            ops, "_filter", SchemaPrinter(
                SchemaPrinter.Options.defaultOptions().includeDirectives(false).includeIntrospectionTypes(false)
            )
        )
    ).build()
    val executionResult = graphQL.execute(
        ExecutionInput.newExecutionInput(
            """
            {
              myresult(_filter: {x: {eq: "x"}, y: {gt: 3, lt: 9}}) {
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
    fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    )

    fun compile(expr: Value<*>): (Any) -> Any
}

class SimpleOperator<P, F, R : Any>(
    val name: String,
    val resultClass: KClass<R>,
    val fieldType: GraphQLInputType,
    val parameterType: GraphQLInputType,
    val body: (P, F) -> R
) : Operator {
    override fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType): Boolean {
        return resultType == resultClass && fieldType == inputType
    }

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    ) {
        into.field { it.name(name).type(parameterType) }
    }

    override fun compile(parm: Value<*>): (Any) -> Any {
        parm.namedChildren
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
    builtins.map { (_, i) ->
        SimpleOperator(
            "eq",
            Boolean::class,
            i,
            i
        ) { a: Any, b: Any ->
            a === b || (a != null && b != null && a.equals(b))
        }
    }
            + builtins.map { (_, i) ->
        SimpleOperator("gt", Boolean::class, i, i) { a: Comparable<*>, b: Comparable<*> ->
            a != null && b != null && a.compareTo(b) > 0
        }
    }
            + builtins.map { (_, i) -> SimpleOperator("gte", Boolean::class, i, i) }
            + builtins.map { (_, i) -> SimpleOperator("lt", Boolean::class, i, i) }
            + builtins.map { (_, i) -> SimpleOperator("lte", Boolean::class, i, i) }
            + AndOfFields()
            + AnyOfList()

)

class AndOfFields : Operator {
    override fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType) =
        resultType == Boolean::class && inputType is GraphQLObjectType

    override fun makeField(
        from: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    ) {
        (from as GraphQLObjectType).fieldDefinitions.forEach { field ->
            query.field {
                it.name(field.name)
                it.type(function(field.type, Boolean::class))
            }

        }
    }
}

class AnyOfList : Operator {
    override fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType) =
        resultType == Boolean::class && inputType is GraphQLList

    override fun makeField(
        from: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    ) {
        query.field {
            (from as GraphQLList).wrappedType.testableType()?.run {
                it.name("any").type(function(this, Boolean::class))
            }
        }
    }
}

fun <T : Any> KClass<T>.toGraphQlInput(): GraphQLScalarType =
    builtins[this] ?: throw Exception("$this cannot be mapped to a GraphQLInputType")


