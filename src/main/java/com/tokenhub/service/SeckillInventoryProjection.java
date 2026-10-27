package com.tokenhub.service;

import com.tokenhub.mapper.SeckillInventoryMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class SeckillInventoryProjection {
    private final SeckillInventoryMapper inventory;
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static { SCRIPT.setLocation(new ClassPathResource("lua/sync_seckill_inventory.lua")); SCRIPT.setResultType(Long.class); }

    public SeckillInventoryProjection(SeckillInventoryMapper inventory, StringRedisTemplate redis) {
        this.inventory = inventory; this.redis = redis;
    }

    public void synchronize(long activityId) {
        inventory.initialize(activityId);
        var snapshot = inventory.find(activityId);
        if (snapshot == null) throw new IllegalStateException("missing activity inventory");
        Long result = redis.execute(SCRIPT, List.of("seckill:stock:" + activityId, "seckill:stock:version:" + activityId),
                Long.toString(snapshot.availableStock()), Long.toString(snapshot.version()));
        if (result == null) throw new IllegalStateException("inventory projection returned no result");
    }
}
