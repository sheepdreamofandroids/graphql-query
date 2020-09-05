package net.bloemsma.graphql.query

import org.slf4j.LoggerFactory

val debug: Boolean = System.getProperty("debug")?.toBoolean() ?: false

val logger = LoggerFactory.getLogger("graphql.query")

inline fun <T> T.logDebug(msg: (T) -> (Any?)): T {
    if (logger.isDebugEnabled) logger.debug(msg(this)?.toString())
    return this
}

inline fun <REC, OUT> REC.trace(msg: () -> (Any?), result: () -> OUT): OUT {
    Unit.logDebug { "--> " + msg() }
    val o: OUT = result()
    Unit.logDebug { "<-- " + msg() + " : $o" }
    return o
}