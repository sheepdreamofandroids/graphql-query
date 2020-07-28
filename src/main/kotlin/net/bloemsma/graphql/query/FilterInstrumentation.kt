package net.bloemsma.graphql.query

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Caffeine.newBuilder
import graphql.ExecutionResult
import graphql.Scalars.GraphQLString
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.language.Argument
import graphql.language.Document
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.PropertyDataFetcherHelper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** Adds filtering capabilities to GraphQL.
 * This happens in steps:
 * 1 extend an existing GraphQL schema with an extra '_filter' parameter for each field of list type. This happens once when the Schema is built.
 * 2 when a query comes in the first time, "compile" it to a ResultModifier which takes a complete Result tree and modifies those fields. This happens once for each unique query.
 * 3 when the query is actually executed, retrieve that ResultModifier from the cache and apply it to the result. This happens for every query.
 * */
class FilterInstrumentation(
    private val filterName: String = "_filter",
    private val ops: OperatorRegistry = defaultOperators,
    private val cacheConfig: Caffeine<Any, Any>.() -> Unit = {}
) : SimpleInstrumentation() {
    // this could be an async loading cache, parsing the query while the data is being retrieved
    private val query2ResultModifier: ConcurrentMap<String, ResultModifier> = ConcurrentHashMap()
    private val schemaCache: Cache<GraphQLSchema, GraphQLSchema> = newBuilder()
        .apply(cacheConfig)
        .weakKeys() // allows to vacate entries when memory is tight and forces identity semantics
        .build()

    /** Parses a query into a Modifier and stores it in query2modifier */
    override fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters
    ): DocumentAndVariables {
        query2ResultModifier.getOrPut(parameters.query) {
            parseDocument(documentAndVariables.document, parameters.schema) ?: noModification
        }
        return documentAndVariables
    }


    private val noModification: ResultModifier = { _: Result?, _: Variables -> }.showingAs { "no modification" }
    private val addQueryToSchema = AddQueryToSchema(ops)

    /** Extends schema with filter parameters on lists. */
    override fun instrumentSchema(
        schema: GraphQLSchema,
        parameters: InstrumentationExecutionParameters
    ): GraphQLSchema = schemaCache.get(schema) { addQueryToSchema.transform(schema) }!!


    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters
    ): CompletableFuture<ExecutionResult> {
        query2ResultModifier[parameters.query]
            ?.invoke(executionResult.getData(), parameters.variables) // destructively modify result
        return super.instrumentExecutionResult(executionResult, parameters)
    }


    private fun parseDocument(document: Document, schema: GraphQLSchema): ResultModifier? =
        document.definitions
            .mapNotNull { it as? OperationDefinition }
            .filter { it.operation == OperationDefinition.Operation.QUERY }
            .flatMap { it.selectionSet.getSelectionsOfType(Field::class.java) }
            .firstOrNull { !it.name.startsWith("__") }
            ?.let { field: Field ->
                schema.queryType.getFieldDefinition(field.name)
                    ?.type
                    ?.let { type ->
                        modifierFor(field, type)?.let { mod ->
                            { r: Result?, v: Variables ->
                                r?.getField(field.name)?.let { mod(it, v) }
                            } as ResultModifier
                        }
                    }
            }

    private fun modifierFor(field: Field, type: GraphQLOutputType): ResultModifier? {
        return when (type) {
            is GraphQLList -> {
                val innerModifier = modifierFor(field, type.wrappedType as GraphQLOutputType)
                field.arguments.firstOrNull { it.name == filterName }?.let { argument: Argument ->
                    val contextType = type.wrappedType as GraphQLOutputType
                    addQueryToSchema
                        .functionFor(contextType, Boolean::class)
                        .compile(null, argument.value, contextType)
                        .logln { "Filtering ${field.name} on $it" }
                }?.let { pred: QueryPredicate ->
                    { context: Result?, variables: Variables ->
                        (context as? MutableIterable<Result>)
                            ?.let {
                                val iterator = it.iterator()
                                while (iterator.hasNext()) {
                                    val next = iterator.next()
                                    if (!pred(next, variables))
                                        iterator.remove()
                                    else
                                        innerModifier?.invoke(next, variables)
                                }
                            }
                        Unit
                    }.showingAs { "Modifier on field ${field.name} with predicate $pred." }
                } ?: innerModifier?.let {
                    { context: Result?, variables: Variables ->
                        (context as? MutableIterable<Result>)
                            ?.let {
                                val iterator = it.iterator()
                                while (iterator.hasNext()) {
                                    val next = iterator.next()
                                    innerModifier.invoke(next, variables)
                                }
                            }
                        Unit
                    }.showingAs { "Modifier on field ${field.name} passing on to $innerModifier." }
                }
            }

            is GraphQLObjectType ->
                field.selectionSet.getSelectionsOfType(Field::class.java)
                    .mapNotNull { innerField: Field ->
                        modifierFor(
                            innerField,
                            type.getFieldDefinition(innerField.name).type
                        )?.let { innerField.name to it }
                    }
                    .let { fieldModifiers: List<Pair<String, ResultModifier>> ->
                        when {
                            fieldModifiers.isEmpty() -> null
                            else -> {
                                val modifier: ResultModifier = { context: Result?, variables: Variables ->
                                    for ((name, modifier) in fieldModifiers) {
                                        modifier(context?.getField(name), variables)
                                    }
                                }.showingAs { "Modifying fields: " + fieldModifiers.joinToString { "\n${it.first}: ${it.second}" } }
                                modifier
                            }
                        }
                    }
            is GraphQLNonNull -> modifierFor(field, type.wrappedType as GraphQLOutputType)
            else -> null
        }
    }
}

fun Result.getField(name: String): Any? =
    PropertyDataFetcherHelper.getPropertyValue(name, this, GraphQLString)


inline fun <I, O> ((I) -> O).showingAs(crossinline body: ((I) -> O).() -> String): (I) -> O =
    if (debug == false) this else let {
        object : ((I) -> O) {
            override fun invoke(i: I): O = it(i)
            override fun toString(): String = it.body()
        }
    }

inline fun <I1, I2, O> ((I1, I2) -> O).showingAs(crossinline body: ((I1, I2) -> O).() -> String): (I1, I2) -> O =
    if (debug == false) this else let {
        object : ((I1, I2) -> O) {
            override fun invoke(i1: I1, i2: I2): O {
                logln { "executing $this($i1, $i2)" }
                val r = it(i1, i2)
                logln { "executing $this -> $r" }
                return r
            }

            override fun toString(): String = it.body()
        }
    }