package net.bloemsma.graphql.query

import graphql.language.BooleanValue
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass


inline fun <reified T : Any> converterTo(): (Any?) -> T? {
    return converterTo(T::class)
}

fun <T : Any> converterTo(kClass: KClass<*>): (Any?) -> T? {
    return when (kClass) {
        Boolean::class -> Any?::asBoolean
        Int::class -> Any?::asInt
        Long::class -> Any?::asLong
        Double::class -> Any?::asDouble
        String::class -> Any?::asString
        Byte::class -> Any?::asByte
        else -> throw Exception("Cannot convert to $kClass")
    } as (Any?) -> T?
}

fun Any?.asBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is BooleanValue -> isValue
    else -> null
}

fun Any?.asLong(): Long? = when (this) {
    is Number -> toLong()
    is BigInteger -> longValueExact()
    else -> null
}

fun Any?.asInt(): Int? = when (this) {
    is Number -> toInt()
    is BigInteger -> intValueExact()
    else -> null
}

fun Any?.asByte(): Byte? = when (this) {
    is Number -> toByte()
    is BigInteger -> byteValueExact()
    else -> null
}

fun Any?.asDouble(): Double? = when (this) {
    is Number -> toDouble()
    is BigDecimal -> toDouble()
    else -> null
}

fun Any?.asString(): String? = this?.toString()
