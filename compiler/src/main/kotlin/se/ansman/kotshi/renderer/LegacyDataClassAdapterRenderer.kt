package se.ansman.kotshi.renderer

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.plusParameter
import se.ansman.kotshi.*
import se.ansman.kotshi.Types.Moshi.jsonDataException
import se.ansman.kotshi.Types.Moshi.jsonReaderToken
import se.ansman.kotshi.model.*

class LegacyDataClassAdapterRenderer(
    private val adapter: DataClassJsonAdapter,
    private val createAnnotationsUsingConstructor: Boolean
) : AdapterRenderer(adapter) {
    private val adapters = adapter.properties.generateAdapterProperties()
    private val propertyNames = adapter.properties.mapTo(mutableSetOf()) { it.jsonName }
    private val parentLabels = adapter.polymorphicLabels.filterKeys { it !in propertyNames }
    private val serializedNames = adapter.serializedProperties.map { it.jsonName }.toSet()
    private val optionsProperty = jsonOptionsProperty(serializedNames + parentLabels.keys)

    override fun TypeSpec.Builder.renderSetup() {
        primaryConstructor(
            FunSpec.constructorBuilder()
                .applyIf(adapters.isNotEmpty()) { addParameter(moshiParameterName, Types.Moshi.moshi) }
                .applyIf(adapters.any { it.key.type.hasTypeVariable }) {
                    addParameter(typesParameterName, Types.Kotshi.typesArray)
                }
                .build()
        )
        addProperties(adapters.values)
        addProperty(optionsProperty)
    }

    private fun Iterable<Property>.generateAdapterProperties(): Map<Property, PropertySpec> =
        asSequence()
            .filter { it.shouldUseAdapter }
            .associateWith { property ->
                PropertySpec
                    .builder(
                        nameAllocator.newName(property.suggestedAdapterName),
                        Types.Moshi.jsonAdapter.plusParameter(property.type),
                        KModifier.PRIVATE
                    )
                    .initializer(
                        CodeBlock.builder()
                            .add("«moshi.adapter(")
                            .add(property.asRuntimeType { typeVariableName ->
                                val genericIndex =
                                    adapter.targetTypeVariables.indexOfFirst { it.name == typeVariableName.name }
                                require(genericIndex >= 0) {
                                    throw IllegalStateException("Element ${adapter.targetType} is generic but is of an unknown type")
                                }
                                CodeBlock.of("types[$genericIndex]")
                            })
                            .add(", %L", property.annotations(createAnnotationsUsingConstructor))
                            .add(", %S", property.name)
                            .add(")»")
                            .build()
                    )
                    .build()
            }

    override fun FunSpec.Builder.renderToJson(
        writerParameter: ParameterSpec,
        valueParameter: ParameterSpec
    ) {
        fun addBody(): FunSpec.Builder =
            addStatement("%N.beginObject()", writerParameter)
                .applyEach(parentLabels.entries) { (key, value) ->
                    addStatement("%N.name(%S).value(%S)", writerParameter, key, value)
                }
                .applyEach(adapter.serializedProperties) { property ->
                    addStatement("%N.name(%S)", writerParameter, property.jsonName)
                    val getter = CodeBlock.of("%N.%L", valueParameter, property.name)

                    if (property.shouldUseAdapter) {
                        addCode(
                            "%N.toJson(%N, ", adapters.getValue(property),
                            writerParameter
                        )
                            .addCode(getter)
                            .addCode(")\n")
                    } else when (property.type.notNull()) {
                        STRING,
                        INT,
                        LONG,
                        FLOAT,
                        DOUBLE,
                        SHORT,
                        BOOLEAN -> addStatement("%N.value(%L)", writerParameter, getter)

                        BYTE -> addStatement("%N.%M(%L)", writerParameter, Functions.Kotshi.byteValue, getter)
                        CHAR -> addStatement("%N.%M(%L)", writerParameter, Functions.Kotshi.value, getter)
                        else -> error("Property ${property.name} is not primitive ${property.type} but requested non adapter use")
                    }
                }
                .addStatement("%N.endObject()", writerParameter)

        addIf("%N == null", valueParameter) {
            addStatement("%N.nullValue()", writerParameter)
            addStatement("return")
        }

        val serializeNullsEnabled = when (adapter.serializeNulls) {
            SerializeNulls.DEFAULT -> null
            SerializeNulls.ENABLED -> true
            SerializeNulls.DISABLED -> false
        }
        if (serializeNullsEnabled != null) {
            this
                .addStatement("val·serializeNulls·= %N.serializeNulls", writerParameter)
                .addStatement("%N.serializeNulls·= %L", writerParameter, serializeNullsEnabled)
                .beginControlFlow("try")
                .apply { addBody() }
                .nextControlFlow("finally")
                .addStatement("%N.serializeNulls·= serializeNulls", writerParameter)
                .endControlFlow()
        } else {
            addBody()
        }
    }

    override fun FunSpec.Builder.renderFromJson(readerParameter: ParameterSpec) {
        val variables = adapter.serializedProperties.associateBy({ it }, { it.createVariables() })
        this
            .addStatement(
                "if (%N.peek() == %T.NULL) return %N.nextNull()",
                readerParameter,
                jsonReaderToken,
                readerParameter
            )
            .addCode("\n")
            .applyEach(variables.values) { variable ->
                addCode("%L", variable.value)
                if (variable.helper != null) {
                    addCode("%L", variable.helper)
                }
            }
            .addCode("\n")
            .addStatement("%N.beginObject()", readerParameter)
            .addWhile("%N.hasNext()", readerParameter) {
                addWhen("%N.selectName(%N)", readerParameter, optionsProperty) {
                    variables.entries.forEachIndexed { index, (property, variable) ->
                        addWhenBranch("%L", index) {
                            if (property.shouldUseAdapter) {
                                addStatement(
                                    "%N·= %N.fromJson(%N)",
                                    variable.value,
                                    adapters.getValue(property),
                                    readerParameter
                                )
                                if (variable.helper != null) {
                                    if (property.type.isNullable) {
                                        addStatement("%N·= true", variable.helper)
                                    } else {
                                        addStatement("?.also { %N = true }", variable.helper)
                                    }
                                }
                            } else {
                                fun FunSpec.Builder.readPrimitive(functionName: String, vararg args: Any) {
                                    addIfElse(
                                        "%N.peek() == %T.NULL",
                                        readerParameter, jsonReaderToken
                                    ) {
                                        addStatement("%N.skipValue()", readerParameter)
                                    }
                                    addElse {
                                        addStatement(
                                            "%N·= %N.$functionName()",
                                            variable.value,
                                            readerParameter,
                                            *args
                                        )
                                        if (variable.helper != null && !property.type.isNullable) {
                                            addStatement("%N·= true", variable.helper)
                                        }
                                    }
                                    if (variable.helper != null && property.type.isNullable) {
                                        addStatement("%N·= true", variable.helper)
                                    }
                                }

                                when (property.type.notNull()) {
                                    STRING -> readPrimitive("nextString")
                                    BOOLEAN -> readPrimitive("nextBoolean")
                                    BYTE -> readPrimitive("%M", Functions.Kotshi.nextByte)
                                    SHORT -> readPrimitive("%M", Functions.Kotshi.nextShort)
                                    INT -> readPrimitive("nextInt")
                                    LONG -> readPrimitive("nextLong")
                                    CHAR -> readPrimitive("%M", Functions.Kotshi.nextChar)
                                    FLOAT -> readPrimitive("%M", Functions.Kotshi.nextFloat)
                                    DOUBLE -> readPrimitive("nextDouble")
                                    else -> error(
                                        "Internal Kotshi error when processing ${adapter.targetType}. " +
                                            "Expected property type to be a primitive but was ${property.type}. " +
                                            "Please open an issue here: https://github.com/ansman/kotshi/issues/new"
                                    )
                                }
                            }
                        }
                    }
                    parentLabels.keys.forEachIndexed { i, name ->
                        addWhenBranch("%L", variables.size + i) {
                            addComment("Consumes the '%L' label key from the parent", name)
                            addStatement("%N.nextString()", readerParameter)
                        }
                    }
                    addWhenBranch("-1") {
                        addStatement("%N.skipName()", readerParameter)
                        addStatement("%N.skipValue()", readerParameter)
                    }
                }
            }
            .addStatement("%N.endObject()", readerParameter)
            .addCode("\n")
            .apply {
                val propertiesToCheck = variables.entries
                    .filter { (property, _) ->
                        !property.type.isNullable && !property.hasDefaultValue
                    }

                if (propertiesToCheck.isNotEmpty()) {
                    val stringBuilder = PropertySpec
                        .builder("errorBuilder", StringBuilder::class.asTypeName().nullable())
                        .mutable()
                        .initializer("null")
                        .build()
                    addCode("%L", stringBuilder)
                    for ((property, variable) in propertiesToCheck) {
                        addIf("%L", variable.isNotSet) {
                            if (property.name == property.jsonName) {
                                addStatement(
                                    "%N = %N.%M(%S)",
                                    stringBuilder,
                                    stringBuilder,
                                    Functions.Kotshi.appendNullableError,
                                    property.name
                                )
                            } else {
                                addStatement(
                                    "%N = %N.%M(%S, %S)",
                                    stringBuilder,
                                    stringBuilder,
                                    Functions.Kotshi.appendNullableError,
                                    property.name,
                                    property.jsonName
                                )
                            }
                        }
                    }

                    addIf("%N != null", stringBuilder) {
                        addStatement(
                            "%N.append(\" (at path \").append(%N.path).append(')')",
                            stringBuilder,
                            readerParameter
                        )
                        addStatement("throw %T(%N.toString())", jsonDataException, stringBuilder)
                    }
                    addCode("\n")
                }
            }
            .apply {
                val constructorProperties = adapter.properties.filter { !it.hasDefaultValue }
                val copyProperties = adapter.serializedProperties.filter { it.hasDefaultValue }

                addCode("return·%T(«", adapter.targetType)
                constructorProperties.forEachIndexed { index, property ->
                    val variable = variables.getValue(property)

                    if (index > 0) {
                        addCode(",")
                    }
                    addCode("\n%N·= %N", property.name, variable.value.name)
                    if (variable.value.type.isNullable && !property.type.isNullable) {
                        addCode("!!")
                    }
                }
                addCode("»")
                if (constructorProperties.isNotEmpty()) addCode("\n")
                addCode(")")
                if (copyProperties.isNotEmpty()) {
                    addControlFlow(".let") {
                        addCode("it.copy(«")
                        copyProperties.forEachIndexed { index, property ->
                            val variable = variables.getValue(property)

                            if (index > 0) {
                                addCode(",")
                            }
                            addCode("\n%N = ", property.name)

                            if (variable.helper == null) {
                                addCode("%N ?: it.%N", variable.value, property.name)
                            } else {
                                addCode("if (%L) %N else it.%N", variable.isSet, variable.value, property.name)
                            }
                        }
                        addCode("»\n)\n")
                    }
                }
            }
    }

    private fun Property.createVariables() =
        LegacyPropertyVariables(
            value = PropertySpec
                .builder(
                    name = nameAllocator.newName(name),
                    type = if (type.isPrimitive && !shouldUseAdapter) type else type.nullable()
                )
                .initializer(
                    when {
                        type.isPrimitive && !shouldUseAdapter ->
                            when (type) {
                                BOOLEAN -> CodeBlock.of("false")
                                BYTE -> CodeBlock.of("0")
                                SHORT -> CodeBlock.of("0")
                                INT -> CodeBlock.of("0")
                                LONG -> CodeBlock.of("0L")
                                CHAR -> CodeBlock.of("'\\u0000'")
                                FLOAT -> CodeBlock.of("0f")
                                DOUBLE -> CodeBlock.of("0.0")
                                else -> throw AssertionError()
                            }

                        else -> CodeBlock.of("null")
                    }
                )
                .mutable()
                .build(),
            helper = if (type.isPrimitive && !shouldUseAdapter || type.isNullable && hasDefaultValue) {
                PropertySpec
                    .builder(nameAllocator.newName("${name}IsSet"), BOOLEAN)
                    .mutable()
                    .initializer("false")
                    .build()
            } else {
                null
            }
        )

}

private data class LegacyPropertyVariables(
    val value: PropertySpec,
    val helper: PropertySpec?
) {
    val isNotSet: CodeBlock by lazy(LazyThreadSafetyMode.NONE) {
        if (helper == null) {
            CodeBlock.of("%N == null", value)
        } else {
            CodeBlock.of("!%N", helper)
        }
    }
    val isSet: CodeBlock by lazy(LazyThreadSafetyMode.NONE) {
        if (helper == null) {
            CodeBlock.of("%N != null", value)
        } else {
            CodeBlock.of("%N", helper)
        }
    }

}

private fun Property.annotations(createAnnotationsUsingConstructor: Boolean): CodeBlock =
    CodeBlock.builder()
        .add("%M(", Functions.Kotlin.setOf)
        .applyIf(jsonQualifiers.size > 1) {
            indent()
            add("\n")
        }
        .applyEachIndexed(jsonQualifiers.sortedBy { it.annotationName }) { index, qualifier ->
            if (index > 0) add(",\n")
            add(qualifier.render(createAnnotationsUsingConstructor))
        }
        .applyIf(jsonQualifiers.size > 1) {
            unindent()
            add("\n")
        }
        .add(")")
        .build()


private val TypeName.hasTypeVariable: Boolean
    get() = when (this) {
        is ClassName -> false
        Dynamic -> false
        is LambdaTypeName -> receiver?.hasTypeVariable ?: false || parameters.any { it.type.hasTypeVariable } || returnType.hasTypeVariable
        is ParameterizedTypeName -> typeArguments.any { it.hasTypeVariable }
        is TypeVariableName -> true
        is WildcardTypeName -> inTypes.any { it.hasTypeVariable } || outTypes.any { it.hasTypeVariable }
    }
