/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativeplatform.test.xctest.internal;

import com.google.common.base.Joiner;
import org.gradle.api.internal.tasks.testing.DefaultTestClassDescriptor;
import org.gradle.api.internal.tasks.testing.DefaultTestMethodDescriptor;
import org.gradle.api.internal.tasks.testing.DefaultTestOutputEvent;
import org.gradle.api.internal.tasks.testing.TestCompleteEvent;
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.internal.tasks.testing.TestStartEvent;
import org.gradle.api.tasks.testing.TestOutputEvent;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.io.TextStream;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.internal.time.Clock;
import org.gradle.util.TextUtil;

import javax.annotation.Nullable;
import java.util.Deque;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class XcTestScraper implements TextStream {
    private static final Pattern TEST_FAILURE_PATTERN = Pattern.compile(":\\d+: error: (-\\[\\p{Alnum}+.)?(\\p{Alnum}+)[ .](\\p{Alnum}+)]? : (.*)");

    private final TestResultProcessor processor;
    private final TestOutputEvent.Destination destination;
    private final IdGenerator<?> idGenerator;
    private final Clock clock;
    private final Deque<XCTestDescriptor> testDescriptors;
    private TestDescriptorInternal lastDescriptor;

    XcTestScraper(TestOutputEvent.Destination destination, TestResultProcessor processor, IdGenerator<?> idGenerator, Clock clock, Deque<XCTestDescriptor> testDescriptors) {
        this.processor = processor;
        this.destination = destination;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.testDescriptors = testDescriptors;
    }

    @Override
    public void text(String text) {
        synchronized (testDescriptors) {
            Scanner scanner = new Scanner(text).useDelimiter("'");
            if (scanner.hasNext()) {
                String token = scanner.next().trim();
                if (token.equals("Test Suite")) {
                    // Test Suite 'PassingTestSuite' started at 2017-10-30 10:45:47.828
                    String testSuite = scanner.next();
                    if (testSuite.equals("All tests") || testSuite.endsWith(".xctest")) {
                        // ignore these test suites
                        return;
                    }
                    String status = scanner.next();
                    boolean started = status.contains("started at");

                    if (started) {
                        TestDescriptorInternal testDescriptor = new DefaultTestClassDescriptor(idGenerator.generateId(), testSuite);  // Using DefaultTestClassDescriptor to fake JUnit test
                        processor.started(testDescriptor, new TestStartEvent(clock.getCurrentTime()));
                        testDescriptors.push(new XCTestDescriptor(testDescriptor));
                    } else {
                        XCTestDescriptor xcTestDescriptor = testDescriptors.pop();
                        lastDescriptor = xcTestDescriptor.getDescriptorInternal();
                        TestDescriptorInternal testDescriptor = xcTestDescriptor.getDescriptorInternal();
                        TestResult.ResultType resultType = TestResult.ResultType.SUCCESS;
                        boolean failed = status.contains("failed at");
                        if (failed) {
                            resultType = TestResult.ResultType.FAILURE;
                        }

                        processor.completed(testDescriptor.getId(), new TestCompleteEvent(clock.getCurrentTime(), resultType));
                    }
                } else if (token.equals("Test Case")) {
                    // Looks like: Test Case '-[AppTest.PassingTestSuite testCanPassTestCaseWithAssertion]' started.
                    String testSuiteAndCase = scanner.next();
                    String[] splits = testSuiteAndCase.
                        replace('[', ' ').
                        replace(']', ' ').
                        split("[. ]");
                    String testSuite = splits[OperatingSystem.current().isMacOsX() ? 2 : 0];
                    String testCase = splits[OperatingSystem.current().isMacOsX() ? 3 : 1];
                    String status = scanner.next().trim();
                    boolean started = status.contains("started");

                    if (started) {
                        TestDescriptorInternal testDescriptor = new DefaultTestMethodDescriptor(idGenerator.generateId(), testSuite, testCase);
                        processor.started(testDescriptor, new TestStartEvent(clock.getCurrentTime()));
                        testDescriptors.push(new XCTestDescriptor(testDescriptor));
                    } else {
                        XCTestDescriptor xcTestDescriptor = testDescriptors.pop();
                        lastDescriptor = xcTestDescriptor.getDescriptorInternal();
                        TestDescriptorInternal testDescriptor = xcTestDescriptor.getDescriptorInternal();
                        TestResult.ResultType resultType = TestResult.ResultType.SUCCESS;
                        boolean failed = status.contains("failed");
                        if (failed) {
                            resultType = TestResult.ResultType.FAILURE;
                            processor.failure(testDescriptor.getId(), new Throwable(Joiner.on(TextUtil.getPlatformLineSeparator()).join(xcTestDescriptor.getMessages())));
                        }

                        processor.completed(testDescriptor.getId(), new TestCompleteEvent(clock.getCurrentTime(), resultType));
                    }
                } else {
                    XCTestDescriptor xcTestDescriptor = testDescriptors.peek();
                    if (xcTestDescriptor != null) {
                        TestDescriptorInternal testDescriptor = xcTestDescriptor.getDescriptorInternal();

                        processor.output(testDescriptor.getId(), new DefaultTestOutputEvent(destination, text));

                        Matcher failureMessageMatcher = TEST_FAILURE_PATTERN.matcher(text);
                        if (failureMessageMatcher.find()) {
                            String testSuite = failureMessageMatcher.group(2);
                            String testCase = failureMessageMatcher.group(3);
                            String message = failureMessageMatcher.group(4);

                            if (testDescriptor.getClassName().equals(testSuite) && testDescriptor.getName().equals(testCase)) {
                                xcTestDescriptor.getMessages().add(message);
                            }
                        }

                        // If no current test can be associated to the output, the last known descriptor is used.
                        // See https://bugs.swift.org/browse/SR-1127 for more information.
                    } else if (lastDescriptor != null) {
                        processor.output(lastDescriptor.getId(), new DefaultTestOutputEvent(destination, text));
                    }
                }
            }
        }
    }

    @Override
    public void endOfStream(@Nullable Throwable failure) {
        if (failure != null) {
            while (!testDescriptors.isEmpty()) {
                processor.failure(testDescriptors.pop(), failure);
            }
        }
    }

}