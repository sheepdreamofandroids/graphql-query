package net.bloemsma.graphql.query

import graphql.Scalars
import graphql.language.VariableReference
import graphql.schema.*
import net.bloemsma.graphql.query.operators.AndOfFields
import net.bloemsma.graphql.query.operators.NonNull
import net.bloemsma.graphql.query.operators.Not
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf


class OperatorRegistry(private val ops: Iterable<OperatorProducer>) {
    fun <R : Any> applicableTo(resultType: KClass<R>, context: GraphQLOutputType): Iterable<Operator<R>> =
        ops.flatMap {
            val produce: Iterable<Operator<R>> = it.produce(resultType, context, this)
            produce
        }
}

interface OperatorProducer {
    fun <R : Any> produce(
        resultType: KClass<R>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<R>>

}

//TODO split into Operator (implementation) and OperatorGroup (produces Operator)
// Or better, make the simple case (group of 1) a subtype of Operator
interface Operator<R : Any> : OperatorProducer {
    val name: String
    fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType): Boolean
    override fun <R2 : Any> produce(
        resultType: KClass<R2>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<R2>> =
        if (canProduce(resultType, contextType)) listOf(this as Operator<R2>) else emptyList()

    fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    )

    val compile: (param: Query, schemaFunction: SchemaFunction<R>, context: GraphQLOutputType) -> QueryFunction<R>?

    //    fun compile(expr: Value<*>): (Any) -> Any
    fun expand(): List<Operator<R>> = listOf(this)
}

class SimpleOperator<R : Any>(
    override val name: String,
    val resultClass: KClass<*>,
    val contextType: GraphQLOutputType,
    val parameterType: GraphQLInputType,
    private val description: String? = null,
    override val compile: (param: Query, schemaFunction: SchemaFunction<R>, context: GraphQLOutputType) -> QueryFunction<R>?
) : Operator<R> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType): Boolean {
        return resultType == resultClass && this.contextType == contextType
    }

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.addField {
            name(name).type(parameterType)
            description?.run { description(this) }
        }
    }

    override fun toString() = "$name($contextType, $parameterType)->$resultClass // $description"
}

fun GraphQLObjectType.Builder.addField(block: GraphQLFieldDefinition.Builder.() -> Unit): GraphQLObjectType.Builder =
    field { it.apply(block) }

fun GraphQLInputObjectType.Builder.addField(block: GraphQLInputObjectField.Builder.() -> Unit): GraphQLInputObjectType.Builder =
    field { it.apply(block) }

inline fun <reified R : Any, reified P : Any, reified C : Any> operator(
    name: String,
    noinline body: (context: C, parameter: P) -> R
): Operator<R> {
    val resultClass = R::class
    val contextClass = C::class
    val parameterClass = P::class
    return simpleOperator(
        name,
        resultClass,
        contextClass,
        parameterClass,
        body
    )
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
    val fromParam: (Any?) -> P? = resultTo(parameterClass)
    val fromContext: (Any?) -> C? = resultTo(contextClass)
    return simpleOperator(
        name,
        resultClass,
        contextClass,
        fromContext,
        parameterClass,
        fromParam,
        body
    )
}

fun <C : Any, P : Any, R : Any> simpleOperatorImpl(
    name: String,
    resultClass: KClass<*>,
    contextClass: KClass<*>,
    parameterClass: KClass<*>,
    body: (context: C, parameter: P) -> R
): SimpleOperator<R> {
    val fromParam: (Any?) -> P? = resultTo(parameterClass)
    val fromContext: (Any?) -> C? = resultTo(contextClass)
    return simpleOperator(
        name,
        resultClass,
        contextClass,
        fromContext,
        parameterClass,
        fromParam,
        body
    )
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
        compile = { param: Query, _, _ ->
            { c: Result?, v: Variables ->
                body(
                    fromContext(c) ?: throw Exception("Cannot convert from $c to $contextClass"),
                    valueOrVariable(fromParam, param, v, parameterClass)
                )
            }.showingAs { "$name ${fromParam(param)}" }
        }
    )
}

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
    Int::class to Scalars.GraphQLInt,
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
                bsp<Any>("eq") { a, b -> a == b },
                bsp<Comparable<Comparable<*>>>("gt") { a, b -> a > b },
                bsp<Comparable<Comparable<*>>>("gte") { a, b -> a >= b },
                bsp<Comparable<Comparable<*>>>("lt") { a, b -> a < b },
                bsp<Comparable<Comparable<*>>>("lte") { a, b -> a <= b }
            )
        }
    }
            + Not()
            + AndOfFields()
            + NonNull()

)

/** Binary (2 parameter), symmetric(both the same type) predicate*/
private inline fun <reified T : Any> KClass<*>.bsp(name: String, noinline body: (T, T) -> Boolean) =
    simpleOperator(name, Boolean::class, this, this, body)

fun <T : Any> KClass<T>.toGraphQlOutput(): GraphQLScalarType = toGraphQlInput()

fun <T : Any> KClass<T>.toGraphQlInput(): GraphQLScalarType =
    builtins[this]
        ?: throw Exception("$this cannot be mapped to a GraphQLInputType")


