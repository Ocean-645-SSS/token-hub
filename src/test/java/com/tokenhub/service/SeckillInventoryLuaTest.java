package com.tokenhub.service;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual Lua script with a minimal GET/SET Redis command adapter. */
class SeckillInventoryLuaTest {
    private final Map<String, String> data = new HashMap<>();

    private long synchronize(long stock, long version) throws Exception {
        Globals lua = JsePlatform.standardGlobals();
        LuaTable redis = new LuaTable();
        redis.set("call", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String command = args.arg(1).checkjstring(); String key = args.arg(2).checkjstring();
                if ("GET".equals(command)) return data.containsKey(key) ? LuaValue.valueOf(data.get(key)) : LuaValue.FALSE;
                if ("SET".equals(command)) { data.put(key, args.arg(3).checkjstring()); return LuaValue.valueOf("OK"); }
                throw new AssertionError("Unexpected Redis command: " + command);
            }
        });
        lua.set("redis", redis);
        lua.set("KEYS", LuaValue.listOf(new LuaValue[]{LuaValue.valueOf("stock"), LuaValue.valueOf("version")}));
        lua.set("ARGV", LuaValue.listOf(new LuaValue[]{LuaValue.valueOf(Long.toString(stock)), LuaValue.valueOf(Long.toString(version))}));
        try (var source = getClass().getResourceAsStream("/lua/sync_seckill_inventory.lua")) {
            assertNotNull(source);
            return lua.load(new String(source.readAllBytes(), StandardCharsets.UTF_8), "inventory-sync").call().checklong();
        }
    }

    @Test void duplicateSnapshotsCannotIncrementStock() throws Exception {
        synchronize(7, 3); synchronize(7, 3); synchronize(7, 3);
        assertEquals("7", data.get("stock"));
    }
    @Test void olderSnapshotCannotOverwriteNewerInventory() throws Exception {
        synchronize(6, 4); assertEquals(0, synchronize(7, 3));
        assertEquals("6", data.get("stock")); assertEquals("4", data.get("version"));
    }
    @Test void redisLossRebuildsAbsoluteBalanceInsteadOfReplayingDelta() throws Exception {
        synchronize(6, 4); data.clear(); synchronize(6, 4); synchronize(6, 4);
        assertEquals("6", data.get("stock")); assertEquals("4", data.get("version"));
    }
    @Test void equalVersionRepairsOrphanedAdmissionEstimate() throws Exception {
        synchronize(6, 4); data.put("stock", "5"); synchronize(6, 4);
        assertEquals("6", data.get("stock"));
    }
}
