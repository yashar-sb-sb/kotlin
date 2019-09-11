/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin.impl

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.builtins.ReflectionTypes
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.NotFoundClasses
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.SupertypeLoopChecker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.load.java.AnnotationTypeQualifierResolver
import org.jetbrains.kotlin.load.java.JavaClassesTracker
import org.jetbrains.kotlin.load.java.components.JavaPropertyInitializerEvaluator
import org.jetbrains.kotlin.load.java.components.JavaResolverCache
import org.jetbrains.kotlin.load.java.components.SamConversionResolver
import org.jetbrains.kotlin.load.java.components.SignaturePropagator
import org.jetbrains.kotlin.load.java.lazy.JavaResolverComponents
import org.jetbrains.kotlin.load.java.lazy.JavaResolverSettings
import org.jetbrains.kotlin.load.java.lazy.LazyJavaPackageFragmentProvider
import org.jetbrains.kotlin.load.java.lazy.SingleModuleClassResolver
import org.jetbrains.kotlin.load.java.typeEnhancement.SignatureEnhancement
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.types.checker.NewKotlinTypeChecker
import org.jetbrains.kotlin.utils.Jsr305State
import org.jetbrains.kotlin.descriptors.runtime.components.ReflectJavaClassFinder
import org.jetbrains.kotlin.descriptors.runtime.components.ReflectKotlinClassFinder
import org.jetbrains.kotlin.descriptors.runtime.components.RuntimeErrorReporter
import org.jetbrains.kotlin.descriptors.runtime.components.RuntimeSourceElementFactory
import org.jetbrains.kotlin.load.kotlin.*
import org.jetbrains.kotlin.serialization.deserialization.ContractDeserializer
import org.jetbrains.kotlin.serialization.deserialization.DeserializationConfiguration
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvm.ClassLoaderByConfiguration


class PackageFragmentFromClassLoaderProviderExtension(
    val classLoaderGetter: ClassLoaderByConfiguration,
    val scriptCompilationConfiguration: ScriptCompilationConfiguration
) : PackageFragmentProviderExtension {

    override fun getPackageFragmentProvider(
        project: Project,
        module: ModuleDescriptor,
        storageManager: StorageManager,
        trace: BindingTrace,
        moduleInfo: ModuleInfo?,
        lookupTracker: LookupTracker
    ): PackageFragmentProvider? {
        val classLoader = classLoaderGetter(scriptCompilationConfiguration)

        val reflectKotlinClassFinder = ReflectKotlinClassFinder(classLoader)
        val deserializedDescriptorResolver = DeserializedDescriptorResolver()
        val notFoundClasses = NotFoundClasses(storageManager, module)
        val annotationTypeQualifierResolver = AnnotationTypeQualifierResolver(storageManager, Jsr305State.DISABLED)
        val javaResolverComponents = JavaResolverComponents(
            storageManager,
            ReflectJavaClassFinder(classLoader), reflectKotlinClassFinder, deserializedDescriptorResolver,
            SignaturePropagator.DO_NOTHING,
            RuntimeErrorReporter, JavaResolverCache.EMPTY,
            JavaPropertyInitializerEvaluator.DoNothing, SamConversionResolver.Empty,
            RuntimeSourceElementFactory,
            SingleModuleClassResolver(),
            PackagePartProvider.Empty, SupertypeLoopChecker.EMPTY, LookupTracker.DO_NOTHING, module,
            ReflectionTypes(module, notFoundClasses), annotationTypeQualifierResolver,
            SignatureEnhancement(annotationTypeQualifierResolver, Jsr305State.DISABLED),
            JavaClassesTracker.Default, JavaResolverSettings.Default, NewKotlinTypeChecker.Default
        )

        val lazyJavaPackageFragmentProvider = LazyJavaPackageFragmentProvider(javaResolverComponents)

        val binaryClassAnnotationAndConstantLoader = BinaryClassAnnotationAndConstantLoaderImpl(
            module, notFoundClasses, storageManager, reflectKotlinClassFinder
        )
        val deserializationComponentsForJava = DeserializationComponentsForJava(
            storageManager, module, DeserializationConfiguration.Default,
            JavaClassDataFinder(reflectKotlinClassFinder, deserializedDescriptorResolver),
            binaryClassAnnotationAndConstantLoader, lazyJavaPackageFragmentProvider, notFoundClasses,
            RuntimeErrorReporter, LookupTracker.DO_NOTHING, ContractDeserializer.DEFAULT, NewKotlinTypeChecker.Default
        )

        deserializedDescriptorResolver.setComponents(deserializationComponentsForJava)

        return lazyJavaPackageFragmentProvider
    }
}

