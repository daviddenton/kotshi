package se.ansman.kotshi.ksp.generators

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.moshi.Json
import se.ansman.kotshi.JsonDefaultValue
import se.ansman.kotshi.ksp.KspProcessingError
import se.ansman.kotshi.ksp.getAnnotation
import se.ansman.kotshi.ksp.getValue
import se.ansman.kotshi.model.EnumJsonAdapter
import se.ansman.kotshi.model.GeneratableJsonAdapter
import se.ansman.kotshi.model.GlobalConfig

class EnumAdapterGenerator(
    environment: SymbolProcessorEnvironment,
    resolver: Resolver,
    element: KSClassDeclaration,
    globalConfig: GlobalConfig
) : AdapterGenerator(environment, resolver, element, globalConfig) {
    init {
        require(Modifier.ENUM in element.modifiers)
    }

    override fun getGenerableAdapter(): GeneratableJsonAdapter {
        val entries = targetElement.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .toList()

        val defaultValues = entries.filter { it.getAnnotation<JsonDefaultValue>() != null }
        if (defaultValues.size > 1) {
            throw KspProcessingError("Only one enum entry can be annotated with @JsonDefaultValue", targetElement)
        }

        return EnumJsonAdapter(
            targetPackageName = targetClassName.packageName,
            targetSimpleNames = targetClassName.simpleNames,
            entries = entries.map { it.toEnumEntry() },
            fallback = defaultValues.singleOrNull()?.toEnumEntry()
        )
    }

    private fun KSClassDeclaration.toEnumEntry(): EnumJsonAdapter.Entry =
        EnumJsonAdapter.Entry(
            name = simpleName.getShortName(),
            serializedName = getAnnotation<Json>()?.getValue("name") ?: simpleName.getShortName()
        )
}