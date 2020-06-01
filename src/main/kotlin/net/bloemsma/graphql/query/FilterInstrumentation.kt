package net.bloemsma.graphql.query

import graphql.ExecutionResult
import graphql.Scalars.GraphQLString
import graphql.analysis.*
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.language.Argument
import graphql.language.Document
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.schema.*
import graphql.schema.idl.SchemaPrinter
import graphql.util.TraversalControl
import graphql.util.TraverserContext
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
        val toModify: MutableMap<String, ResultModifier> = mutableMapOf()
        if (false) queryTraverser.visitDepthFirst(object : QueryVisitorStub() {
            override fun visitField(env: QueryVisitorFieldEnvironment) {
                val context = env.traverserContext
                val fieldName = env.field.name
                val fieldType = env.fieldDefinition.type
                println("${context.phase}: $fieldName alias ${env.field.alias} of type $fieldType")
                val parentsTypeRegister: TypeRegister? = context.getVarFromParents(
                    TypeRegister::class.java
                )
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
                                fieldType.filterableType()?.wrappedType?.testableType()
                                    ?: throw Exception("Can't filter $fieldType"),
                                Boolean::class
                            )
                            val test: QueryFunction<Boolean> = filterFunction.compile(null, filterArgument.value)
                            val modifier: ResultModifier = { data: Any, variables: Variables ->
                                val iterator = (data as? MutableIterable<Any>)?.iterator()
                                iterator?.forEach {
                                    if (!(test(it, variables))
                                    ) iterator.remove()
                                }
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

//    override fun instrumentDataFetcher(
//        dataFetcher: DataFetcher<*>,
//        parameters: InstrumentationFieldFetchParameters
//    ): DataFetcher<*> {
//        if (parameters.environment.containsArgument(filterName)) {
//            println("Datafetcher: $dataFetcher with parms: ${parameters.environment.arguments} on source: ${parameters.environment.getSource<Any>()}")
//            dataFetcher // should wrap in filtering datafetcher
//        } else dataFetcher
//        return super.instrumentDataFetcher(dataFetcher, parameters)
//    }

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

//        val mapNotNull: List<Pair<String, ResultModifier>> = document.definitions.mapNotNull { definition ->
//            if (definition is OperationDefinition && definition.operation == OperationDefinition.Operation.QUERY)
//                definition.selectionSet.selections.mapNotNull {
//                    val type = schema.queryType.getFieldDefinition("").type
//                    analysis.functionFor(type) type
//
//                }
//            toModifier()?.let { definition.name to it }
//            else null
//        }
//        println(mapNotNull)
//        return { result: Any, variables: Variables ->
//            mapNotNull.forEach {
//                it.second.invoke(result, variables)
//            }
//        }.showingAs { "not doing anything" }
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


//    private fun <T : Node<*>> SelectionSetContainer<T>.toModifier(): ResultModifier? =
//        getSelectionSet().selections.mapNotNull { selection ->
//            (selection as? Field)?.arguments?.find { it.name == filterName }?.toModifier(mapOf())
//        }.let {
//            { _, _ -> }
//        }
//
//    private fun Argument.toModifier(types: Map<String, GraphQLType>): ResultModifier {
//        val test: XPredicate = this.value.toPredicate(types)
////        return object : Modifier {
////            override fun invoke(data: Any) {
////                val iterator = (data as? MutableIterable<Any>)?.iterator()
////                iterator?.forEach { if (!test(it)) iterator.remove() }
////            }
////
////            override fun toString(): String {
////                return "super.toString()"
////            }
////        }
//
//        val modifier: (Any, Variables) -> Unit = { data: Any, _ ->
//            val iterator = (data as? MutableIterable<Any>)?.iterator()
//            iterator?.forEach { if (!test(it)) iterator.remove() }
//        }
//        val showingAs = modifier.showingAs { "filter on: \n$test" }
//        println(showingAs)
//        return showingAs
//    }
//
//    private fun Value<*>.toPredicate(types: Map<String, GraphQLType>): XPredicate {
//        val nothing: XPredicate = when (this) {
//            is ObjectValue -> objectFields.mapNotNull { objectField -> objectField.toPredicate(types) }
//                .let { predicates ->
//                    { any: Any -> predicates.all { it.invoke(this) } }
//                        .showingAs { "    ${predicates.joinToString(separator = "\nAND ")}\n" }
//                }
//            else -> throw Exception("Not a predate at $sourceLocation")
//        }
//        return nothing
//    }

//    private fun ObjectFieldOp.toPredicate(types: Map<String, GraphQLType>): XPredicate {
////        ops.operators.find { it.canProduce(Boolean::class, this.) }
//        return { data -> true }
//    }

//    fun test(it: Any): Any {
//
//    }

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
        override fun invoke(i1: I1, i2: I2): O  {
            println("executing $this($i1, $i2)")
            val r = it(i1, i2)
            println("executing $this -> $r")
            return r
        }

        override fun toString(): String = it.body()
    }
}
