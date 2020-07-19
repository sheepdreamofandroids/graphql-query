package net.bloemsma.graphql.query

import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.StringValue
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass


inline fun <reified T : Result> converterTo(): (Result?) -> T? {
    return converterTo(T::class)
}

fun <T : Result> converterTo(kClass: KClass<*>): (Result?) -> T? {
    return when (kClass) {
        Boolean::class -> Result?::asBoolean
        Int::class -> Result?::asInt
        Long::class -> Result?::asLong
        Double::class -> Result?::asDouble
        String::class -> Result?::asString
        Byte::class -> Result?::asByte
        Any::class -> Result?::asAny
        else -> throw Exception("Cannot convert to $kClass")
    } as (Result?) -> T?
}

fun Result?.asBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is BooleanValue -> isValue
    else -> null
}

fun Result?.asLong(): Long? = when (this) {
    is Number -> toLong()
    is BigInteger -> longValueExact()
    is IntValue -> value.longValueExact()
    else -> null
}

fun Result?.asInt(): Int? = when (this) {
    is Number -> toInt()
    is BigInteger -> intValueExact()
    is IntValue -> value.intValueExact()
    else -> null
}

fun Result?.asByte(): Byte? = when (this) {
    is Number -> toByte()
    is BigInteger -> byteValueExact()
    is IntValue -> value.byteValueExact()
    else -> null
}

fun Result?.asDouble(): Double? = when (this) {
    is Number -> toDouble()
    is BigDecimal -> toDouble()
    is FloatValue -> value.toDouble()
    else -> null
}

fun Result?.asString(): String? = when (this) {
    null -> "null"
    is StringValue -> value
    else -> toString()
}
fun Result?.asAny(): Any? = this
