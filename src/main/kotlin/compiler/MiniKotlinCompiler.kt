package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    private data class Parameter(
        val sourceName: String,
        val argumentJavaName: String,
        val cellJavaName: String,
        val type: String
    )

    private data class FunctionSignature(
        val sourceName: String,
        val javaName: String,
        val returnType: String,
        val parameters: List<Parameter>
    )

    private data class Binding(
        val sourceName: String,
        val javaName: String,
        val type: String
    ) {
        fun accessExpression(): String = "$javaName.value"
        fun helperParameterDeclaration(): String = "Ref<$type> $javaName"
    }

    private data class Environment(
        val bindings: LinkedHashMap<String, Binding> = linkedMapOf()
    ) {
        operator fun get(name: String): Binding =
            bindings[name] ?: error("Unknown variable: $name")

        fun with(binding: Binding): Environment {
            val copy = LinkedHashMap(bindings)
            copy[binding.sourceName] = binding
            return Environment(copy)
        }

        fun helperParameterList(): String = bindings.values.joinToString(", ") { it.helperParameterDeclaration() }
        fun helperArgumentList(): String = bindings.values.joinToString(", ") { it.javaName }
    }

    private data class CompiledValue(
        val expression: String,
        val type: String
    )

    private data class FunctionContext(
        val signature: FunctionSignature,
        val returnContinuationName: String
    )

    private val functionSignatures = linkedMapOf<String, FunctionSignature>()
    private val generatedHelpers = mutableListOf<String>()

    private var helperCounter = 0
    private var tempCounter = 0
    private var localCounter = 0
    private var functionCounter = 0

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        resetState()
        collectFunctionSignatures(program)

        val functions = program.functionDeclaration().map { declaration ->
            val signature = functionSignatures.getValue(declaration.IDENTIFIER().text)
            compileFunction(declaration, signature)
        }

        val miniMain = functionSignatures["main"]
        val javaMain = if (miniMain != null) {
            validateEntryPoint(miniMain)
            """
            public static void main(String[] args) {
                ${miniMain.javaName}((__ignored) -> {
                });
            }
            """.trimIndent()
        } else {
            """
            public static void main(String[] args) {
                return;
            }
            """.trimIndent()
        }

        return buildString {
            appendLine("public class $className {")
            appendLine()
            appendLine(indent("public static final class Ref<T> {"))
            appendLine(indent("    public T value;"))
            appendLine(indent(""))
            appendLine(indent("    public Ref(T value) {"))
            appendLine(indent("        this.value = value;"))
            appendLine(indent("    }"))
            appendLine(indent("}"))
            appendLine()
            appendLine(indent(javaMain))
            if (functions.isNotEmpty()) {
                appendLine()
                appendLine(functions.joinToString("\n\n") { indent(it) })
            }
            if (generatedHelpers.isNotEmpty()) {
                appendLine()
                appendLine(generatedHelpers.joinToString("\n\n") { indent(it) })
            }
            appendLine("}")
        }.trim()
    }

    private fun resetState() {
        functionSignatures.clear()
        generatedHelpers.clear()
        helperCounter = 0
        tempCounter = 0
        localCounter = 0
        functionCounter = 0
    }

    private fun validateEntryPoint(signature: FunctionSignature) {
        require(signature.parameters.isEmpty()) {
            "MiniKotlin entry point must be declared as fun main(): Unit"
        }
        require(signature.returnType == "Void") {
            "MiniKotlin entry point must return Unit"
        }
    }

    private fun collectFunctionSignatures(program: MiniKotlinParser.ProgramContext) {
        for (declaration in program.functionDeclaration()) {
            val sourceName = declaration.IDENTIFIER().text
            require(sourceName !in functionSignatures) {
                "Duplicate function declaration: $sourceName"
            }

            val returnType = mapType(declaration.type())
            val parameters = declaration.parameterList()?.parameter().orEmpty().mapIndexed { index, parameter ->
                val parameterName = parameter.IDENTIFIER().text
                Parameter(
                    sourceName = parameterName,
                    argumentJavaName = "__param_${functionCounter}_$index",
                    cellJavaName = freshLocalName(parameterName),
                    type = mapType(parameter.type())
                )
            }

            val duplicates = parameters.groupBy { it.sourceName }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "Duplicate parameter(s) in function $sourceName: ${duplicates.joinToString(", ")}"
            }

            val javaName = if (sourceName == "main") {
                "main\$mini"
            } else {
                "__fun_${sanitizeIdentifier(sourceName)}_${functionCounter++}"
            }

            functionSignatures[sourceName] = FunctionSignature(
                sourceName = sourceName,
                javaName = javaName,
                returnType = returnType,
                parameters = parameters
            )
        }
    }

    private fun compileFunction(
        declaration: MiniKotlinParser.FunctionDeclarationContext,
        signature: FunctionSignature
    ): String {
        var environment = Environment()
        val parameterCellInitializers = mutableListOf<String>()
        val blockLocalNames = signature.parameters.mapTo(linkedSetOf()) { it.sourceName }

        for (parameter in signature.parameters) {
            val binding = Binding(
                sourceName = parameter.sourceName,
                javaName = parameter.cellJavaName,
                type = parameter.type
            )
            environment = environment.with(binding)
            parameterCellInitializers += "final Ref<${parameter.type}> ${parameter.cellJavaName} = new Ref<>(${parameter.argumentJavaName});"
        }

        val context = FunctionContext(
            signature = signature,
            returnContinuationName = "__returnContinuation"
        )

        val body = compileStatements(
            statements = declaration.block().statement(),
            startIndex = 0,
            environment = environment,
            blockLocalNames = blockLocalNames,
            functionContext = context,
            onEnd = defaultFunctionExit(signature)
        )

        val sourceParameters = signature.parameters.joinToString(", ") { "${it.type} ${it.argumentJavaName}" }
        val methodParameters = listOfNotNull(
            sourceParameters.takeIf { it.isNotBlank() },
            "Continuation<${signature.returnType}> ${context.returnContinuationName}"
        ).joinToString(", ")

        val fullBody = buildString {
            if (parameterCellInitializers.isNotEmpty()) {
                appendLine(parameterCellInitializers.joinToString("\n"))
            }
            append(body)
        }.trim()

        return """
            public static void ${signature.javaName}($methodParameters) {
                ${indent(fullBody)}
            }
        """.trimIndent()
    }

    private fun defaultFunctionExit(signature: FunctionSignature): String =
        if (signature.returnType == "Void") {
            "__returnContinuation.accept(null);\nreturn;"
        } else {
            "throw new IllegalStateException(\"Function ${signature.sourceName} completed without returning a value\");"
        }

    private fun compileStatements(
        statements: List<MiniKotlinParser.StatementContext>,
        startIndex: Int,
        environment: Environment,
        blockLocalNames: LinkedHashSet<String>,
        functionContext: FunctionContext,
        onEnd: String
    ): String {
        if (startIndex >= statements.size) {
            return onEnd
        }

        val statement = statements[startIndex]

        statement.variableDeclaration()?.let { declaration ->
            val sourceName = declaration.IDENTIFIER().text
            require(sourceName !in blockLocalNames) {
                "Duplicate variable declaration in the same scope: $sourceName"
            }
            val declaredType = mapType(declaration.type())
            val javaName = freshLocalName(sourceName)
            val binding = Binding(sourceName, javaName, declaredType)
            val nextBlockLocals = LinkedHashSet(blockLocalNames).apply { add(sourceName) }

            return compileExpression(declaration.expression(), environment) { value ->
                requireAssignable(
                    declaredType,
                    value.type,
                    "Cannot initialize variable $sourceName of type $declaredType with value of type ${value.type}"
                )
                val remainder = compileStatements(
                    statements = statements,
                    startIndex = startIndex + 1,
                    environment = environment.with(binding),
                    blockLocalNames = nextBlockLocals,
                    functionContext = functionContext,
                    onEnd = onEnd
                )
                """
                final Ref<$declaredType> $javaName = new Ref<>(${coerceExpression(value, declaredType)});
                $remainder
                """.trimIndent()
            }
        }

        statement.variableAssignment()?.let { assignment ->
            val name = assignment.IDENTIFIER().text
            val binding = environment[name]
            val remainder = compileStatements(
                statements = statements,
                startIndex = startIndex + 1,
                environment = environment,
                blockLocalNames = blockLocalNames,
                functionContext = functionContext,
                onEnd = onEnd
            )
            return compileExpression(assignment.expression(), environment) { value ->
                requireAssignable(
                    binding.type,
                    value.type,
                    "Cannot assign value of type ${value.type} to variable $name of type ${binding.type}"
                )
                """
                ${binding.javaName}.value = ${coerceExpression(value, binding.type)};
                $remainder
                """.trimIndent()
            }
        }

        statement.ifStatement()?.let { ifStatement ->
            val remainder = compileStatements(
                statements = statements,
                startIndex = startIndex + 1,
                environment = environment,
                blockLocalNames = blockLocalNames,
                functionContext = functionContext,
                onEnd = onEnd
            )
            return compileExpression(ifStatement.expression(), environment) { condition ->
                requireType(condition.type, "Boolean", "if condition must have type Boolean, but found ${condition.type}")
                val thenCode = compileStatements(
                    statements = ifStatement.block(0).statement(),
                    startIndex = 0,
                    environment = environment,
                    blockLocalNames = linkedSetOf(),
                    functionContext = functionContext,
                    onEnd = remainder
                )
                val elseCode = if (ifStatement.block().size > 1) {
                    compileStatements(
                        statements = ifStatement.block(1).statement(),
                        startIndex = 0,
                        environment = environment,
                        blockLocalNames = linkedSetOf(),
                        functionContext = functionContext,
                        onEnd = remainder
                    )
                } else {
                    remainder
                }
                """
                if (${condition.expression}) {
                    ${indent(thenCode)}
                } else {
                    ${indent(elseCode)}
                }
                """.trimIndent()
            }
        }

        statement.whileStatement()?.let { whileStatement ->
            val remainder = compileStatements(
                statements = statements,
                startIndex = startIndex + 1,
                environment = environment,
                blockLocalNames = blockLocalNames,
                functionContext = functionContext,
                onEnd = onEnd
            )
            return compileWhileStatement(
                whileStatement = whileStatement,
                environment = environment,
                functionContext = functionContext,
                onLoopFinished = remainder
            )
        }

        statement.returnStatement()?.let { returnStatement ->
            val functionReturnType = functionContext.signature.returnType
            val returnExpression = returnStatement.expression()
            return if (returnExpression != null) {
                compileExpression(returnExpression, environment) { value ->
                    requireAssignable(
                        functionReturnType,
                        value.type,
                        "Cannot return value of type ${value.type} from function ${functionContext.signature.sourceName} declared as $functionReturnType"
                    )
                    val expression = if (functionReturnType == "Void") "null" else coerceExpression(value, functionReturnType)
                    emitReturn(functionContext.returnContinuationName, expression)
                }
            } else {
                require(functionReturnType == "Void") {
                    "Function ${functionContext.signature.sourceName} must return a value of type $functionReturnType"
                }
                emitReturn(functionContext.returnContinuationName, "null")
            }
        }

        statement.expression()?.let { expression ->
            val remainder = compileStatements(
                statements = statements,
                startIndex = startIndex + 1,
                environment = environment,
                blockLocalNames = blockLocalNames,
                functionContext = functionContext,
                onEnd = onEnd
            )
            return compileExpression(expression, environment) {
                remainder
            }
        }

        error("Unsupported statement: ${statement.text}")
    }

    private fun compileWhileStatement(
        whileStatement: MiniKotlinParser.WhileStatementContext,
        environment: Environment,
        functionContext: FunctionContext,
        onLoopFinished: String
    ): String {
        val helperName = "__while_${helperCounter++}"
        val loopContinuationName = "__loopContinuation"

        val helperParameters = buildList {
            val envParams = environment.helperParameterList()
            if (envParams.isNotBlank()) add(envParams)
            add("Continuation<${functionContext.signature.returnType}> ${functionContext.returnContinuationName}")
            add("Continuation<Void> $loopContinuationName")
        }.joinToString(", ")

        val recursiveCallArguments = buildList {
            val envArgs = environment.helperArgumentList()
            if (envArgs.isNotBlank()) add(envArgs)
            add(functionContext.returnContinuationName)
            add(loopContinuationName)
        }.joinToString(", ")

        val helperBody = compileExpression(whileStatement.expression(), environment) { condition ->
            requireType(condition.type, "Boolean", "while condition must have type Boolean, but found ${condition.type}")
            val bodyCode = compileStatements(
                statements = whileStatement.block().statement(),
                startIndex = 0,
                environment = environment,
                blockLocalNames = linkedSetOf(),
                functionContext = functionContext,
                onEnd = "$helperName($recursiveCallArguments);"
            )
            """
            if (${condition.expression}) {
                ${indent(bodyCode)}
            } else {
                $loopContinuationName.accept(null);
                return;
            }
            """.trimIndent()
        }

        generatedHelpers += """
            private static void $helperName($helperParameters) {
                ${indent(helperBody)}
            }
        """.trimIndent()

        val initialCallArguments = buildList {
            val envArgs = environment.helperArgumentList()
            if (envArgs.isNotBlank()) add(envArgs)
            add(functionContext.returnContinuationName)
            add("(__loopResult) -> {\n${indent(onLoopFinished)}\n}")
        }.joinToString(", ")

        return "$helperName($initialCallArguments);"
    }

    private fun compileExpression(
        expression: MiniKotlinParser.ExpressionContext,
        environment: Environment,
        onValue: (CompiledValue) -> String
    ): String = when (expression) {
        is MiniKotlinParser.FunctionCallExprContext -> compileFunctionCall(expression, environment, onValue)
        is MiniKotlinParser.PrimaryExprContext -> compilePrimary(expression.primary(), environment, onValue)
        is MiniKotlinParser.NotExprContext -> compileExpression(expression.expression(), environment) { value ->
            requireType(value.type, "Boolean", "Operator ! requires Boolean operand, but found ${value.type}")
            onValue(CompiledValue("(!${value.expression})", "Boolean"))
        }
        is MiniKotlinParser.MulDivExprContext -> compileExpression(expression.expression(0), environment) { left ->
            compileExpression(expression.expression(1), environment) { right ->
                requireType(left.type, "Integer", "Operator ${expression.getChild(1).text} requires Int operands, but left operand has type ${left.type}")
                requireType(right.type, "Integer", "Operator ${expression.getChild(1).text} requires Int operands, but right operand has type ${right.type}")
                onValue(CompiledValue("(${left.expression} ${expression.getChild(1).text} ${right.expression})", "Integer"))
            }
        }
        is MiniKotlinParser.AddSubExprContext -> compileExpression(expression.expression(0), environment) { left ->
            compileExpression(expression.expression(1), environment) { right ->
                val operator = expression.getChild(1).text
                when (operator) {
                    "+" -> {
                        if (left.type == "String" || right.type == "String") {
                            onValue(
                                CompiledValue(
                                    "(String.valueOf(${left.expression}) + String.valueOf(${right.expression}))",
                                    "String"
                                )
                            )
                        } else {
                            requireType(left.type, "Integer", "Operator + requires Int operands unless one operand is String, but left operand has type ${left.type}")
                            requireType(right.type, "Integer", "Operator + requires Int operands unless one operand is String, but right operand has type ${right.type}")
                            onValue(CompiledValue("(${left.expression} + ${right.expression})", "Integer"))
                        }
                    }
                    "-" -> {
                        requireType(left.type, "Integer", "Operator - requires Int operands, but left operand has type ${left.type}")
                        requireType(right.type, "Integer", "Operator - requires Int operands, but right operand has type ${right.type}")
                        onValue(CompiledValue("(${left.expression} - ${right.expression})", "Integer"))
                    }
                    else -> error("Unsupported additive operator: $operator")
                }
            }
        }
        is MiniKotlinParser.ComparisonExprContext -> compileExpression(expression.expression(0), environment) { left ->
            compileExpression(expression.expression(1), environment) { right ->
                val operator = expression.getChild(1).text
                when {
                    left.type == "Integer" && right.type == "Integer" -> {
                        onValue(CompiledValue("(${left.expression} $operator ${right.expression})", "Boolean"))
                    }
                    left.type == "String" && right.type == "String" -> {
                        val cmp = "(${left.expression}.compareTo(${right.expression}))"
                        val javaOp = when (operator) {
                            "<" -> "< 0"
                            "<=" -> "<= 0"
                            ">" -> "> 0"
                            ">=" -> ">= 0"
                            else -> error("Unsupported comparison operator: $operator")
                        }
                        onValue(CompiledValue("($cmp $javaOp)", "Boolean"))
                    }
                    else -> error("Operator $operator requires operands of type Int/Int or String/String, but found ${left.type}/${right.type}")
                }
            }
        }
        is MiniKotlinParser.EqualityExprContext -> compileExpression(expression.expression(0), environment) { left ->
            compileExpression(expression.expression(1), environment) { right ->
                val operator = expression.getChild(1).text
                val eq = "java.util.Objects.equals(${left.expression}, ${right.expression})"
                if (operator == "==") {
                    onValue(CompiledValue(eq, "Boolean"))
                } else {
                    onValue(CompiledValue("(!$eq)", "Boolean"))
                }
            }
        }
        is MiniKotlinParser.AndExprContext -> compileExpression(expression.expression(0), environment) { left ->
            requireType(left.type, "Boolean", "Operator && requires Boolean operands, but left operand has type ${left.type}")
            """
            if (${left.expression}) {
                ${indent(compileExpression(expression.expression(1), environment) { right ->
                    requireType(right.type, "Boolean", "Operator && requires Boolean operands, but right operand has type ${right.type}")
                    onValue(right)
                })}
            } else {
                ${indent(onValue(CompiledValue("false", "Boolean")))}
            }
            """.trimIndent()
        }
        is MiniKotlinParser.OrExprContext -> compileExpression(expression.expression(0), environment) { left ->
            requireType(left.type, "Boolean", "Operator || requires Boolean operands, but left operand has type ${left.type}")
            """
            if (${left.expression}) {
                ${indent(onValue(CompiledValue("true", "Boolean")))}
            } else {
                ${indent(compileExpression(expression.expression(1), environment) { right ->
                    requireType(right.type, "Boolean", "Operator || requires Boolean operands, but right operand has type ${right.type}")
                    onValue(right)
                })}
            }
            """.trimIndent()
        }
        else -> error("Unsupported expression node: ${expression::class.java.simpleName}")
    }

    private fun compilePrimary(
        primary: MiniKotlinParser.PrimaryContext,
        environment: Environment,
        onValue: (CompiledValue) -> String
    ): String = when (primary) {
        is MiniKotlinParser.ParenExprContext -> compileExpression(primary.expression(), environment) { value ->
            onValue(CompiledValue("(${value.expression})", value.type))
        }
        is MiniKotlinParser.IntLiteralContext -> onValue(CompiledValue(primary.INTEGER_LITERAL().text, "Integer"))
        is MiniKotlinParser.StringLiteralContext -> onValue(CompiledValue(primary.STRING_LITERAL().text, "String"))
        is MiniKotlinParser.BoolLiteralContext -> onValue(CompiledValue(primary.BOOLEAN_LITERAL().text, "Boolean"))
        is MiniKotlinParser.IdentifierExprContext -> {
            val binding = environment[primary.IDENTIFIER().text]
            onValue(CompiledValue(binding.accessExpression(), binding.type))
        }
        else -> error("Unsupported primary node: ${primary::class.java.simpleName}")
    }

    private fun compileFunctionCall(
        call: MiniKotlinParser.FunctionCallExprContext,
        environment: Environment,
        onValue: (CompiledValue) -> String
    ): String {
        val sourceName = call.IDENTIFIER().text
        val signature = resolveFunctionSignature(sourceName)
        val arguments = call.argumentList()?.expression().orEmpty()

        require(arguments.size == signature.parameters.size) {
            "Function $sourceName expects ${signature.parameters.size} argument(s), but got ${arguments.size}"
        }

        return compileArguments(arguments, environment, emptyList()) { compiledArguments ->
            compiledArguments.zip(signature.parameters).forEachIndexed { index, (value, parameter) ->
                requireAssignable(
                    parameter.type,
                    value.type,
                    "Argument ${index + 1} of function $sourceName has type ${value.type}, but ${parameter.type} was expected"
                )
            }

            val argumentExpressions = compiledArguments.zip(signature.parameters)
                .joinToString(", ") { (value, parameter) -> coerceExpression(value, parameter.type) }

            if (signature.returnType == "Void") {
                val continuationBody = onValue(CompiledValue("null", "Void"))
                val fullArgumentList = listOfNotNull(
                    argumentExpressions.takeIf { it.isNotBlank() },
                    "(__unit) -> {\n${indent(continuationBody)}\n}"
                ).joinToString(", ")
                "${signature.javaName}($fullArgumentList);"
            } else {
                val tempName = freshTempName()
                val continuationBody = onValue(CompiledValue(tempName, signature.returnType))
                val fullArgumentList = listOfNotNull(
                    argumentExpressions.takeIf { it.isNotBlank() },
                    "($tempName) -> {\n${indent(continuationBody)}\n}"
                ).joinToString(", ")
                "${signature.javaName}($fullArgumentList);"
            }
        }
    }

    private fun compileArguments(
        arguments: List<MiniKotlinParser.ExpressionContext>,
        environment: Environment,
        alreadyCompiled: List<CompiledValue>,
        onReady: (List<CompiledValue>) -> String
    ): String {
        if (arguments.isEmpty()) {
            return onReady(alreadyCompiled)
        }

        val head = arguments.first()
        val tail = arguments.drop(1)
        return compileExpression(head, environment) { value ->
            compileArguments(tail, environment, alreadyCompiled + value, onReady)
        }
    }

    private fun resolveFunctionSignature(name: String): FunctionSignature {
        functionSignatures[name]?.let { return it }
        return when (name) {
            "println" -> FunctionSignature(
                sourceName = "println",
                javaName = "Prelude.println",
                returnType = "Void",
                parameters = listOf(
                    Parameter(
                        sourceName = "message",
                        argumentJavaName = "__builtin_message",
                        cellJavaName = "__builtin_message_cell",
                        type = "Object"
                    )
                )
            )
            else -> error("Unknown function: $name")
        }
    }

    private fun requireType(actual: String, expected: String, message: String) {
        require(actual == expected) { message }
    }

    private fun requireAssignable(targetType: String, sourceType: String, message: String) {
        val ok = when (targetType) {
            "Object" -> true
            else -> targetType == sourceType
        }
        require(ok) { message }
    }

    private fun emitReturn(returnContinuationName: String, expression: String): String = """
        $returnContinuationName.accept($expression);
        return;
    """.trimIndent()

    private fun coerceExpression(value: CompiledValue, targetType: String): String = when {
        targetType == "Object" -> value.expression
        value.type == targetType -> value.expression
        targetType == "String" && value.type != "String" -> "String.valueOf(${value.expression})"
        targetType == "Void" && value.type == "Void" -> "null"
        else -> value.expression
    }

    private fun mapType(type: MiniKotlinParser.TypeContext): String = when {
        type.INT_TYPE() != null -> "Integer"
        type.STRING_TYPE() != null -> "String"
        type.BOOLEAN_TYPE() != null -> "Boolean"
        type.UNIT_TYPE() != null -> "Void"
        else -> error("Unsupported type: ${type.text}")
    }

    private fun sanitizeIdentifier(identifier: String): String {
        val javaKeywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "yield",
            "record", "sealed", "permits", "non-sealed"
        )
        return if (identifier in javaKeywords) "_${identifier}" else identifier
    }

    private fun freshTempName(): String = "__tmp${tempCounter++}"
    private fun freshLocalName(base: String): String = "${sanitizeIdentifier(base)}__${localCounter++}"

    private fun indent(text: String, spaces: Int = 4): String {
        val prefix = " ".repeat(spaces)
        return text.lines().joinToString("\n") { line ->
            if (line.isBlank()) line else prefix + line
        }
    }
}
