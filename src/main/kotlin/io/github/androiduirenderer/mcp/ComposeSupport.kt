package io.github.androiduirenderer.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves a top-level @Composable from project Kotlin source and produces a typed direct call.
 * It intentionally compiles a normal Kotlin call instead of reflectively invoking a transformed
 * composable method. That keeps Compose's compiler-generated parameters entirely invisible to
 * the MCP API.
 */
internal class ComposeCallGenerator(private val projectRoot: Path) {
    fun generate(render: ComposeRender): GeneratedComposeCall {
        val symbol = render.function.split('.').filter { it.isNotBlank() }
        if (symbol.size < 2 || !symbol.last().matches(IDENTIFIER)) {
            throw RendererException("INVALID_REQUEST", "function must be a fully qualified top-level composable name")
        }
        val functionName = symbol.last()
        val packageName = symbol.dropLast(1).joinToString(".")
        val sourceFile = kotlinFiles().firstOrNull { file ->
            packageOf(file) == packageName && composableFunction(file, functionName) != null
        } ?: throw RendererException("COMPOSE_FUNCTION_NOT_FOUND", "Composable function not found: ${render.function}")
        val function = composableFunction(sourceFile, functionName)!!
        val imports = importsOf(sourceFile)
        val compiler = ArgumentCompiler(projectRoot, function.packageName, imports)
        val supplied = render.arguments
        val unknown = supplied.keys - function.parameters.map { it.name }.toSet()
        if (unknown.isNotEmpty()) throw RendererException("INVALID_REQUEST", "Unknown Compose arguments for ${render.function}: ${unknown.sorted().joinToString()}")

        val callArguments = function.parameters.mapNotNull { parameter ->
            when {
                supplied.containsKey(parameter.name) -> "${parameter.name} = ${compiler.value(parameter.type, supplied.getValue(parameter.name), parameter.name)}"
                parameter.type.isCallback() -> "${parameter.name} = ${callback(parameter.type)}"
                parameter.hasDefault -> null
                else -> throw RendererException("INVALID_REQUEST", "Missing required Compose argument: ${parameter.name}")
            }
        }.joinToString(",\n")
        return GeneratedComposeCall(compiler.preamble(), "${render.function}(\n$callArguments\n)", resolveTheme(render.theme))
    }

    private fun kotlinFiles(): Sequence<Path> = Files.walk(projectRoot).use { paths ->
        paths.filter { path -> path.toString().endsWith(".kt") && !path.toString().contains("/build/") }.toList().asSequence()
    }

    private fun packageOf(file: Path): String? = Regex("(?m)^\\s*package\\s+([\\w.]+)").find(Files.readString(file))?.groupValues?.get(1)

    private fun importsOf(file: Path): Map<String, String> = Regex("(?m)^\\s*import\\s+([\\w.]+)")
        .findAll(Files.readString(file)).associate { it.groupValues[1].substringAfterLast('.') to it.groupValues[1] }

    private fun composableFunction(file: Path, name: String): KotlinFunction? {
        val text = Files.readString(file)
        val match = Regex("(?m)^\\s*(?:(?:public|private|internal)\\s+)?fun\\s+$name\\s*\\(").find(text) ?: return null
        val before = text.substring(maxOf(0, match.range.first - 600), match.range.first)
        if (!before.contains("@Composable")) return null
        val open = text.indexOf('(', match.range.first)
        val close = matching(text, open, '(', ')') ?: return null
        val parameters = splitTopLevel(text.substring(open + 1, close)).filter { it.isNotBlank() }.map { declaration ->
            val colon = topLevelIndex(declaration, ':')
            if (colon < 1) throw RendererException("COMPOSE_SIGNATURE_INVALID", "Cannot parse parameter in $name: $declaration")
            val rawName = declaration.substring(0, colon).trim().removePrefix("vararg ")
            val rest = declaration.substring(colon + 1).trim()
            val equals = topLevelIndex(rest, '=')
            KotlinParameter(rawName, (if (equals >= 0) rest.substring(0, equals) else rest).trim(), equals >= 0)
        }
        return KotlinFunction(packageOf(file) ?: "", parameters)
    }

    private fun callback(type: String): String {
        val left = type.substringBefore("->").trim().removeSurrounding("(", ")").trim()
        if (left.isEmpty()) return "{}"
        return "{ ${splitTopLevel(left).indices.joinToString(", ") { "_" }} -> }"
    }

    private fun resolveTheme(explicit: String?): String? {
        explicit?.let { name ->
            if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) {
                throw RendererException("INVALID_REQUEST", "theme must be a fully qualified top-level composable name")
            }
            return name
        }
        return kotlinFiles().firstNotNullOfOrNull { file ->
            val packageName = packageOf(file) ?: return@firstNotNullOfOrNull null
            composableFunction(file, "AppTheme")?.let { "$packageName.AppTheme" }
        }
    }

    private class ArgumentCompiler(private val root: Path, private val packageName: String, private val imports: Map<String, String>) {
        private val statements = mutableListOf<String>()
        private var sequence = 0

        fun preamble(): String = statements.joinToString("\n")

        fun value(declaredType: String, json: JsonElement, hint: String): String {
            val nullable = declaredType.trim().endsWith('?')
            val type = declaredType.trim().removeSuffix("?")
            if (json is JsonNull) {
                if (!nullable) throw RendererException("INVALID_REQUEST", "$hint is not nullable")
                return "null"
            }
            if (type.isCallback()) return callbackValue(type)
            return when (type) {
                "String", "kotlin.String" -> quote(json.string(hint))
                "Int", "kotlin.Int" -> json.number(hint).toInt().toString()
                "Long", "kotlin.Long" -> "${json.number(hint).toLong()}L"
                "Float", "kotlin.Float" -> "${json.number(hint).toFloat()}f"
                "Double", "kotlin.Double" -> json.number(hint).toString()
                "Boolean", "kotlin.Boolean" -> json.boolean(hint).toString()
                else -> collection(type, json, hint) ?: objectValue(resolve(type), json, hint)
            }
        }

        private fun collection(type: String, json: JsonElement, hint: String): String? {
            val match = Regex("(?:kotlin\\.collections\\.)?(List|Set)<(.+)>").matchEntire(type) ?: return null
            val elements = json.asArray(hint).mapIndexed { index, element -> value(match.groupValues[2], element, "$hint[$index]") }
            return if (match.groupValues[1] == "Set") "setOf(${elements.joinToString()})" else "listOf(${elements.joinToString()})"
        }

        private fun objectValue(type: String, element: JsonElement, hint: String): String {
            val declaration = findType(type) ?: throw RendererException("COMPOSE_TYPE_NOT_FOUND", "Cannot find Kotlin type $type for $hint")
            if (declaration.kind == KotlinTypeKind.ENUM) {
                val enumName = element.string(hint)
                if (enumName !in declaration.enumValues) throw RendererException("INVALID_REQUEST", "$hint is not a $type enum value")
                return "$type.$enumName"
            }
            val json = element.asObject(hint)
            val unknown = json.keys - declaration.constructor.map { it.name }.toSet() - declaration.mutableProperties
            if (unknown.isNotEmpty()) throw RendererException("INVALID_REQUEST", "Unknown fields for $type: ${unknown.sorted().joinToString()}")
            val args = declaration.constructor.mapNotNull { parameter ->
                when {
                    json.containsKey(parameter.name) -> "${parameter.name} = ${value(parameter.type, json.getValue(parameter.name), "$hint.${parameter.name}")}"
                    parameter.hasDefault -> null
                    else -> throw RendererException("INVALID_REQUEST", "Missing required field $hint.${parameter.name}")
                }
            }
            val mutable = json.keys.intersect(declaration.mutableProperties)
            if (mutable.isEmpty()) return "$type(${args.joinToString()})"
            val variable = "aur_${hint.replace(Regex("[^A-Za-z0-9_]"), "_")}_${sequence++}"
            statements += "val $variable = $type(${args.joinToString()})"
            mutable.forEach { property -> statements += "$variable.$property = ${value(declaration.propertyTypes.getValue(property), json.getValue(property), "$hint.$property")}" }
            return variable
        }

        private fun resolve(type: String): String = when {
            '.' in type -> type
            type in imports -> imports.getValue(type)
            else -> "$packageName.$type"
        }

        private fun findType(fqName: String): KotlinType? {
            val wantedPackage = fqName.substringBeforeLast('.', "")
            val wantedName = fqName.substringAfterLast('.')
            return Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") && !it.toString().contains("/build/") }.toList() }
                .firstNotNullOfOrNull { file -> if (packageOfText(Files.readString(file)) == wantedPackage) typeIn(Files.readString(file), wantedName) else null }
        }

        private fun typeIn(text: String, name: String): KotlinType? {
            Regex("(?m)^\\s*enum\\s+class\\s+$name\\s*\\{").find(text)?.let { match ->
                val open = text.indexOf('{', match.range.first); val close = matching(text, open, '{', '}') ?: return null
                return KotlinType(KotlinTypeKind.ENUM, emptyList(), emptySet(), emptyMap(), splitTopLevel(text.substring(open + 1, close)).map { it.trim().substringBefore(' ') }.filter { it.matches(IDENTIFIER) })
            }
            val match = Regex("(?m)^\\s*(?:data\\s+)?class\\s+$name\\s*\\(").find(text) ?: return null
            val open = text.indexOf('(', match.range.first); val close = matching(text, open, '(', ')') ?: return null
            val constructor = splitTopLevel(text.substring(open + 1, close)).filter { it.isNotBlank() }.map { member ->
                val colon = topLevelIndex(member, ':'); if (colon < 0) throw RendererException("COMPOSE_SIGNATURE_INVALID", "Cannot parse constructor of $name")
                val raw = member.substring(0, colon).trim().removePrefix("private ").removePrefix("public ").removePrefix("internal ").removePrefix("val ").removePrefix("var ")
                val rest = member.substring(colon + 1).trim(); val equals = topLevelIndex(rest, '=')
                KotlinParameter(raw, (if (equals >= 0) rest.substring(0, equals) else rest).trim(), equals >= 0)
            }
            val bodyStart = text.indexOf('{', close).takeIf { it >= 0 && it < text.indexOf("\nclass ", close).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
            val body = bodyStart?.let { start -> matching(text, start, '{', '}')?.let { text.substring(start + 1, it) } }.orEmpty()
            val properties = Regex("(?m)^\\s*var\\s+(\\w+)\\s*(?::\\s*([^=\\n]+))?").findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }.toMutableMap()
            val constructorTypes = constructor.associate { it.name to it.type }
            Regex("(?m)^\\s*var\\s+(\\w+)\\s+by\\s+mutableStateOf\\((\\w+)\\)").findAll(body).forEach { delegated ->
                val property = delegated.groupValues[1]
                val source = delegated.groupValues[2]
                properties[property] = constructorTypes[source]
                    ?: throw RendererException("COMPOSE_SIGNATURE_INVALID", "Cannot infer type of mutable state $name.$property")
            }
            return KotlinType(KotlinTypeKind.CLASS, constructor, properties.keys, properties, emptyList())
        }

        private fun callbackValue(type: String): String {
            val left = type.substringBefore("->").trim().removeSurrounding("(", ")").trim()
            return if (left.isEmpty()) "{}" else "{ ${splitTopLevel(left).indices.joinToString(", ") { "_" }} -> }"
        }
    }

    private data class KotlinFunction(val packageName: String, val parameters: List<KotlinParameter>)
    private data class KotlinParameter(val name: String, val type: String, val hasDefault: Boolean)
    private enum class KotlinTypeKind { CLASS, ENUM }
    private data class KotlinType(val kind: KotlinTypeKind, val constructor: List<KotlinParameter>, val mutableProperties: Set<String>, val propertyTypes: Map<String, String>, val enumValues: List<String>)

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        fun String.isCallback() = "->" in this
        fun JsonElement.asObject(hint: String): JsonObject = this as? JsonObject ?: throw RendererException("INVALID_REQUEST", "$hint must be an object")
        fun JsonElement.asArray(hint: String): JsonArray = this as? JsonArray ?: throw RendererException("INVALID_REQUEST", "$hint must be an array")
        fun JsonElement.string(hint: String): String = (this as? JsonPrimitive)?.content ?: throw RendererException("INVALID_REQUEST", "$hint must be a string")
        fun JsonElement.number(hint: String): Number = (this as? JsonPrimitive)?.content?.toDoubleOrNull() ?: throw RendererException("INVALID_REQUEST", "$hint must be a number")
        fun JsonElement.boolean(hint: String): Boolean = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: throw RendererException("INVALID_REQUEST", "$hint must be a boolean")
        fun packageOfText(text: String): String? = Regex("(?m)^\\s*package\\s+([\\w.]+)").find(text)?.groupValues?.get(1)
        fun quote(value: String): String = buildString { append('"'); value.forEach { char -> append(when (char) { '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> char }) }; append('"') }
        fun splitTopLevel(value: String): List<String> { val result = mutableListOf<String>(); var start = 0; var depth = 0; value.forEachIndexed { index, char -> when (char) { '(', '<', '[', '{' -> depth++; ')', ']', '}' -> depth--; '>' -> if (index == 0 || value[index - 1] != '-') depth--; ',' -> if (depth == 0) { result += value.substring(start, index).trim(); start = index + 1 } } }; result += value.substring(start).trim(); return result }
        fun topLevelIndex(value: String, target: Char): Int { var depth = 0; value.forEachIndexed { index, char -> when (char) { '(', '<', '[', '{' -> depth++; ')', ']', '}' -> depth--; '>' -> if (index == 0 || value[index - 1] != '-') depth--; else -> if (char == target && depth == 0) return index } }; return -1 }
        fun matching(value: String, open: Int, start: Char, end: Char): Int? { var depth = 0; for (index in open until value.length) when (value[index]) { start -> depth++; end -> if (--depth == 0) return index }; return null }
    }
}

internal data class GeneratedComposeCall(val preamble: String, val invocation: String, val theme: String?)
