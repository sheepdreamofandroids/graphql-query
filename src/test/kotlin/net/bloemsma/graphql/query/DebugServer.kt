package net.bloemsma.graphql.query

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.resolveResource
import io.ktor.request.path
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
    server.start(wait = true)
}

private val UTF8 = Charsets.UTF_8

private val server = embeddedServer(CIO, 8080) {
    routing {
        get("/") {
            call.resolveResource("index.html", "static")
//            call.respondText("Hello, world!", ContentType.Text.Html)
            call.res
        }
        post("/graphql") {
            val map: Map<String, Any> =
                InputStreamReader(call.request.receiveChannel().toInputStream(), UTF8).jsonToMap()
            val query = map["query"] as? String
            val variables = map["variables"] as? Map<String, Any>
            val operationName = map["operationName"] as? String
            val executionInput = ExecutionInput
                .newExecutionInput(query)
                .operationName(operationName)
                .variables(variables)
                .build()
            val executionResult = graphQL.execute(executionInput)
            call.respondOutputStream(contentType = ContentType.Application.Json, status = HttpStatusCode.OK) {
                executionResult.toSpecification().writeAsJsonTo(OutputStreamWriter(this, UTF8))
            }
        }
        get("/schema") {
            call.respondText { schemaPrinter.print(oldSchema) }
        }
    }
}


private val originalSchema = """
                type Query {
                  myresult(simpleArg: String): [result]
                }
        
                type result {
                  b: Byte
                  x: String
                  y: Int
                  z: [foo]
                }
                
                type foo {
                  bar: Int
                  }
                """
private val oldSchema: GraphQLSchema = SchemaGenerator().makeExecutableSchema(
    SchemaParser().parse(originalSchema),
    RuntimeWiring.newRuntimeWiring().codeRegistry(
        GraphQLCodeRegistry.newCodeRegistry().dataFetcher(
            FieldCoordinates.coordinates("Query", "myresult"),
            DataFetcher { _ -> unfilteredResult })
    ).build()
)

private val unfilteredResult = listOf(
    mapOf("x" to "wai", "y" to 3, "z" to listOf(mapOf("bar" to 5))),
    mapOf("x" to "iks", "y" to 5, "z" to listOf(mapOf("bar" to 6)))
)

private val schemaPrinter = SchemaPrinter(
    SchemaPrinter.Options
        .defaultOptions()
        .includeDirectives(false)
        .includeIntrospectionTypes(false)
)

private val graphQL: GraphQL = GraphQL
    .newGraphQL(oldSchema)
    .instrumentation(
        FilterInstrumentation(
            ops, "_filter", SchemaPrinter(
                SchemaPrinter.Options.defaultOptions().includeDirectives(false).includeIntrospectionTypes(false)
            )
        )
    ).build()

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
