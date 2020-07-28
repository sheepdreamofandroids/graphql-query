package net.bloemsma.graphql.query

val debug: Boolean = System.getProperty("debug")?.toBoolean() ?: false
inline fun <T> T.logln(msg: (T) -> (Any?)): T {
    if (debug) println(msg(this))
    return this
}

//inline fun <T> trace(msg: () -> (Any?), result: () -> T): T {
//    Unit.logln { "--> " + msg() }
//    val t: T = result()
//    Unit.logln { "<-- " + msg() + " : $t" }
//    return t
//}

inline fun <REC, OUT> REC.trace(msg: REC.() -> (Any?), result: REC.() -> OUT): OUT {
    Unit.logln { "--> " + msg() }
    val o: OUT = result()
    Unit.logln { "<-- " + msg() + " : $o" }
    return o
}