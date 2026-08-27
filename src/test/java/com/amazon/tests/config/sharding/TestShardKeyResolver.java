package com.amazon.tests.config.sharding;

public class TestShardKeyResolver {

    private final int shardCount;

    public TestShardKeyResolver(int shardCount) {
        this.shardCount = shardCount;
    }

    public int getShardCount() {
        return shardCount;
    }

    /**
     * Mirrors the production ShardingSphere INLINE algorithm expression:
     * shard$->{Math.abs(user_id.hashCode()) % 4}
     * Must be kept in sync if the prod algorithm-expression ever changes.
     */
    public int expectedShardFor(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be null/blank");
        }
        return Math.abs(userId.hashCode()) % shardCount;
    }

    public String generateUserIdForShard(int targetShard) {
        if (targetShard < 0 || targetShard >= shardCount) {
            throw new IllegalArgumentException("targetShard out of range: " + targetShard);
        }
        String candidate;
        int attempts = 0;
        do {
            candidate = java.util.UUID.randomUUID().toString();
            if (++attempts > 100_000) {
                throw new IllegalStateException("Could not find a userId for shard " + targetShard + " after 100k attempts");
            }
        } while (expectedShardFor(candidate) != targetShard);
        return candidate;
    }
}