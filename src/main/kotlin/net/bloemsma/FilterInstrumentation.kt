package net.bloemsma

import graphql.ExecutionResult
import graphql.analysis.*
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.SchemaTransformer
import graphql.schema.idl.SchemaPrinter
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

// modifying the query result is destructive
typealias  ResultModifier = (Any) -> Unit
typealias  Predicate = (Any) -> Boolean
typealias QueryTimeFunction = (Any, Variables) -> Any
typealias QueryTimePredicate = (Any, Variables) -> Boolean
// calculates a modifier from a query
typealias FilterParser = (Value<*>) -> ResultModifier

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

    class QueryToModifier(val ops: OperatorRegistry) : QueryReducer<QueryToModifier.Q2MState> {

        data class Q2MState(val x: Int, val types: Map<Field, GraphQLType>)

        override fun reduceField(env: QueryVisitorFieldEnvironment, acc: Q2MState): Q2MState {
            val q2MState = Q2MState(2, acc.types + (env.field to env.parentType))
//            println(q2MState)
            println(env.field)
            return q2MState
        }
    }

    class TypeRegister(val types: MutableMap<String, GraphQLType>?) {
        val toModify: MutableMap<String, ResultModifier> = mutableMapOf()
        val subTypes: MutableMap<String, TypeRegister> = mutableMapOf()
    }

    /** Parses a query into a Modifier and stores it in query2modifier */
    override fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters
    ): DocumentAndVariables {
        val queryTraverser = QueryTraverser.newQueryTraverser()
            .document(documentAndVariables.document) // document is the parsed query
            .schema(parameters.schema)
            .variables(documentAndVariables.variables)
            .build()
//        queryTraverser.reducePostOrder(QueryToModifier(ops), QueryToModifier.Q2MState(1, emptyMap()))
        val toModify: MutableMap<String, ResultModifier> = mutableMapOf()
        if (true) queryTraverser.visitDepthFirst(object : QueryVisitorStub() {
            override fun visitField(env: QueryVisitorFieldEnvironment) {
                val context = env.traverserContext
                val fieldName = env.field.name
                val fieldType = env.fieldDefinition.type
                println("${context.phase}: $fieldName alias ${env.field.alias} of type $fieldType")
                val parentsTypeRegister: TypeRegister? = context.getVarFromParents(TypeRegister::class.java)
                parentsTypeRegister?.types?.put(fieldName, fieldType)
                val filterArgument = env.field.arguments.firstOrNull { arg: Argument -> arg.name == filterName }
                val hasFilter = filterArgument != null
                // collecting types only makes sense for filters
                when (context.phase) {
                    TraverserContext.Phase.ENTER -> {
                        context.setVar(
                            TypeRegister::class.java,
                            TypeRegister(types = if (hasFilter) mutableMapOf() else null)
                        )
                    }
                    TraverserContext.Phase.LEAVE -> {
                        val typeRegister = context.getVar(TypeRegister::class.java)
                        val types = typeRegister.types
                        parentsTypeRegister?.subTypes?.put(fieldName, typeRegister)
                        if (filterArgument != null) {
                            val filterFunction = analysis.functionFor(
                                fieldType.filterableType()?.wrappedType?.testableType() ?: throw Exception("Can't filter $fieldType"),
                                Boolean::class
                            )
                            val test: Predicate = filterFunction.compile(filterArgument) as Predicate
                            val modifier: ResultModifier = { data: Any ->
                                val iterator = (data as? MutableIterable<Any>)?.iterator()
                                iterator?.forEach { if (!test(it)) iterator.remove() }
                            }
                            val showingAs = modifier.showingAs { "filter on: \n$test" }
                            println(showingAs)
//                            return showingAs
//                            val modifier = filterArgument.toModifier(types!!)
                            println("Field $fieldName has filter, types are: $types, modifier is: $modifier")
                            (parentsTypeRegister?.toModify ?: toModify).put(fieldName, modifier)
                        }
                    }
                }
//                println(x)
//                if(env.traverserContext.phase==TraverserContext.Phase.ENTER)
//                    env.traverserContext.getSharedContextData<>()
            }

            override fun visitArgument(environment: QueryVisitorFieldArgumentEnvironment): TraversalControl {
                println("${environment.argument} => ${environment.argumentValue}")
                return super.visitArgument(environment)
            }
        })
        println("To modify: $toModify")
        query2ResultModifier.getOrPut(parameters.query) {
            parseDocument(documentAndVariables.document, parameters.schema)
        }
        return documentAndVariables
    }


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

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters
    ): DataFetcher<*> {
        if (parameters.environment.containsArgument(filterName)) {
            println("Datafetcher: $dataFetcher with parms: ${parameters.environment.arguments} on source: ${parameters.environment.getSource<Any>()}")
            dataFetcher // should wrap in filtering datafetcher
        } else dataFetcher
        return super.instrumentDataFetcher(dataFetcher, parameters)
    }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters
    ): CompletableFuture<ExecutionResult> {
        query2ResultModifier.get(parameters.query)?.invoke(executionResult.getData()) // destructively modify result
        return super.instrumentExecutionResult(executionResult, parameters)
    }


    private fun parseDocument(document: Document, schema: GraphQLSchema): ResultModifier {


        val mapNotNull: List<Pair<String, (Any) -> Unit>> = document.definitions.mapNotNull { definition ->
            if (definition is OperationDefinition && definition.operation == OperationDefinition.Operation.QUERY)
                definition.toModifier()?.let { definition.name to it }
            else null
        }
        println(mapNotNull)
        return { it }
//        return when {
//            mapNotNull.isNullOrEmpty() -> { x -> x }
//            else -> { result ->
//                mapNotNull.forEach {
//                    it.second.invoke(
//                        PropertyDataFetcherHelper.getPropertyValue(
//                            it.first,
//                            result,
//                            GraphQLString
//                        )
//                    )
//                }
//                result
//            }
//        }
    }


    private fun <T : Node<*>> SelectionSetContainer<T>.toModifier(): ResultModifier? =
        getSelectionSet().selections.mapNotNull { selection ->
            (selection as? Field)?.arguments?.find { it.name == filterName }?.toModifier(mapOf())
        }.let { { it } }

    private fun Argument.toModifier(types: Map<String, GraphQLType>): ResultModifier {
        val test: Predicate = this.value.toPredicate(types)
//        return object : Modifier {
//            override fun invoke(data: Any) {
//                val iterator = (data as? MutableIterable<Any>)?.iterator()
//                iterator?.forEach { if (!test(it)) iterator.remove() }
//            }
//
//            override fun toString(): String {
//                return "super.toString()"
//            }
//        }

        val modifier: (Any) -> Unit = { data: Any ->
            val iterator = (data as? MutableIterable<Any>)?.iterator()
            iterator?.forEach { if (!test(it)) iterator.remove() }
        }
        val showingAs = modifier.showingAs { "filter on: \n$test" }
        println(showingAs)
        return showingAs
    }

    private fun Value<*>.toPredicate(types: Map<String, GraphQLType>): Predicate {
        val nothing: Predicate = when (this) {
            is ObjectValue -> objectFields.mapNotNull { objectField -> objectField.toPredicate(types) }
                .let { predicates ->
                    { any: Any -> predicates.all { it.invoke(this) } }
                        .showingAs { "    ${predicates.joinToString(separator = "\nAND ")}\n" }
                }
            else -> throw Exception("Not a predate at $sourceLocation")
        }
        return nothing
    }

    private fun ObjectField.toPredicate(types: Map<String, GraphQLType>): Predicate {
//        ops.operators.find { it.canProduce(Boolean::class, this.) }
        return { data -> true }
    }

//    fun test(it: Any): Any {
//
//    }

}

fun <I, O> ((I) -> O).showingAs(body: () -> String): (I) -> O = let {
    object : ((I) -> O) {
        override fun invoke(i: I): O = it(i)
        override fun toString(): String = body()
    }
}