package net.bloemsma.graphql.query

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import graphql.ExecutionInput
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.resolveResource
import io.ktor.response.respond
import io.ktor.response.respondOutputStream
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer

fun main() {
    server().start(wait = true)
}

private val UTF8 = Charsets.UTF_8

fun server(testSchema: TestSchema = TestSchema()) = embeddedServer(CIO, 8080) {
    routing {
        get("/") {
            call.resolveResource(
                path = "index.html",
                resourcePackage = "static"
            )?.let { call.respond(it) }
                ?: call.respond(HttpStatusCode.NotFound, "Oops, where did my homepage go?")
        }
        post("/graphql") {
            try {
                val map: Map<String, Any> =
                    InputStreamReader(call.request.receiveChannel().toInputStream(), UTF8).jsonToMap()
                val query = map["query"] as? String
                val variables = map["variables"] as? Map<String, Any> ?: mapOf()
                val operationName = map["operationName"] as? String
                val executionInput = ExecutionInput
                    .newExecutionInput(query)
                    .operationName(operationName)
                    .variables(variables)
                    .build()
                val executionResult = testSchema.graphQL.execute(executionInput)
                call.respondOutputStream(contentType = ContentType.Application.Json, status = HttpStatusCode.OK) {
                    OutputStreamWriter(this, UTF8).use { executionResult.toSpecification().writeAsJsonTo(it) }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        get("/schema") {
            call.respondText { testSchema.schemaPrinter.print(testSchema.oldSchema) }
        }
    }
}


private fun getVariables(variables: Any?): Map<String, Any> {
    if (variables is Map<*, *>) {
        val pairs = variables.map { it.key as String to it.value as Any }
        return hashMapOf(*pairs.toTypedArray())
    }

    return hashMapOf()
}

private fun Reader.jsonToMap(): Map<String, Any> =
    gson.fromJson<Map<String, Any>>(this, typeOfStringMap) ?: mapOf()

private fun Any.writeAsJsonTo(writer: Writer) = gson.toJson(this, writer)

private val gson = GsonBuilder().serializeNulls().create()

private val typeOfStringMap = (object : TypeToken<Map<String, Any>>() {}).type
