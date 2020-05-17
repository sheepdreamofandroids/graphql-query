package net.bloemsma

import graphql.Scalars
import graphql.language.Value
import graphql.schema.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass


class OperatorRegistry(val operators: Iterable<Operator>) {
    //    private val map<KClass<*>,
    fun applicableTo(resultType: KClass<*>, context: GraphQLOutputType): Iterable<Operator> =
        operators.filter { it.canProduce(resultType, context) }
}

typealias Variables = Map<String, *>
typealias Result = Any
typealias Query = Value<*>

interface Operator<R : Any> {
    fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType): Boolean
    fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    )

    val compile: (param: Query) -> (context: Result, variables: Variables) -> R
//    fun compile(expr: Value<*>): (Any) -> Any
}

class SimpleOperator<R : Any>(
    val name: String,
    val resultClass: KClass<R>,
    val contextType: GraphQLOutputType,
    val parameterType: GraphQLInputType,
    val description: String? = null,
    override val compile: (param: Query) -> (context: Result, variables: Variables) -> R
) : Operator<R> {
    override fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType): Boolean {
        return resultType == resultClass && contextType == inputType
    }

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    ) {
        into.addField {
            name(name).type(parameterType)
            description?.run { description(this) }
        }
    }
}

//    override fun compile(parm: Value<*>): (Any) -> Any {
//        parm.namedChildren
//    }


fun GraphQLObjectType.Builder.addField(block: GraphQLFieldDefinition.Builder.() -> Unit) =
    field { it.apply(block) }

fun GraphQLInputObjectType.Builder.addField(block: GraphQLInputObjectField.Builder.() -> Unit) =
    field { it.apply(block) }

inline fun <reified R : Any, reified P : Any, reified C : Any> operator(
    name: String,
    noinline body: (context: C, parameter: P) -> R
): Operator<R> =
    SimpleOperator(
        name = name,
        resultClass = R::class,
        contextType = C::class.toGraphQlOutput(),
        parameterType = P::class.toGraphQlInput(),
        compile = { param: Query ->k
            val p: (Variables) -> P = param.extractor()

            { c:Result,v:Variables -> body(c.extract(), p(v)) }
        }
    )

val builtins: Map<KClass<*>, GraphQLScalarType> = mapOf(
    Boolean::class to Scalars.GraphQLBoolean,
    Byte::class to Scalars.GraphQLByte,
    Short::class to Scalars.GraphQLShort,
    Int::class to Scalars.GraphQLInt,
    Long::class to Scalars.GraphQLLong,
    Double::class to Scalars.GraphQLFloat,
// WTF?    Double::class to GraphQLDouble,
    BigInteger::class to Scalars.GraphQLBigInteger,
    BigDecimal::class to Scalars.GraphQLBigDecimal,
    Char::class to Scalars.GraphQLChar,
    String::class to Scalars.GraphQLString
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
        SimpleOperator(
            "gt",
            Boolean::class,
            i,
            i
        ) { a: Comparable<Any>, b: Comparable<Any> -> a != null && b != null && a.compareTo(b) > 0 }
    }
            + builtins.map { (_, i) ->
        SimpleOperator(
            "gte",
            Boolean::class,
            i,
            i
        ) { a: Comparable<Any>, b: Comparable<Any> -> a != null && b != null && a.compareTo(b) >= 0 }
    }
            + builtins.map { (_, i) ->
        SimpleOperator(
            "lt",
            Boolean::class,
            i,
            i
        ) { a: Comparable<Any>, b: Comparable<Any> -> a != null && b != null && a.compareTo(b) < 0 }
    }
            + builtins.map { (_, i) ->
        SimpleOperator(
            "lte",
            Boolean::class,
            i,
            i
        ) { a: Comparable<Any>, b: Comparable<Any> -> a != null && b != null && a.compareTo(b) <= 0 }
    }
            + AndOfFields()
            + AndOfFields.AnyOfList()
//            + OrOfFields()

)

class AndOfFields : Operator<Any, Any, Boolean> {
    override fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType) =
        resultType == Boolean::class && inputType is GraphQLObjectType

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    ) {
        into.description("This is true when all fields are true (AND).")
        (from as GraphQLObjectType).fieldDefinitions.forEach { field ->
            into.field {
                it.name(field.name)
                it.type(function(field.type, Boolean::class))
            }

        }
    }

    override val compile = { (context: Any, param: Value<*>) ->
        O
    }
}

class OrOfFields : Operator {
    override fun canProduce(resultType: KClass<*>, inputType: GraphQLOutputType) =
        resultType == Boolean::class && inputType is GraphQLObjectType

    override fun makeField(
        from: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    ) {
        query.field {
            it.name("_OR").type(GraphQLList(function(from, Boolean::class)))
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

fun <T : Any> KClass<T>.toGraphQlOutput(): GraphQLScalarType = toGraphQlInput()

fun <T : Any> KClass<T>.toGraphQlInput(): GraphQLScalarType =
    builtins[this]
        ?: throw Exception("$this cannot be mapped to a GraphQLInputType")


