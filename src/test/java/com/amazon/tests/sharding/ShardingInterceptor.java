package com.amazon.tests.sharding;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters the full test method list down to just this shard's slice.
 * ... (existing Javadoc unchanged)
 */
@Slf4j
public class ShardingInterceptor implements IMethodInterceptor {

    @Override
    public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
        String shardIndexEnv = System.getenv("JOB_COMPLETION_INDEX");
        String totalShardsEnv = System.getenv("TOTAL_SHARDS");

        // Sharding not configured — run everything unchanged.
        if (shardIndexEnv == null || totalShardsEnv == null) {
            return methods;
        }

        int shardIndex = Integer.parseInt(shardIndexEnv.trim());
        int totalShards = Integer.parseInt(totalShardsEnv.trim());

        if (totalShards <= 0) {
            throw new IllegalArgumentException("TOTAL_SHARDS must be > 0, got: " + totalShards);
        }
        if (shardIndex < 0 || shardIndex >= totalShards) {
            throw new IllegalArgumentException(
                    "JOB_COMPLETION_INDEX (" + shardIndex + ") out of range for TOTAL_SHARDS (" + totalShards + ")"
            );
        }

        // Set once, here, at the earliest possible point — intercept() runs
        // before any @BeforeSuite/@BeforeMethod, so this is the actual
        // earliest point shard/pod identity is knowable. Every subsequent
        // log line for this JVM's whole lifetime (via %X{shard}/%X{pod} in
        // the Logback pattern) will carry it automatically from here on,
        // the same way BaseTest.createTest() seeds %X{test} per-test.
        MDC.put("shard", String.valueOf(shardIndex));
        MDC.put("pod", System.getenv().getOrDefault("HOSTNAME", "unknown-pod"));

        List<IMethodInstance> assigned = new ArrayList<>();
        for (IMethodInstance instance : methods) {
            String className = instance.getMethod().getTestClass().getName();
            int bucket = Math.floorMod(className.hashCode(), totalShards);
            if (bucket == shardIndex) {
                assigned.add(instance);
            }
        }

        log.info("[SHARD] {}/{}: running {} of {} total methods",
                shardIndex, totalShards, assigned.size(), methods.size());

        return assigned;
    }
}