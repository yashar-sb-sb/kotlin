/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.run.tasks

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil.getExternalProjectInfo
import com.intellij.openapi.module.Module
import org.jetbrains.kotlin.config.KotlinFacetSettingsProvider
import org.jetbrains.kotlin.idea.caches.project.implementingModules
import org.jetbrains.kotlin.idea.caches.project.isMPPModule
import org.jetbrains.kotlin.idea.configuration.KotlinTargetData
import org.jetbrains.kotlin.idea.project.platform
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.plugins.gradle.execution.test.runner.GradleTestTasksProvider
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverUtil
import org.jetbrains.plugins.gradle.util.GradleConstants

abstract class KotlinMultiplatformGradleTestTasksProvider : GradleTestTasksProvider {
    private companion object {
        private val LOG = Logger.getInstance(KotlinMultiplatformGradleTestTasksProvider::class.java)

        private const val TASK_NAME_SUFFIX = "Test"
        private const val CLEAN_NAME_PREFIX = "clean"
    }

    abstract fun isApplicable(module: Module): Boolean

    override fun getTasks(module: Module): List<String> {
        if (module.isDisposed || !isMultiplatformTestModule(module)) {
            return emptyList()
        }

        val platform = module.platform ?: return emptyList()
        if (platform.isCommon()) {
            val implementingModules = module.getImplementingLeaves()
            return implementingModules.flatMap(::getTasksForMultiplatformModule)
        }

        return getTasksForMultiplatformModule(module)
    }

    private fun getTasksForMultiplatformModule(module: Module): List<String> {
        if (module.isDisposed || !isApplicable(module)) {
            return emptyList()
        }

        val gradlePath = GradleProjectResolverUtil.getGradlePath(module) ?: return emptyList()
        val taskNamePrefix = if (gradlePath.endsWith(':')) gradlePath else "$gradlePath:"

        val moduleProperties = ExternalSystemModulePropertyManager.getInstance(module)
        val externalProjectId = moduleProperties.getLinkedProjectId() ?: return emptyList()
        val projectPath = moduleProperties.getLinkedProjectPath() ?: return emptyList()

        val projectInfo = getExternalProjectInfo(module.project, GradleConstants.SYSTEM_ID, projectPath) ?: return emptyList()
        val moduleData = GradleProjectResolverUtil.findModule(projectInfo.externalProjectStructure, projectPath) ?: return emptyList()

        val applicableTargets = ExternalSystemApiUtil.findAll(moduleData, KotlinTargetData.KEY)
            .filter { externalProjectId in it.data.moduleIds }

        if (applicableTargets.size > 1) {
            val targetNames = applicableTargets.joinToString { it.data.externalName }
            LOG.warn("Several targets ($targetNames) are applicable for module $module")

            return emptyList()
        }

        val applicableTarget = applicableTargets.firstOrNull() ?: return emptyList()
        val tasksToRun = findTasksToRun(moduleData, applicableTarget) ?: return emptyList()
        return listOf(taskNamePrefix + tasksToRun.cleanTestTask, taskNamePrefix + tasksToRun.testTask)
    }

    private fun isMultiplatformTestModule(module: Module): Boolean {
        val settings = KotlinFacetSettingsProvider.getInstance(module.project).getInitializedSettings(module)
        return settings.isMPPModule && settings.isTestModule
    }

    private class TasksToRun(val cleanTestTask: String, val testTask: String)

    private fun findTasksToRun(moduleData: DataNode<ModuleData>, target: DataNode<KotlinTargetData>): TasksToRun? {
        val targetName = target.data.externalName

        val cleanTestTaskName = CLEAN_NAME_PREFIX + targetName.capitalize() + TASK_NAME_SUFFIX
        val testTaskName = targetName + TASK_NAME_SUFFIX

        // For some reason, 'cleanPlatformTest' task is absent in the tasks list. So check just the main test task.
        if (ExternalSystemApiUtil.findAll(moduleData, ProjectKeys.TASK).firstOrNull { it.data.name == testTaskName } == null) {
            return null
        }

        return TasksToRun(cleanTestTaskName, testTaskName)
    }
}

private fun Module.getImplementingLeaves(): List<Module> {
    val leaves = mutableListOf<Module>()

    for (module in implementingModules) {
        val platform = module.platform ?: continue
        if (platform.isCommon()) {
            leaves += module.getImplementingLeaves()
        } else {
            leaves += module
        }
    }

    return leaves
}