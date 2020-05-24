package net.bloemsma.graphql.query

import graphql.Scalars
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.language.VariableReference
import graphql.schema.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf


class OperatorRegistry(val operators: Iterable<Operator<*>>) {
    //    private val map<KClass<*>,
    fun <R : Any> applicableTo(resultType: KClass<R>, context: GraphQLOutputType): Iterable<Operator<R>> =
        operators.filter { it.canProduce(resultType, context) } as Iterable<Operator<R>>
}

interface Operator<R : Any> {
    val name: String
    fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType): Boolean
    fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> GraphQLInputType
    )

    val compile: (param: Query, schemaFunction: SchemaFunction<R>) -> QueryFunction<R>
//    fun compile(expr: Value<*>): (Any) -> Any
}

class SimpleOperator<R : Any>(
    override val name: String,
    val resultClass: KClass<*>,
    val contextType: GraphQLOutputType,
    val parameterType: GraphQLInputType,
    val description: String? = null,
    override val compile: (param: Query, schemaFunction: SchemaFunction<R>) -> QueryFunction<R>
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

    override fun toString() = "$name($contextType, $parameterType)->$resultClass // $description"
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
    val fromParam: (Any?) -> P? = converterTo(parameterClass)
    val fromContext: (Any?) -> C? = converterTo(contextClass)
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
    val fromParam: (Any?) -> P? = converterTo(parameterClass)
    val fromContext: (Any?) -> C? = converterTo(contextClass)
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

    override val compile: (param: Query, schemaFunction: SchemaFunction<Boolean>) -> QueryPredicate =
        { param: Query, schemaFunction: SchemaFunction<Boolean> ->
            val tests: List<(context: Result, variables: Map<String, Any?>) -> Any> =
                (param as? ObjectValue)?.objectFields?.mapNotNull { objectField ->
                    schemaFunction.operators.find { it.name == objectField.name }
                        ?.compile?.invoke(objectField.value, schemaFunction)
                } ?: throw Exception("Must be object")
            ;
            { context: Result, variables: Variables ->
                tests.all {
                    it.invoke(context, variables).asBoolean() ?: throw Exception("Expecting only booleans")
                }
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

    override val compile: (param: Query, schemaFunction: SchemaFunction<Boolean>) -> QueryPredicate
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

    override val compile: (param: Query, schemaFunction: SchemaFunction<Boolean>) -> QueryPredicate
        get() = TODO("Not yet implemented")
    override val name: String = "_ANY"
}

fun <T : Any> KClass<T>.toGraphQlOutput(): GraphQLScalarType = toGraphQlInput()

fun <T : Any> KClass<T>.toGraphQlInput(): GraphQLScalarType =
    builtins[this]
        ?: throw Exception("$this cannot be mapped to a GraphQLInputType")


