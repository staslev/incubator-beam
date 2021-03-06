/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.fn.harness.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.beam.harness.test.TestExecutors;
import org.apache.beam.harness.test.TestExecutors.TestExecutorService;
import org.apache.beam.harness.test.TestStreams;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DirectStreamObserver}. */
@RunWith(JUnit4.class)
public class DirectStreamObserverTest {
  @Rule public TestExecutorService executor = TestExecutors.from(Executors::newCachedThreadPool);

  @Test
  public void testThreadSafety() throws Exception {
    List<String> onNextValues = new ArrayList<>();
    AdvancingPhaser phaser = new AdvancingPhaser(1);
    final AtomicBoolean isCriticalSectionShared = new AtomicBoolean();
    final DirectStreamObserver<String> streamObserver =
        new DirectStreamObserver<>(
            phaser,
            TestStreams.withOnNext(
                new Consumer<String>() {
                  @Override
                  public void accept(String t) {
                    // Use the atomic boolean to detect if multiple threads are in this
                    // critical section. Any thread that enters purposefully blocks by sleeping
                    // to increase the contention between threads artificially.
                    assertFalse(isCriticalSectionShared.getAndSet(true));
                    Uninterruptibles.sleepUninterruptibly(50, TimeUnit.MILLISECONDS);
                    onNextValues.add(t);
                    assertTrue(isCriticalSectionShared.getAndSet(false));
                  }
                }).build());

    List<String> prefixes = ImmutableList.of("0", "1", "2", "3", "4");
    List<Callable<String>> tasks = new ArrayList<>();
    for (String prefix : prefixes) {
      tasks.add(
          new Callable<String>() {
            @Override
            public String call() throws Exception {
              for (int i = 0; i < 10; i++) {
                streamObserver.onNext(prefix + i);
              }
              return prefix;
            }
          });
    }
    executor.invokeAll(tasks);
    streamObserver.onCompleted();

    // Check that order was maintained.
    int[] prefixesIndex = new int[prefixes.size()];
    assertEquals(50, onNextValues.size());
    for (String onNextValue : onNextValues) {
      int prefix = Integer.parseInt(onNextValue.substring(0, 1));
      int suffix = Integer.parseInt(onNextValue.substring(1, 2));
      assertEquals(prefixesIndex[prefix], suffix);
      prefixesIndex[prefix] += 1;
    }
  }

  @Test
  public void testIsReadyIsHonored() throws Exception {
    AdvancingPhaser phaser = new AdvancingPhaser(1);
    final AtomicBoolean elementsAllowed = new AtomicBoolean();
    final DirectStreamObserver<String> streamObserver =
        new DirectStreamObserver<>(
            phaser,
            TestStreams.withOnNext(
                new Consumer<String>() {
                  @Override
                  public void accept(String t) {
                    assertTrue(elementsAllowed.get());
                  }
                }).withIsReady(elementsAllowed::get).build());

    // Start all the tasks
    List<Future<String>> results = new ArrayList<>();
    for (String prefix : ImmutableList.of("0", "1", "2", "3", "4")) {
      results.add(
          executor.submit(
              new Callable<String>() {
                @Override
                public String call() throws Exception {
                  for (int i = 0; i < 10; i++) {
                    streamObserver.onNext(prefix + i);
                  }
                  return prefix;
                }
              }));
    }

    // Have them wait and then flip that we do allow elements and wake up those awaiting
    Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
    elementsAllowed.set(true);
    phaser.arrive();

    for (Future<String> result : results) {
      result.get();
    }
    streamObserver.onCompleted();
  }
}
