package net.bloemsma

import graphql.Scalars
import graphql.language.BooleanValue
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.language.VariableReference
import graphql.schema.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf


class OperatorRegistry(val operators: Iterable<Operator<*>>) {
    //    private val map<KClass<*>,
    fun applicableTo(resultType: KClass<*>, context: GraphQLOutputType): Iterable<Operator<*>> =
        operators.filter { it.canProduce(resultType, context) }
}

typealias Variables = Map<String, *>
typealias Result = Any
typealias Query = Value<*>

interface Operator<R : Any> {
    val name: String
    fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType): Boolean
    fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    )

    val compile: (param: Query, schemaFunction: SchemaFunction) -> (context: Result, variables: Variables) -> R
//    fun compile(expr: Value<*>): (Any) -> Any
}

class SimpleOperator<R : Any>(
    override val name: String,
    val resultClass: KClass<*>,
    val contextType: GraphQLOutputType,
    val parameterType: GraphQLInputType,
    val description: String? = null,
    override val compile: (param: Query, schemaFunction: SchemaFunction) -> (context: Result, variables: Variables) -> R
) : Operator<R> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType): Boolean {
        return resultType == resultClass && this.contextType == contextType
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
): Operator<R> {
    val resultClass = R::class
    val contextClass = C::class
    val parameterClass = P::class
    return simpleOperator(name, resultClass, contextClass, parameterClass, body)
}


/** Operator with non-null parameters */
inline fun <reified C : Any, reified P : Any, reified R : Any> simpleOperator(
    name: String,
    resultClass: KClass<*>,
    contextClass: KClass<*>,
    parameterClass: KClass<*>,
    noinline body: (context: C, parameter: P) -> R
): SimpleOperator<R> {
    assert(R::class.isSubclassOf(resultClass))
    assert(parameterClass.isSubclassOf(P::class))
    assert(contextClass.isSubclassOf(C::class))
    val fromParam: (Any?) -> P? = az(parameterClass)
    val fromContext: (Any?) -> C? = az(contextClass)
    return simpleOperator(name, resultClass, contextClass, fromContext, parameterClass, fromParam, body)
}

fun <C : Any, P : Any, R : Any> simpleOperatorImpl(
    name: String,
    resultClass: KClass<*>,
    contextClass: KClass<*>,
    parameterClass: KClass<*>,
    body: (context: C, parameter: P) -> R
): SimpleOperator<R> {
    val fromParam: (Any?) -> P? = az(parameterClass)
    val fromContext: (Any?) -> C? = az(contextClass)
    return simpleOperator(name, resultClass, contextClass, fromContext, parameterClass, fromParam, body)
}

fun <C : Any, P : Any, R : Any> simpleOperator(
    name: String,
    resultClass: KClass<*>,
    contextClass: KClass<*>,
    fromContext: (Any?) -> C?,
    parameterClass: KClass<*>,
    fromParam: (Any?) -> P?,
    body: (context: C, parameter: P) -> R
): SimpleOperator<R> {
    return SimpleOperator(
        name = name,
        resultClass = resultClass,
        contextType = contextClass.toGraphQlOutput(),
        parameterType = parameterClass.toGraphQlInput(),
        compile = { param: Query, _ ->
            { c: Result, v: Variables ->
                body(
                    fromContext(c) ?: throw Exception("Cannot convert from $c to $contextClass"),
                    valueOrVariable(fromParam, param, v, parameterClass)
                )
            }
        }
    )
}

inline fun <reified T : Any> az(): (Any?) -> T? {
    return az(T::class)
}

fun <T : Any> az(kClass: KClass<*>): (Any?) -> T? {
    return when (kClass) {
        Boolean::class -> ::asBoolean
        Long::class -> (::asLong)
        Double::class -> (::asDouble)
        String::class -> (::asString)
        Byte::class -> (::asByte)
        else -> throw Exception("Cannot convert to $kClass")
    } as (Any?) -> T?
}

fun asBoolean(any: Any?): Boolean? = when (any) {
    is Boolean -> any
    is BooleanValue -> any.isValue
    else -> null
}

fun asLong(any: Any?): Long? = when (any) {
    is Number -> any.toLong()
    is BigInteger -> any.longValueExact()
    else -> null
}

fun asByte(any: Any?): Byte? = when (any) {
    is Number -> any.toByte()
    is BigInteger -> any.byteValueExact()
    else -> null
}

fun asDouble(any: Any?): Double? = when (any) {
    is Number -> any.toDouble()
    is BigDecimal -> any.toDouble()
    else -> null
}

fun asString(any: Any?): String? = any?.toString()

inline fun <reified T : Any> valueOrVariable(noinline convert: (Any?) -> T?, any: Any, vars: Variables): T =
    valueOrVariable(convert, any, vars, T::class)

fun <T : Any> valueOrVariable(convert: (Any?) -> T?, any: Any, vars: Variables, t: KClass<*>): T =
    convert(any)
        ?: (any as? VariableReference)?.run { convert(vars[name]) }
        ?: throw Exception("Cannot convert from $any to $t")


val builtins: Map<KClass<*>, GraphQLScalarType> = mapOf(
    Boolean::class to Scalars.GraphQLBoolean,
    Byte::class to Scalars.GraphQLByte,
//    Short::class to Scalars.GraphQLShort,
//    Int::class to Scalars.GraphQLInt,
    Long::class to Scalars.GraphQLLong,
    Double::class to Scalars.GraphQLFloat,
// WTF?    Double::class to GraphQLDouble,
//    BigInteger::class to Scalars.GraphQLBigInteger,
//    BigDecimal::class to Scalars.GraphQLBigDecimal,
//    Char::class to Scalars.GraphQLChar,
    String::class to Scalars.GraphQLString
)

val ops = OperatorRegistry(
    builtins.flatMap { (kClass, gqlType) ->
        kClass.run {
            listOf(
                bsp("eq") { a: Any, b: Any -> a == b },
                bsp("gt") { a: Comparable<*>, b: Comparable<*> -> a.compareTo(b as Nothing) > 0 },
                bsp("gte") { a: Comparable<*>, b: Comparable<*> -> a.compareTo(b as Nothing) >= 0 },
                bsp("lt") { a: Comparable<*>, b: Comparable<*> -> a.compareTo(b as Nothing) < 0 },
                bsp("lte") { a: Comparable<*>, b: Comparable<*> -> a.compareTo(b as Nothing) <= 0 }
            )
        }
    }
            + AndOfFields()
//            + AndOfFields()
//        +AnyOfList()
//            + OrOfFields()

)

/** Binary (2 parameter), symmetric(both the same type) predicate*/
private inline fun <reified T : Any> KClass<*>.bsp(name: String, noinline body: (T, T) -> Boolean) =
    simpleOperator(name, Boolean::class, this, this, body)

class AndOfFields : Operator<Boolean> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class && contextType is GraphQLObjectType

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

    override val compile: (param: Query, schemaFunction: SchemaFunction) -> (context: Result, variables: Variables) -> Boolean =
        { param: Query, schemaFunction: SchemaFunction ->
            (param as? ObjectValue)?.objectFields?.map {
                val name = it.name
                { context: Result, variables: Variables -> context.getField(name) }
            }
        }

    //        get() = TODO("Not yet implemented")
    override val name: String = "and of fields"

}

class OrOfFields : Operator<Boolean> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class && contextType is GraphQLObjectType

    override fun makeField(
        from: GraphQLOutputType,
        query: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    ) {
        query.field {
            it.name("_OR").type(GraphQLList(function(from, Boolean::class)))
        }
    }

    override val compile: (param: Query, schemaFunction: SchemaFunction) -> (context: Result, variables: Variables) -> Boolean
        get() = TODO("Not yet implemented")
    override val name: String = "_OR"
}

class AnyOfList : Operator<Boolean> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class && contextType is GraphQLList

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

    override val compile: (param: Query, schemaFunction: SchemaFunction) -> (context: Result, variables: Variables) -> Boolean
        get() = TODO("Not yet implemented")
    override val name: String = "_ANY"
}

fun <T : Any> KClass<T>.toGraphQlOutput(): GraphQLScalarType = toGraphQlInput()

fun <T : Any> KClass<T>.toGraphQlInput(): GraphQLScalarType =
    builtins[this]
        ?: throw Exception("$this cannot be mapped to a GraphQLInputType")


