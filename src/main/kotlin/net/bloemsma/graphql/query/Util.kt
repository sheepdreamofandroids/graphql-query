package net.bloemsma.graphql.query

val debug = false
inline fun <T> T.logln(msg: (T) -> (Any?)): T {
    if (debug) println(msg(this))
    return this
}
