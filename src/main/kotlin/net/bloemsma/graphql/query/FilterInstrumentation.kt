package net.bloemsma.graphql.query

import graphql.ExecutionResult
import graphql.Scalars.GraphQLString
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.language.Document
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.schema.*
import graphql.schema.idl.SchemaPrinter
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
    val ops: OperatorRegistry,
    val filterName: String,
    val schemaPrinter: SchemaPrinter
) : SimpleInstrumentation() {
    // this could be an async loading cache, parsing the query while the data is being retrieved
    private val query2ResultModifier: ConcurrentMap<String, ResultModifier> = ConcurrentHashMap()

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


    val noModification: ResultModifier = { _: Result, _: Variables -> }.showingAs { "no modification" }
    private val analysis = AddQueryToSchema(ops)

    /** Extends schema with filter parameters on lists. */
    override fun instrumentSchema(
        schema: GraphQLSchema?,
        parameters: InstrumentationExecutionParameters?
    ): GraphQLSchema = SchemaTransformer.transformSchema(schema, analysis)
        .also {
            println("Found these functions: ${analysis.functions}")
            println(schemaPrinter.print(it))
        }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters
    ): CompletableFuture<ExecutionResult> {
        query2ResultModifier.get(parameters.query)
            ?.invoke(executionResult.getData(), parameters.variables) // destructively modify result
        return super.instrumentExecutionResult(executionResult, parameters)
    }


    private fun parseDocument(document: Document, schema: GraphQLSchema): ResultModifier? {
        val field = document.definitions
            .mapNotNull { it as? OperationDefinition }
            .filter { it.operation == OperationDefinition.Operation.QUERY }
            .flatMap { it.selectionSet.getSelectionsOfType(Field::class.java) }
            .firstOrNull()

        return field?.let {
            val type = schema.queryType.getFieldDefinition(it.name).type
            modifierFor(it, type)?.let { mod ->
                { r: Result, v: Variables ->
                    mod.invoke(r.getField(it.name), v)
                }
            }

        }

    }

    private fun modifierFor(field: Field, type: GraphQLOutputType): ResultModifier? {
        return when (type) {
            is GraphQLList ->
                field.arguments.firstOrNull { it.name == filterName }?.let {
                    analysis
                        .functionFor(type.wrappedType as GraphQLOutputType, Boolean::class)
                        .compile(null, it.value)
                        .also { println("Filtering ${field.name} on $it") }
                }?.let { pred: QueryPredicate ->
                    val modifier: ResultModifier = { context: Result, variables: Variables ->
                        (context as? MutableIterable<Result>)
                            ?.let {
                                val iterator = it.iterator()
                                while (iterator.hasNext())
                                    if (!pred(iterator.next(), variables))
                                        iterator.remove()
                            }
                    }
                    modifier
                }

            is GraphQLObjectType -> field.selectionSet.getSelectionsOfType(Field::class.java)
                .mapNotNull { sel -> modifierFor(sel, type.getFieldDefinition(sel.name).type)?.let { sel.name to it } }
                .let { fieldModifiers: List<Pair<String, ResultModifier>> ->
                    when {
                        fieldModifiers.isEmpty() -> null
                        else -> {
                            val modifier: ResultModifier = { context: Result, variables: Variables ->
                                for ((name, modifier) in fieldModifiers) {
                                    modifier(context.getField(name), variables)
                                }
                            }
                            modifier
                        }
                    }
                }
            else -> null
        }
    }
}

fun Result.getField(name: String): Any =
    PropertyDataFetcherHelper.getPropertyValue(name, this, GraphQLString)


fun <I, O> ((I) -> O).showingAs(body: ((I) -> O).() -> String): (I) -> O = let {
    object : ((I) -> O) {
        override fun invoke(i: I): O = it(i)
        override fun toString(): String = it.body()
    }
}

fun <I1, I2, O> ((I1, I2) -> O).showingAs(body: ((I1, I2) -> O).() -> String): (I1, I2) -> O = let {
    object : ((I1, I2) -> O) {
        override fun invoke(i1: I1, i2: I2): O {
            println("executing $this($i1, $i2)")
            val r = it(i1, i2)
            println("executing $this -> $r")
            return r
        }

        override fun toString(): String = it.body()
    }
}
