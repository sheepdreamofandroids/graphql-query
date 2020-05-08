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
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

// modifying the query result is destructive
typealias  Modifier = (Any) -> Unit
typealias  Predicate = (Any) -> Boolean

/** Adds filtering capabilities to GraphQL */
class FilterInstrumentation(val ops: OperatorRegistry, val filterName: String) : SimpleInstrumentation() {
    private val modifiers: ConcurrentMap<String, Modifier> = ConcurrentHashMap()

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
        val toModify: MutableMap<String, Modifier> = mutableMapOf()
        val subTypes: MutableMap<String, TypeRegister> = mutableMapOf()
    }

    override fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters
    ): DocumentAndVariables {
        val queryTraverser = QueryTraverser.newQueryTraverser()
            .document(documentAndVariables.document)
            .schema(parameters.schema)
            .variables(documentAndVariables.variables)
            .build()
//        queryTraverser.reducePostOrder(QueryToModifier(ops), QueryToModifier.Q2MState(1, emptyMap()))
        val toModify: MutableMap<String, Modifier> = mutableMapOf()
        if (true) queryTraverser.visitDepthFirst(object : QueryVisitorStub() {
            override fun visitField(env: QueryVisitorFieldEnvironment) {
                val context = env.traverserContext
                val fieldName = env.field.name
                println("${context.phase}: $fieldName alias ${env.field.alias} of type ${env.fieldDefinition.type}")
                val parentsTypeRegister: TypeRegister? = context.getVarFromParents(TypeRegister::class.java)
                parentsTypeRegister?.types?.put(
                    fieldName,
                    env.fieldDefinition.type
                )
                val filterArgument = env.field.arguments.firstOrNull { arg: Argument -> arg.name == filterName }
                val hasFilter = filterArgument != null
                // collecting types only makes sense for filters
                when (context.phase) {
                    TraverserContext.Phase.ENTER -> {
                        context.setVar(TypeRegister::class.java, TypeRegister(types = if (hasFilter) mutableMapOf() else null))
                    }
                    TraverserContext.Phase.LEAVE -> {
                        val typeRegister = context.getVar(TypeRegister::class.java)
                        val types = typeRegister.types
                        parentsTypeRegister?.subTypes?.put(fieldName, typeRegister)
                        if (filterArgument != null) {
                            val modifier = filterArgument.toModifier(types)
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
        modifiers.getOrPut(parameters.query) { parseDocument(documentAndVariables.document, parameters.schema) }
        return documentAndVariables
    }


    override fun instrumentSchema(
        schema: GraphQLSchema?,
        parameters: InstrumentationExecutionParameters?
    ): GraphQLSchema {
        return SchemaTransformer.transformSchema(
            schema,
            AddQueryToSchema(ops)
        )
    }

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters
    ): DataFetcher<*> {
        if (parameters.environment.containsArgument(filterName)) {
            println("Datafetcher: $dataFetcher with parms: ${parameters.environment.arguments} on source: ${parameters.environment.getSource<Any>()}")
            dataFetcher
        } else dataFetcher
        return super.instrumentDataFetcher(dataFetcher, parameters)
    }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters
    ): CompletableFuture<ExecutionResult> {
        modifiers.get(parameters.query)?.invoke(executionResult.getData())
        return super.instrumentExecutionResult(executionResult, parameters)
    }


    private fun parseDocument(document: Document, schema: GraphQLSchema): Modifier {


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


    private fun <T : Node<*>> SelectionSetContainer<T>.toModifier(): Modifier? =
        getSelectionSet().selections.mapNotNull { selection ->
            (selection as? Field)?.arguments?.find { it.name == filterName }?.toModifier(mapOf())
        }.let { { it } }

    private fun Argument.toModifier(types: Map<String, GraphQLType>?): Modifier {
        val test: Predicate = this.value.toPredicate()
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

        return { data: Any ->
            val iterator = (data as? MutableIterable<Any>)?.iterator()
            iterator?.forEach { if (!test(it)) iterator.remove() }
        }.showingAs<Any, Unit> { """filter on: $test""" }
    }

    private fun Value<*>.toPredicate(): Predicate {
        val nothing: Predicate = when (this) {
            is ObjectValue -> objectFields.mapNotNull { objectField -> objectField.toPredicate() }
                .let { predicates ->
                    val function: Predicate = { any: Any ->
                        predicates.all { it.invoke(this) }
                    }
                    function
                }
            else -> throw Exception("Not a predate at $sourceLocation")
        }
        return nothing
    }

    private fun ObjectField.toPredicate(): Predicate {
        ops.operators.find { it.canProduce(Boolean::class, this.) }
        return { data -> true }
    }

//    fun test(it: Any): Any {
//
//    }

}

fun <I, O> ((I) -> O).showingAs(body: () -> String): (I) -> O = object : ((I) -> O) {
    override fun invoke(i: I): O = this(i)
    override fun toString(): String = body()
}