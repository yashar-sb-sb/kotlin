/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.IdentifierHighlighterPassFactory
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.completion.test.ExpectedCompletionUtils
import org.jetbrains.kotlin.idea.completion.test.handlers.AbstractCompletionHandlerTest
import org.jetbrains.kotlin.idea.completion.test.handlers.CompletionHandlerTestBase.Companion.completionChars
import org.jetbrains.kotlin.idea.completion.test.handlers.CompletionHandlerTestBase.Companion.getExistentLookupElement
import org.jetbrains.kotlin.idea.completion.test.handlers.CompletionHandlerTestBase.Companion.selectItem
import org.jetbrains.kotlin.idea.highlighter.AbstractHighlightAndTypingTest.Companion.ACTIONS_PREFIX
import org.jetbrains.kotlin.idea.highlighter.AbstractHighlightAndTypingTest.Companion.INSPECTIONS_PREFIX
import org.jetbrains.kotlin.idea.highlighter.AbstractHighlightAndTypingTest.Companion.enableInspections
import org.jetbrains.kotlin.idea.highlighter.AbstractHighlightingTest
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.testFramework.commitAllDocuments
import org.jetbrains.kotlin.test.InTextDirectivesUtils
import java.io.File

/**
 * inspired by @see AbstractHighlightAndTypingTest
 */
typealias HighlightInfos = List<HighlightInfo>

abstract class AbstractPerformanceHighlightAndTypingTest : KotlinLightCodeInsightFixtureTestCase() {

    private val defaultCompletionType: CompletionType = CompletionType.BASIC

    companion object {
        @JvmStatic
        var warmedUp: Boolean = false

        @JvmStatic
        val stats: Stats = Stats("highlight_typing")

        init {
            // there is no @AfterClass for junit3.8
            Runtime.getRuntime().addShutdownHook(Thread(Runnable { stats.close() }))
        }
    }

    override fun setUp() {
        super.setUp()

        if (!warmedUp) {
            doWarmUpPerfTest()
            warmedUp = true
        }
    }

    override fun tearDown() {
        commitAllDocuments()
        super.tearDown()
    }

    private fun doWarmUpPerfTest() {
        innerPerfTest(
            "warm-up",
            setUpBody = {
                myFixture.configureByText(
                    KotlinFileType.INSTANCE,
                    "class Foo {\n    private val value: String? = null\n}"
                )
                it.setUpValue = highlights()
            },
            testBody = { it.value = mutableListOf(highlights()) })
    }

    protected fun doPerfTest(filePath: String) {
        val testName = getTestName(false)

        val fileText = FileUtil.loadFile(File(filePath), true)
        val checkInfos = !InTextDirectivesUtils.isDirectiveDefined(fileText, AbstractHighlightingTest.NO_CHECK_INFOS_PREFIX)
        val checkWeakWarnings = !InTextDirectivesUtils.isDirectiveDefined(fileText, AbstractHighlightingTest.NO_CHECK_WEAK_WARNINGS_PREFIX)
        val checkWarnings = !InTextDirectivesUtils.isDirectiveDefined(fileText, AbstractHighlightingTest.NO_CHECK_WARNINGS_PREFIX)
        val expectedDuplicatedHighlighting = InTextDirectivesUtils.isDirectiveDefined(
            fileText,
            AbstractHighlightingTest.EXPECTED_DUPLICATED_HIGHLIGHTING_PREFIX
        )

        val invocationCount = InTextDirectivesUtils.getPrefixedInt(fileText, AbstractCompletionHandlerTest.INVOCATION_COUNT_PREFIX) ?: 1
        val lookupString = InTextDirectivesUtils.findStringWithPrefixes(fileText, AbstractCompletionHandlerTest.LOOKUP_STRING_PREFIX)
        val itemText = InTextDirectivesUtils.findStringWithPrefixes(fileText, AbstractCompletionHandlerTest.ELEMENT_TEXT_PREFIX)
        val tailText = InTextDirectivesUtils.findStringWithPrefixes(fileText, AbstractCompletionHandlerTest.TAIL_TEXT_PREFIX)
        val actions = InTextDirectivesUtils.findListWithPrefixes(fileText, ACTIONS_PREFIX) ?: emptyList()
        val completionType = ExpectedCompletionUtils.getCompletionType(fileText) ?: defaultCompletionType
        val inspections = InTextDirectivesUtils.findListWithPrefixes(fileText, INSPECTIONS_PREFIX) ?: emptyList()
        val completionChars = completionChars(fileText)

        doWithAnnotations(inspections) {
            innerPerfTest(testName,
                          setUpBody = {
                              val file = myFixture.configureByFile(filePath)
                              myFixture.allowTreeAccessForAllFiles()
                              commitAllDocuments()

                              assertTrue("side effect: to load the text", file.textOffset >= 0)

                              // to load AST for changed files before it's prohibited by "fileTreeAccessFilter"
                              CodeInsightTestFixtureImpl.ensureIndexesUpToDate(myFixture.project)

                              // check initial highlight
                              checkHighlighting(
                                  expectedDuplicatedHighlighting,
                                  checkWarnings,
                                  checkInfos,
                                  checkWeakWarnings
                              )
                              it.setUpValue = highlights()
                              it.value = mutableListOf<HighlightInfos>()
                          },
                          testBody = { perfValue ->
                              doTestWithTextLoaded(
                                  completionType,
                                  invocationCount,
                                  lookupString,
                                  itemText,
                                  tailText,
                                  completionChars,
                                  actions
                              ) {
                                  perfValue.value?.add(highlights())
                              }
                          })
        }
    }

    private fun doTestWithTextLoaded(
        completionType: CompletionType,
        time: Int,
        lookupString: String?,
        itemText: String?,
        tailText: String?,
        completionChars: String,
        actions: List<String>? = emptyList(),
        afterTypingBlock: () -> Unit = {}
    ) {
        for (idx in 0 until completionChars.length - 1) {
            myFixture.type(completionChars[idx])
            afterTypingBlock()
        }

        if (actions != null && actions.isNotEmpty()) {
            for (action in actions) {
                myFixture.performEditorAction(action)
            }
        }

        myFixture.complete(completionType, time)

        if (lookupString != null || itemText != null || tailText != null) {
            val item = getExistentLookupElement(myFixture.project, lookupString, itemText, tailText)
            if (item != null) {
                selectItem(myFixture, item, completionChars.last())
            }
        }
        afterTypingBlock()
    }

    private fun checkHighlighting(
        expectedDuplicatedHighlighting: Boolean,
        checkWarnings: Boolean,
        checkInfos: Boolean,
        checkWeakWarnings: Boolean
    ) {
        withExpectedDuplicatedHighlighting(expectedDuplicatedHighlighting, Runnable {
            try {
                myFixture.checkHighlighting(checkWarnings, checkInfos, checkWeakWarnings)
            } catch (e: Throwable) {
                throw e
            }
        })
    }

    private fun doWithAnnotations(inspections: List<String> = emptyList(), block: () -> Unit) {
        val disposable = Disposer.newDisposable("highlightString")

        if (inspections.isNotEmpty()) {
            commitAllDocuments()

            enableInspections(
                project,
                disposable,
                enabledInspections = inspections
            )
        }

        try {
            IdentifierHighlighterPassFactory.doWithHighlightingEnabled {
                block()
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun highlights(): List<HighlightInfo> =
        myFixture.doHighlighting()

    private fun withExpectedDuplicatedHighlighting(expectedDuplicatedHighlighting: Boolean, runnable: Runnable) {
        if (!expectedDuplicatedHighlighting) {
            runnable.run()
            return
        }

        org.jetbrains.kotlin.idea.highlighter.expectedDuplicatedHighlighting(runnable)
    }

    private fun innerPerfTest(
        name: String,
        setUpBody: (TestData<HighlightInfos, MutableList<HighlightInfos>>) -> Unit,
        testBody: (TestData<HighlightInfos, MutableList<HighlightInfos>>) -> Unit
    ) {
        stats.perfTest<HighlightInfos, MutableList<HighlightInfos>>(
            testName = name,
            setUp = { setUpBody(it) },
            test = { testBody(it) },
            tearDown = {
                assertNotNull("no reasons to validate output as it is a performance test", it.setUpValue)
                assertTrue("no reasons to validate output as it is a performance test", it.value?.isNotEmpty() ?: false)
                runWriteAction {
                    myFixture.file.delete()
                }
            }
        )
    }

}