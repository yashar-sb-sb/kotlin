/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.containers.SLRUCache
import org.jetbrains.kotlin.analyzer.ResolverForProject
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.idea.caches.project.*
import org.jetbrains.kotlin.idea.caches.project.IdeaModuleInfo
import org.jetbrains.kotlin.idea.caches.resolve.util.contextWithCompositeExceptionTracker
import org.jetbrains.kotlin.idea.caches.trackers.KotlinCodeBlockModificationListener
import org.jetbrains.kotlin.idea.core.script.ScriptDependenciesModificationTracker
import org.jetbrains.kotlin.idea.core.script.dependencies.ScriptAdditionalIdeaDependenciesProvider
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile

internal class KotlinCacheServiceForScripts(
    val project: Project,
    val facadeForSdk: (PlatformAnalysisSettings) -> ProjectResolutionFacade,
    val facadeForModules: (PlatformAnalysisSettings) -> ProjectResolutionFacade
) {

    fun getFacadeForScripts(files: List<KtFile>, moduleInfo: IdeaModuleInfo): ResolutionFacade? {
        return when {
            isRegularScript(moduleInfo, files) -> {
                val projectFacade = getFacadeForScripts(files.toSet())
                ModuleResolutionFacadeImpl(projectFacade, moduleInfo).createdFor(files, moduleInfo)
            }
            isScriptDependencies(moduleInfo) -> {
                val projectFacade = wrapWitSyntheticFiles(
                    facadeForScriptDependenciesForProject,
                    ResolverForProject.resolverForScriptDependenciesName,
                    files.toSet()
                )
                ModuleResolutionFacadeImpl(projectFacade, moduleInfo).createdFor(files, moduleInfo)
            }
            else -> null
        }
    }

    fun getFacadeForScriptDependencies(moduleInfo: IdeaModuleInfo): ResolutionFacade? {
        val projectFacade = when (moduleInfo) {
            is ScriptDependenciesInfo.ForProject,
            is ScriptDependenciesSourceInfo.ForProject -> facadeForScriptDependenciesForProject
            is ScriptDependenciesInfo.ForFile -> createFacadeForScriptDependencies(moduleInfo)
            else -> return null
        }
        return ModuleResolutionFacadeImpl(projectFacade, moduleInfo)
    }

    private val facadeForScriptDependenciesForProject = createFacadeForScriptDependencies(ScriptDependenciesInfo.ForProject(project))

    private fun createFacadeForScriptDependencies(dependenciesModuleInfo: ScriptDependenciesInfo): ProjectResolutionFacade {
        val sdk = dependenciesModuleInfo.sdk
        val platform = JvmPlatforms.defaultJvmPlatform // TODO: Js scripts?
        val settings = PlatformAnalysisSettings.create(
            project, platform, sdk, true,
            LanguageFeature.ReleaseCoroutines.defaultState == LanguageFeature.State.ENABLED
        )

        val dependenciesForScriptDependencies = listOf(
            LibraryModificationTracker.getInstance(project),
            ProjectRootModificationTracker.getInstance(project),
            ScriptDependenciesModificationTracker.getInstance(project)
        )

        val scriptFile = (dependenciesModuleInfo as? ScriptDependenciesInfo.ForFile)?.scriptFile
        val relatedModules = scriptFile?.let { ScriptAdditionalIdeaDependenciesProvider.getRelatedModules(it, project) }
        val globalFacade =
            if (relatedModules?.isNotEmpty() == true) {
                facadeForModules(settings)
            } else {
                facadeForSdk(settings)
            }

        val globalContext = globalFacade.globalContext.contextWithCompositeExceptionTracker(project, "facadeForScriptDependencies")
        return ProjectResolutionFacade(
            "facadeForScriptDependencies",
            ResolverForProject.resolverForScriptDependenciesName,
            project, globalContext, settings,
            reuseDataFrom = globalFacade,
            allModules = dependenciesModuleInfo.dependencies(),
            //TODO: provide correct trackers
            dependencies = dependenciesForScriptDependencies,
            moduleFilter = { it == dependenciesModuleInfo },
            invalidateOnOOCB = true,
            builtInsCache = globalFacade.builtInsCache
        )
    }

    private fun wrapWitSyntheticFiles(
        reuseDataFrom: ProjectResolutionFacade,
        debugNamePrefix: String,
        files: Set<KtFile>,
        moduleFilter: (IdeaModuleInfo) -> Boolean = reuseDataFrom.moduleFilter
    ): ProjectResolutionFacade {
        val debugName = debugNamePrefix + " with synthetic files ${files.joinToString { it.name }}"
        val globalContext = reuseDataFrom.globalContext.contextWithCompositeExceptionTracker(project, debugName)

        return ProjectResolutionFacade(
            debugString = "facade for $debugNamePrefix",
            resolverDebugName = debugName,
            project = project,
            globalContext = globalContext,
            settings = reuseDataFrom.settings,
            syntheticFiles = files,
            reuseDataFrom = reuseDataFrom,
            moduleFilter = moduleFilter,
            dependencies = listOf(
                KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker
            ),
            invalidateOnOOCB = true,
            builtInsCache = reuseDataFrom.builtInsCache
        )
    }

    private fun createFacadeForScripts(files: Set<KtFile>): ProjectResolutionFacade {
        // we assume that all files come from the same module
        val scriptModuleInfo = files.map(KtFile::getModuleInfo).toSet().single()
        val platform = scriptModuleInfo.platform
        val settings = scriptModuleInfo.platformSettings(project, platform)

        if (scriptModuleInfo is ModuleSourceInfo) {
            val dependentModules = scriptModuleInfo.getDependentModules()
            return wrapWitSyntheticFiles(facadeForModules(settings), ResolverForProject.resolverForScriptsName, files) {
                it in dependentModules
            }
        }

        check(scriptModuleInfo is ScriptModuleInfo) {
            "Unknown ModuleInfo for scripts ${scriptModuleInfo::class.java}"
        }

        val facadeForScriptDependencies = createFacadeForScriptDependencies(
            ScriptDependenciesInfo.ForFile(project, scriptModuleInfo.scriptFile, scriptModuleInfo.scriptDefinition)
        )

        return wrapWitSyntheticFiles(facadeForScriptDependencies, ResolverForProject.resolverForScriptsName, files) {
            it == scriptModuleInfo
        }
    }

    private val scriptsCacheProvider = CachedValueProvider {
        CachedValueProvider.Result(
            object : SLRUCache<Set<KtFile>, ProjectResolutionFacade>(10, 5) {
                override fun createValue(files: Set<KtFile>) = createFacadeForScripts(files)
            },
            LibraryModificationTracker.getInstance(project),
            ProjectRootModificationTracker.getInstance(project),
            ScriptDependenciesModificationTracker.getInstance(project)
        )
    }

    private fun getFacadeForScripts(files: Set<KtFile>): ProjectResolutionFacade {
        val cachedValue: SLRUCache<Set<KtFile>, ProjectResolutionFacade> =
            CachedValuesManager.getManager(project).getCachedValue(project, scriptsCacheProvider)

        return synchronized(cachedValue) {
            cachedValue.get(files)
        }
    }

    private fun isScriptDependencies(moduleInfo: IdeaModuleInfo) =
        moduleInfo is ScriptDependenciesInfo || moduleInfo is ScriptDependenciesSourceInfo

    // In some cases (for ex. during scratch compilation) we analyzing simple KtFile in context of KtScript,
    // so its moduleInfo will be ScriptModuleInfo, but it won't be a script it self
    private fun isRegularScript(moduleInfo: IdeaModuleInfo, files: List<KtFile>): Boolean {
        if (moduleInfo is ScriptModuleInfo) return true
        if (moduleInfo is ModuleSourceInfo) {
            return files.filter { it.isScript() }.toSet().isNotEmpty()
        }
        return false
    }


}