/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.stepping;

import com.intellij.debugger.DebuggerBundle;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.engine.*;
import com.intellij.debugger.engine.DebugProcessImpl.ResumeCommand;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.debugger.ui.breakpoints.BreakpointManager;
import com.intellij.debugger.ui.breakpoints.StepIntoBreakpoint;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

class KotlinStepIntoActionFactory {
    private KotlinStepIntoActionFactory() {}

    private static final Logger LOG = Logger.getInstance(KotlinStepIntoActionFactory.class);

    static ResumeCommand create(SuspendContextImpl suspendContext, boolean ignoreFilters, MethodFilter methodFilter, int stepSize) {
        DebugProcessImpl debugProcess = suspendContext.getDebugProcess();
        ThreadBlockedMonitor threadBlockedMonitor = findMonitor(debugProcess);
        if (threadBlockedMonitor == null) {
            return null;
        }

        Project project = debugProcess.getProject();
        DebuggerSession session = debugProcess.getSession();
        BreakpointManager breakpointManager = DebuggerManagerEx.getInstanceEx(project).getBreakpointManager();

        boolean forcedIgnoreFilters = ignoreFilters || methodFilter != null;
        StepIntoBreakpoint breakpoint = (methodFilter instanceof BreakpointStepMethodFilter) ? breakpointManager.addStepIntoBreakpoint(
                (BreakpointStepMethodFilter) methodFilter) : null;

        boolean resumeOnlyCurrentThread = DebuggerSettings.getInstance().RESUME_ONLY_CURRENT_THREAD;

        // This class is copied from com.intellij.debugger.engine.DebugProcessImpl.StepIntoCommand.
        // Changed parts are marked with '// MODIFICATION: ' comments.
        return suspendContext.getDebugProcess().new ResumeCommand(suspendContext) {
            @Override
            protected void resumeAction() {
                SuspendContextImpl context = getSuspendContext();

                if (context != null &&
                    (context.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD || resumeOnlyCurrentThread)) {
                    threadBlockedMonitor.startWatching(myContextThread);
                }
                if (context != null
                    && resumeOnlyCurrentThread
                    && context.getSuspendPolicy() == EventRequest.SUSPEND_ALL
                    && myContextThread != null
                ) {
                    SuspendManager suspendManager = context.getDebugProcess().getSuspendManager();
                    suspendManager.resumeThread(context, myContextThread);
                }
                else {
                    super.resumeAction();
                }
            }

            @Override
            public void contextAction() {
                SuspendContextImpl suspendContext = getSuspendContext();
                if (suspendContext == null) {
                    return;
                }

                debugProcess.showStatusText(DebuggerBundle.message("status.step.into"));
                ThreadReferenceProxyImpl stepThread = getContextThread();
                // MODIFICATION: Start Kotlin implementation
                RequestHint hint = new RequestHint(stepThread, suspendContext, StepRequest.STEP_LINE, StepRequest.STEP_INTO, methodFilter) {
                    @Override
                    protected boolean isTheSameFrame(SuspendContextImpl context) {
                        return false; // super.isTheSameFrame(context);
                    }
                };
                // MODIFICATION: End Kotlin implementation
                hint.setResetIgnoreFilters(methodFilter != null && !session.shouldIgnoreSteppingFilters());
                if (forcedIgnoreFilters) {
                    try {
                        int frameCount = (stepThread != null) ? stepThread.frameCount() : 0;
                        session.setIgnoreStepFiltersFlag(frameCount);
                    }
                    catch (EvaluateException e) {
                        LOG.info(e);
                    }
                }
                hint.setIgnoreFilters(forcedIgnoreFilters || session.shouldIgnoreSteppingFilters());
                applyThreadFilter(stepThread);
                if (breakpoint != null) {
                    breakpoint.setSuspendPolicy(suspendContext.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD? DebuggerSettings.SUSPEND_THREAD : DebuggerSettings.SUSPEND_ALL);
                    breakpoint.createRequest(suspendContext.getDebugProcess());
                    breakpoint.setRequestHint(hint);
                    debugProcess.setRunToCursorBreakpoint(breakpoint);
                }
                doStepCompat(suspendContext, stepThread, stepSize, StepRequest.STEP_INTO, hint);
                super.contextAction();
            }
        };
    }

    private static void doStepCompat(
            SuspendContextImpl suspendContext,
            ThreadReferenceProxyImpl stepThread,
            int size,
            @SuppressWarnings("SameParameterValue") int depth,
            RequestHint hint
    ) {
        Method method;
        try {
            method = DebugProcessImpl.class.getDeclaredMethod(
                    "doStep",
                    SuspendContextImpl.class, ThreadReferenceProxyImpl.class, Integer.TYPE, Integer.TYPE, RequestHint.class
            );
        }
        catch (NoSuchMethodException e) {
            LOG.error("'doStep()' method was not found, stepping action aborted");
            return;
        }

        try {
            method.setAccessible(true);
            method.invoke(suspendContext.getDebugProcess(), suspendContext, stepThread, size, depth, hint);
        }
        catch (IllegalAccessException | InvocationTargetException e) {
            LOG.error("Error while calling 'doStep()' using Java reflection API", e);
        }
    }

    @Nullable
    private static ThreadBlockedMonitor findMonitor(@NotNull DebugProcessImpl debugProcess) {
        Field[] fields = DebugProcessImpl.class.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType().equals(ThreadBlockedMonitor.class)) {
                try {
                    field.setAccessible(true);
                    return (ThreadBlockedMonitor) field.get(debugProcess);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }

        return null;
    }
}