package se.ansman.kotshi.kapt.generators

import com.squareup.kotlinpoet.metadata.isObject
import kotlinx.metadata.KmClass
import se.ansman.kotshi.kapt.MetadataAccessor
import se.ansman.kotshi.model.GeneratableJsonAdapter
import se.ansman.kotshi.model.GlobalConfig
import se.ansman.kotshi.model.ObjectJsonAdapter
import javax.annotation.processing.Messager
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

class ObjectAdapterGenerator(
    metadataAccessor: MetadataAccessor,
    types: Types,
    elements: Elements,
    element: TypeElement,
    metadata: KmClass,
    globalConfig: GlobalConfig,
    messager: Messager,
) : AdapterGenerator(metadataAccessor, types, elements, element, metadata, globalConfig, messager) {
    init {
        require(metadata.isObject)
    }

    override fun getGeneratableJsonAdapter(): GeneratableJsonAdapter =
        ObjectJsonAdapter(
            targetPackageName = targetClassName.packageName,
            targetSimpleNames = targetClassName.simpleNames,
            polymorphicLabels = getPolymorphicLabels()
        )
}