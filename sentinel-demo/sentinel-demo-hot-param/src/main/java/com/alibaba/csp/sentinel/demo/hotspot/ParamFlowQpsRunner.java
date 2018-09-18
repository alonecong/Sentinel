/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.demo.hotspot;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * A traffic runner to simulate flow for different parameters.
 *
 * @author Eric Zhao
 * @since 0.2.0
 */
class ParamFlowQpsRunner<T> {

    private final T[] params;
    private final String resourceName;
    private int seconds;
    private final int threadCount;

    private final Map<T, AtomicLong> passCountMap = new ConcurrentHashMap<>();

    private volatile boolean stop = false;

    public ParamFlowQpsRunner(T[] params, String resourceName, int threadCount, int seconds) {
        AssertUtil.isTrue(params != null && params.length > 0, "Parameter array should not be empty");
        AssertUtil.isTrue(StringUtil.isNotBlank(resourceName), "Resource name cannot be empty");
        AssertUtil.isTrue(seconds > 0, "Time period should be positive");
        AssertUtil.isTrue(threadCount > 0 && threadCount <= 1000, "Invalid thread count");
        this.params = params;
        this.resourceName = resourceName;
        this.seconds = seconds;
        this.threadCount = threadCount;

        for (T param : params) {
            AssertUtil.isTrue(param != null, "Parameters should not be null");
            passCountMap.putIfAbsent(param, new AtomicLong());
        }
    }

    private T generateParam() {
        int i = ThreadLocalRandom.current().nextInt(0, params.length);
        return params[i];
    }

    void simulateTraffic() {
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(new RunTask());
            t.setName("simulate-traffic-task-" + i);
            t.start();
        }
    }

    void tick() {
        Thread timer = new Thread(new TimerTask());
        timer.setName("sentinel-timer-task");
        timer.start();
    }

    private void passFor(T param) {
        passCountMap.get(param).incrementAndGet();
    }

    final class RunTask implements Runnable {
        @Override
        public void run() {
            while (!stop) {
                Entry entry = null;

                try {
                    T param = generateParam();
                    entry = SphU.entry(resourceName, EntryType.IN, 1, param);
                    // Add pass for parameter.
                    passFor(param);
                } catch (BlockException e1) {
                    // block.incrementAndGet();
                } catch (Exception e2) {
                    // biz exception
                } finally {
                    // total.incrementAndGet();
                    if (entry != null) {
                        entry.exit();
                    }
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(0, 10));
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
    }

    final class TimerTask implements Runnable {
        @Override
        public void run() {
            long start = System.currentTimeMillis();
            System.out.println("Begin to run! Go go go!");

            Map<T, Long> map = new HashMap<>(params.length);
            for (T param : params) {
                map.putIfAbsent(param, 0L);
            }
            while (!stop) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                }
                for (T param : params) {
                    long globalPass = passCountMap.get(param).get();
                    long oldPass = map.get(param);
                    long oneSecondPass = globalPass - oldPass;
                    map.put(param, globalPass);
                    System.out.println(String.format("[%d][%d] Hot param metrics for resource %s: "
                            + "pass count for param <%s> is %d",
                        seconds, TimeUtil.currentTimeMillis(), resourceName, param, oneSecondPass));
                }
                if (seconds-- <= 0) {
                    stop = true;
                }
            }

            long cost = System.currentTimeMillis() - start;
            System.out.println("Time cost: " + cost + " ms");
            System.exit(0);
        }
    }
}