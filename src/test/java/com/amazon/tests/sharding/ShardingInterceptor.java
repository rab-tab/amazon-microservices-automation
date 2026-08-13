package com.amazon.tests.sharding;


import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters the full test method list down to just this shard's slice.
 *
 * Activated automatically via ServiceLoader (see
 * META-INF/services/org.testng.ITestNGListener) — no changes needed to
 * any individual suite XML file, so this applies uniformly whichever
 * suite is invoked (regression.xml, testng-e2e.xml, etc).
 *
 * Reads two env vars:
 *   JOB_COMPLETION_INDEX — set automatically by Kubernetes when a Job
 *                           has completionMode: Indexed. This is the
 *                           0-based index of THIS pod (0, 1, 2, ...).
 *   TOTAL_SHARDS          — set explicitly in the Job manifest, since
 *                           Kubernetes has no built-in way to tell a
 *                           pod how many total shards exist, only its
 *                           own index.
 *
 * When neither env var is present (e.g. running locally, or via the
 * old non-sharded Jenkinsfile path), every test runs — sharding is
 * opt-in, not a silent behavior change for existing usage.
 */
public class ShardingInterceptor implements IMethodInterceptor {

    @Override
    public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
        String shardIndexEnv = System.getenv("JOB_COMPLETION_INDEX");
        String totalShardsEnv = System.getenv("TOTAL_SHARDS");

        // Sharding not configured — run everything unchanged. This keeps
        // local `mvn test` runs and the pre-sharding Jenkinsfile path
        // working exactly as before.
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

        List<IMethodInstance> assigned = new ArrayList<>();
        for (IMethodInstance instance : methods) {
            String className = instance.getMethod().getTestClass().getName();
            String methodName = instance.getMethod().getConstructorOrMethod().getName();
            String key = className + "#" + methodName;

            // Math.floorMod (not %) — % can return negative results for
            // negative hashCodes, which would break the modulo bucketing.
            int bucket = Math.floorMod(key.hashCode(), totalShards);
            if (bucket == shardIndex) {
                assigned.add(instance);
            }
        }

        System.out.printf(
                "[ShardingInterceptor] Shard %d/%d: running %d of %d total methods%n",
                shardIndex, totalShards, assigned.size(), methods.size()
        );

        return assigned;
    }
}
