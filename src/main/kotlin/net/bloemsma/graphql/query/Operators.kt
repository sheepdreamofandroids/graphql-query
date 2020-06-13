package net.bloemsma.graphql.query

import graphql.Scalars
import graphql.language.ObjectValue
import graphql.language.VariableReference
import graphql.schema.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf


class OperatorRegistry(private val ops: Iterable<Operator<*>>) {
    fun <R : Any> applicableTo(resultType: KClass<R>, context: GraphQLOutputType): Iterable<Operator<R>> =
        ops.flatMap {
            it.produce(resultType, context)
        }
}

//TODO split into Operator (implementation) and OperatorGroup (produces Operator)
interface Operator<R : Any> {
    val name: String
    fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType): Boolean
    fun <T : Any> produce(resultType: KClass<T>, contextType: GraphQLOutputType): Iterable<Operator<T>> =
        if (canProduce(resultType, contextType)) listOf(this as Operator<T>) else emptyList()

    fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    )

    val compile: (param: Query, schemaFunction: SchemaFunction<R>) -> QueryFunction<R>?

    //    fun compile(expr: Value<*>): (Any) -> Any
    fun expand(): List<Operator<R>> = listOf(this)
}

class SimpleOperator<R : Any>(
    override val name: String,
    val resultClass: KClass<*>,
    val contextType: GraphQLOutputType,
    val parameterType: GraphQLInputType,
    private val description: String? = null,
    override val compile: (param: Query, schemaFunction: SchemaFunction<R>) -> QueryFunction<R>
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
        compile = { param: Query, _ ->
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
//            + AndOfFields()
//        +AnyOfList()
//            + OrOfFields()

)

/** Binary (2 parameter), symmetric(both the same type) predicate*/
private inline fun <reified T : Any> KClass<*>.bsp(name: String, noinline body: (T, T) -> Boolean) =
    simpleOperator(name, Boolean::class, this, this, body)

class Not : Operator<Boolean> {
    override val name = "not"

    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.field {
            it.name("not")
            it.description("Negates it's argument.")
            it.type(function(from, Boolean::class).ref)
        }
    }

    override val compile = { param: Query, schemaFunction: SchemaFunction<Boolean> ->
        val innerPred =
            schemaFunction.functionFor(schemaFunction.contextQlType, Boolean::class).compile(null, param)
        ;{ r: Result?, v: Variables -> !innerPred(r, v) }
    }

}

class AndOfFields : Operator<Boolean> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
        resultType == Boolean::class && contextType is GraphQLObjectType

    override fun <T : Any> produce(resultType: KClass<T>, contextType: GraphQLOutputType): Iterable<Operator<T>> {
        return (contextType as? GraphQLObjectType)?.let { graphQLObjectType ->
            graphQLObjectType.fieldDefinitions.map { ObjectFieldOp(graphQLObjectType, it, resultType) }
        } ?: emptyList()
    }

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.description("This is true when all fields are true (AND).")
        (from as GraphQLObjectType).fieldDefinitions.forEach { field ->
            into.field {
                it.name(field.name)
                it.type(function(field.type, Boolean::class).ref)
            }

        }
    }

    override val compile = { param: Query, schemaFunction: SchemaFunction<Boolean> ->
        val tests: List<QueryPredicate> =
            (param as? ObjectValue)?.objectFields?.mapNotNull { objectField ->
                val fieldName = objectField.name
                val graphQLObjectType = schemaFunction.contextQlType as GraphQLObjectType
                val schemaFunction1 = schemaFunction.functionFor(
                    graphQLObjectType.getFieldDefinition(fieldName).type,
                    Boolean::class
                )
                val predicate: QueryFunction<Boolean> = schemaFunction1
                    .compile(fieldName, objectField.value)
                val qPredicate: QueryPredicate = { c, v ->
                    predicate(c?.getField(fieldName), v)
                }
                qPredicate
//                    schemaFunction.operators.find { it.name == objectField.name }
//                        ?.compile?.invoke(objectField.value, schemaFunction)
            } ?: throw Exception("Must be object")
        ;
        { context: Result?, variables: Variables -> tests.all { it.invoke(context, variables) } }
    }


    //        get() = TODO("Not yet implemented")
    override val name: String = "and of fields"

}

class ObjectFieldOp<R : Any>(
    private val graphQLObjectType: GraphQLObjectType,
    fieldDefinition: GraphQLFieldDefinition,
    resultType: KClass<R>
) : Operator<R> {
    override fun canProduce(resultType: KClass<*>, contextType: GraphQLOutputType) =
//        resultType == Boolean::class &&
        contextType == graphQLObjectType

    override fun makeField(
        from: GraphQLOutputType,
        into: GraphQLInputObjectType.Builder,
        function: (data: GraphQLOutputType, kClass: KClass<*>) -> SchemaFunction<*>
    ) {
        into.description("This is true when all fields are true (AND).")
        (from as GraphQLObjectType).fieldDefinitions.forEach { field ->
            into.field {
                it.name(field.name)
                it.type(function(field.type, Boolean::class).ref)
            }

        }
    }

    override val compile = { param: Query, schemaFunction: SchemaFunction<R> ->
//        (param as? ObjectValue)
//            ?.objectFields
//            ?.find { it.name == name }
//            ?.let { field: ObjectField ->
        schemaFunction
            .functionFor(
                graphQLObjectType.getFieldDefinition(name).type,
                resultType
            )
            .compile(null, param)
            .let { func ->
                { c: Result?, v: Variables ->
                    func(c?.getField(name), v)
                }.showingAs { "($name) " }
            }
    }
//            ;
//            { context: Result, variables: Variables -> fn(context,variables) }
//
//            objectFields?.mapNotNull { objectField ->
//                val fieldName = objectField.name
//                val graphQLObjectType = schemaFunction.contextQlType as GraphQLObjectType
//                val schemaFunction1 = schemaFunction.functionFor(
//                    graphQLObjectType.getFieldDefinition(fieldName).type,
//                    Boolean::class
//                )
//                val predicate: QueryFunction<Boolean> = schemaFunction1
//                    .compile(fieldName, objectField.value);
//                val qPredicate: QueryPredicate = { c, v ->
//                    predicate(c.getField(fieldName), v)
//                }
//                qPredicate
////                    schemaFunction.operators.find { it.name == objectField.name }
////                        ?.compile?.invoke(objectField.value, schemaFunction)
//            } ?: throw Exception("Must be object")
//        ;
//        { context: Result, variables: Variables -> tests.all { it.invoke(context, variables) } }
//    }


    //        get() = TODO("Not yet implemented")
    override val name: String = fieldDefinition.name

}

fun <T : Any> KClass<T>.toGraphQlOutput(): GraphQLScalarType = toGraphQlInput()

fun <T : Any> KClass<T>.toGraphQlInput(): GraphQLScalarType =
    builtins[this]
        ?: throw Exception("$this cannot be mapped to a GraphQLInputType")


