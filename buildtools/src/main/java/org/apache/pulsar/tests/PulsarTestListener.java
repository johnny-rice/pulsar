/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.tests;

import java.util.Arrays;
import org.testng.IExecutionListener;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.SkipException;
import org.testng.internal.thread.ThreadTimeoutException;

public class PulsarTestListener implements ITestListener, IExecutionListener, ISuiteListener {

    @Override
    public void onTestStart(ITestResult result) {
        ExtendedNettyLeakDetector.setInitialHint(String.format("Test: %s.%s", result.getTestClass().getName(),
                result.getMethod().getMethodName()));
        System.out.format("------- Starting test %s.%s(%s)-------\n", result.getTestClass(),
                result.getMethod().getMethodName(), Arrays.toString(result.getParameters()));
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        System.out.format("------- SUCCESS -- %s.%s(%s)-------\n", result.getTestClass(),
                result.getMethod().getMethodName(), Arrays.toString(result.getParameters()));
        ExtendedNettyLeakDetector.triggerLeakDetection();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        if (!(result.getThrowable() instanceof SkipException)) {
            System.out.format("!!!!!!!!! FAILURE-- %s.%s(%s)-------\n", result.getTestClass(),
                    result.getMethod().getMethodName(), Arrays.toString(result.getParameters()));
            if (result.getThrowable() != null) {
                result.getThrowable().printStackTrace();
                if (result.getThrowable() instanceof ThreadTimeoutException) {
                    System.out.println("====== THREAD DUMPS ======");
                    System.out.println(ThreadDumpUtil.buildThreadDiagnosticString());
                }
            }
        }
        ExtendedNettyLeakDetector.triggerLeakDetection();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        if (!(result.getThrowable() instanceof SkipException)) {
            System.out.format("~~~~~~~~~ SKIPPED -- %s.%s(%s)-------\n", result.getTestClass(),
                    result.getMethod().getMethodName(), Arrays.toString(result.getParameters()));
            if (result.getThrowable() != null) {
                result.getThrowable().printStackTrace();
                if (result.getThrowable() instanceof ThreadTimeoutException) {
                    System.out.println("====== THREAD DUMPS ======");
                    System.out.println(ThreadDumpUtil.buildThreadDiagnosticString());
                }
            }
        }
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        ExtendedNettyLeakDetector.triggerLeakDetection();
    }

    @Override
    public void onStart(ITestContext context) {
        ExtendedNettyLeakDetector.setInitialHint("Starting test: " + context.getName());
    }

    @Override
    public void onFinish(ITestContext context) {
        ExtendedNettyLeakDetector.triggerLeakDetection();
        ExtendedNettyLeakDetector.setInitialHint("Finished test: " + context.getName());
    }

    @Override
    public void onFinish(ISuite suite) {
        ExtendedNettyLeakDetector.setInitialHint("Finished suite: " + suite.getName());
    }

    @Override
    public void onExecutionFinish() {
        if (!ExtendedNettyLeakDetector.isEnabled()) {
            return;
        }
        ExtendedNettyLeakDetector.triggerLeakDetection();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ExtendedNettyLeakDetector.triggerLeakDetection();
    }
}
