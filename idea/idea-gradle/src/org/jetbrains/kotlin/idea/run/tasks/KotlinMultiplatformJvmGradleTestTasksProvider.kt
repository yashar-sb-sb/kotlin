/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.run.tasks

import com.intellij.openapi.module.Module
import org.jetbrains.kotlin.idea.project.platform
import org.jetbrains.kotlin.platform.jvm.isJvm

class KotlinMultiplatformJvmGradleTestTasksProvider : KotlinMultiplatformGradleTestTasksProvider() {
    override fun isApplicable(module: Module) = module.platform.isJvm()
}