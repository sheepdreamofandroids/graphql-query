package net.bloemsma.graphql.query

import graphql.Scalars
import graphql.language.VariableReference
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import net.bloemsma.graphql.query.operators.AndOfFields
import net.bloemsma.graphql.query.operators.ListOperators
import net.bloemsma.graphql.query.operators.Not
import net.bloemsma.graphql.query.operators.Nullability
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf


class OperatorRegistry(private val ops: Iterable<OperatorProducer>) {
    @JvmOverloads
    fun <R : Any> applicableTo(
        resultType: KClass<R>,
        context: GraphQLOutputType,
        producerFilter: (OperatorProducer) -> Boolean = { true }
    ): Iterable<Operator<R>> = trace({ "applicableto $context -> $resultType" }) {
        ops
            .filter(producerFilter)
            .flatMap {
                trace({ "$it product for $context -> $resultType" }) {
                    it.produce(resultType, context, this)
                }
            }
    }
}

interface OperatorProducer {
    fun <R : Any> produce(
        resultType: KClass<R>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<R>>

}

interface Operator<R : Any> : OperatorProducer {
    val name: String
    fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType): Boolean
    @Suppress("UNCHECKED_CAST") // there is a check on resultType
    override fun <R : Any> produce(
        resultType: KClass<R>,
        contextType: GraphQLOutputType,
        operatorRegistry: OperatorRegistry
    ): Iterable<Operator<R>> =
        if (canProduce(resultType, contextType)) listOf(this as Operator<R>) else emptyList()

    fun makeField(
        contextType: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    )

    fun compile(param: Query, schemaFunction: SchemaFunction<R>, contextType: GraphQLOutputType): QueryFunction<R>?

}

abstract class SuperSimpleOperator<R : Any>(
    override val name: String,
    val parameterType: GraphQLInputType,
    private val description: String? = null
) : Operator<R> {
    override fun makeField(
        contextType: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.addField {
            name(name).type(parameterType)
            description?.run { description(this) }
        }
    }
}

abstract class SimpleOperator<R : Any>(
    override val name: String,
    val resultClass: KClass<R>,
    val contextType: GraphQLOutputType,
    val parameterType: GraphQLInputType,
    private val description: String? = null
) : Operator<R> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType): Boolean {
        return resultType == resultClass && this.contextType == contextType
    }

    override fun makeField(
        contextType: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.addField {
            name(name).type(parameterType)
            description?.run { description(this) }
        }
    }

    override fun toString() =
        "$name(${contextType.makeName()}, ${parameterType.makeName()})->${resultClass.simpleName} // $description"
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
    return scalarOperator(
        name,
        resultClass,
        contextClass,
        parameterClass,
        body
    )
}


/** Operator with non-null parameters */
inline fun <reified C : Any, reified P : Any, reified R : Any> scalarOperator(
    name: String,
    resultClass: KClass<R>,
    contextClass: KClass<C>,
    parameterClass: KClass<P>,
    noinline body: (context: C, parameter: P) -> R
): SimpleOperator<R> {
    assert(R::class.isSubclassOf(resultClass))
    assert(parameterClass.isSubclassOf(P::class))
    assert(contextClass.isSubclassOf(C::class))
    val fromParam: (Any?) -> P? = converterTo(parameterClass)
    val fromContext: (Any?) -> C? = converterTo(contextClass)
    return ScalarOperator(
        name,
        resultClass,
        contextClass,
        fromContext,
        parameterClass,
        fromParam,
        body
    )
}

class ScalarOperator<C : Any, P : Any, R : Any>(
    name: String,
    resultClass: KClass<R>,
    private val contextClass: KClass<C>,
    private val fromContext: (Any?) -> C?,
    private val parameterClass: KClass<P>,
    private val fromParam: (Any?) -> P?,
    private val body: (context: C, parameter: P) -> R
) : SimpleOperator<R>(
    name = name,
    resultClass = resultClass,
    contextType = contextClass.toGraphQlOutput(),
    parameterType = parameterClass.toGraphQlInput()
) {
    override fun compile(
        param: Query,
        schemaFunction: SchemaFunction<R>,
        contextType: GraphQLOutputType
    ): QueryFunction<R>? =
        { c: Result?, v: Variables ->
            body(
                fromContext(c) ?: throw Exception("Cannot convert from $c to $contextClass"),
                valueOrVariable(fromParam, param, v, parameterClass)
            )
        }.showingAs { "$name ${fromParam(param)}" }


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
    Int::class to Scalars.GraphQLInt,
    Long::class to Scalars.GraphQLLong,
    Double::class to Scalars.GraphQLFloat,
    String::class to Scalars.GraphQLString
)

val comparisons: List<SimpleOperator<Boolean>> = builtins.flatMap { (kClass, _) ->
    @Suppress("UNCHECKED_CAST") // statically know, will fail on test if wrong
    (kClass as KClass<Comparable<Comparable<*>>>).run {
        listOf(
            (this as KClass<Any>).binarySymmetricOperator("eq") { a, b -> a == b },
            binarySymmetricOperator("gt") { a, b -> a > b },
            binarySymmetricOperator("gte") { a, b -> a >= b },
            binarySymmetricOperator("lt") { a, b -> a < b },
            binarySymmetricOperator("lte") { a, b -> a <= b }
        )
    }
}

val algebraicOperators =
    numop("plus", { a, b -> a + b }, { a, b -> a + b }) +
            numop("minus", { a, b -> a - b }, { a, b -> a - b }) +
            numop("times", { a, b -> a * b }, { a, b -> a * b }) +
            numop("div", { a, b -> a / b }, { a, b -> a / b }) +
            numop("rem", { a, b -> a % b }, { a, b -> a % b })

val defaultOperators = OperatorRegistry(
    comparisons
            + algebraicOperators
            + Not()
            + AndOfFields()
            + Nullability()
            + ListOperators()

)

/** Binary (2 parameters), symmetric(both the same type) operator*/
private inline fun <reified T : Any, reified R : Any> KClass<T>.binarySymmetricOperator(
    name: String,
    noinline body: (T, T) -> R
): SimpleOperator<R> =
    scalarOperator(name, R::class, this, this, body)

fun numop(name: String, intBody: (Int, Int) -> Int, doubleBody: (Double, Double) -> Double): List<SimpleOperator<*>> =
    listOf(op3(name, intBody), op3(name, doubleBody))

private inline fun <reified T : Any> op3(name: String, noinline body: (T, T) -> T): SimpleOperator<T> =
    scalarOperator(name, T::class, T::class, T::class, body)

fun <T : Any> KClass<T>.toGraphQlOutput(): GraphQLScalarType =
    toGraphQlInput()

fun <T : Any> KClass<T>.toGraphQlInput(): GraphQLScalarType =
    builtins[this]
        ?: throw Exception("$this cannot be mapped to a GraphQLInputType")


