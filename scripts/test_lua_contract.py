"""Offline Lua state-transition tests using the existing system liblua5.1.

Run: python3 scripts/test_lua_contract.py (Linux/WSL with liblua5.1 installed).
Executes the actual project scripts with an in-memory Redis command double;
does not connect to Redis or prove persistence/Redis command-error atomicity.
"""
import ctypes
import ctypes.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
LIBRARY = ctypes.util.find_library("lua5.1")
if not LIBRARY:
    raise RuntimeError("Existing liblua5.1 is required; no packages are installed by this test")
LUA = ctypes.CDLL(LIBRARY)
LUA.luaL_newstate.restype = ctypes.c_void_p
LUA.luaL_openlibs.argtypes = [ctypes.c_void_p]
LUA.luaL_loadstring.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
LUA.lua_pcall.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int]
LUA.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.POINTER(ctypes.c_size_t)]
LUA.lua_tolstring.restype = ctypes.c_char_p
LUA.lua_close.argtypes = [ctypes.c_void_p]

HARNESS = """
values = {}; sets = {}; streams = {}; pending = {}; calls = {}; keyCalls = {}; serial = 0
redis = {}
function redis.call(cmd, key, ...)
    local args = {...}; calls[cmd] = (calls[cmd] or 0) + 1
    keyCalls[cmd .. ':' .. key] = (keyCalls[cmd .. ':' .. key] or 0) + 1
    if cmd == 'get' then return values[key] or false end
    if cmd == 'incrby' then
        values[key] = tostring((tonumber(values[key]) or 0) + tonumber(args[1])); return tonumber(values[key])
    end
    if cmd == 'sismember' then return sets[key] and sets[key][args[1]] and 1 or 0 end
    if cmd == 'sadd' then sets[key] = sets[key] or {}; sets[key][args[1]] = true; return 1 end
    if cmd == 'srem' then if sets[key] then sets[key][args[1]] = nil end; return 1 end
    if cmd == 'del' then values[key] = nil; return 1 end
    if cmd == 'xack' then
        if pending[args[2]] then pending[args[2]] = nil; return 1 end; return 0
    end
    if cmd == 'xadd' then
        serial = serial + 1; streams[key] = streams[key] or {}
        local record = {}; for i = 2, #args, 2 do record[args[i]] = args[i+1] end
        streams[key][tostring(serial)] = record; return tostring(serial)
    end
    if cmd == 'xdel' then
        if streams[key] and streams[key][args[1]] then streams[key][args[1]] = nil; return 1 end; return 0
    end
    if cmd == 'xrange' then
        if streams[key] and streams[key][args[1]] then return {{args[1], streams[key][args[1]]}} end
        return {}
    end
    error('unsupported command ' .. cmd)
end
function count(t) local n = 0; for _ in pairs(t or {}) do n = n + 1 end; return n end
function activity()
    values['seckill:active:1']='1'; values['seckill:begin:1']='100'; values['seckill:end:1']='200'
    values['seckill:stock:1']='5'; ARGV={'1','user','order','150'}; KEYS={}
end
function reservation()
    values.stock='0'; sets.users={user=true}; values.retry='4'; pending.message=true
    KEYS={'source','stock','users','dead','retry'}
    ARGV={'group','message','user','1','order','retry_exhausted'}
end
function replay(state)
    streams.dead={message={reservationState=state}}; KEYS={'dead','target','stock','users','recovery'}
    ARGV={'message','user','1','order',state}
end
"""


def execute(script, setup, checks):
    source = (ROOT / "src/main/resources" / script).read_text(encoding="utf-8")
    chunk = HARNESS + setup + "\nlocal script = function()\n" + source + "\nend\nlocal result = script()\n" + checks
    state = LUA.luaL_newstate()
    try:
        LUA.luaL_openlibs(state)
        code = LUA.luaL_loadstring(state, chunk.encode())
        if code == 0:
            code = LUA.lua_pcall(state, 0, 0, 0)
        if code:
            raise AssertionError(LUA.lua_tolstring(state, -1, None).decode())
    finally:
        LUA.lua_close(state)


class LuaContracts(unittest.TestCase):
    def test_activity_rejections_do_not_mutate_reservation_or_stream(self):
        cases = [
            ("ARGV[4]='99'", 5), ("ARGV[4]='201'", 6),
            ("values['seckill:active:1']='0'", 4),
            ("values['seckill:active:1']=nil", 3),
            ("values['seckill:begin:1']=nil", 3),
            ("values['seckill:end:1']=nil", 3),
            ("values['seckill:begin:1']='bad'", 3),
            ("values['seckill:stock:1']='0'", 1),
            ("sets['seckill:order:1']={user=true}", 2),
        ]
        for change, expected in cases:
            with self.subTest(change=change):
                execute("seckill.lua", "activity(); " + change +
                        "; oldStock=values['seckill:stock:1']; oldUsers=count(sets['seckill:order:1'])", f"""
                    assert(result == {expected})
                    assert(values['seckill:stock:1'] == oldStock)
                    assert(count(sets['seckill:order:1']) == oldUsers)
                    assert(count(streams['stream.orders']) == 0)
                    assert(not calls.incrby and not calls.sadd and not calls.xadd)
                """)

    def test_inclusive_time_boundaries_accept(self):
        for now in (100, 200):
            with self.subTest(now=now):
                execute("seckill.lua", f"activity(); ARGV[4]='{now}'", """
                    assert(result == 0); assert(values['seckill:stock:1']=='4')
                    assert(sets['seckill:order:1'].user); assert(count(streams['stream.orders'])==1)
                """)

    def test_stop_survives_inventory_increase(self):
        execute("seckill.lua", "activity(); values['seckill:active:1']='0'; redis.call('incrby','seckill:stock:1',100)", """
            assert(result==4); assert(values['seckill:stock:1']=='105')
            assert(not calls.sadd and not calls.xadd)
        """)

    def test_dead_letter_holds_reservation_even_if_db_commits_late(self):
        execute("dead-letter.lua", "reservation(); dbStock=1", """
            assert(result==1); assert(values.stock=='0'); assert(sets.users.user)
            assert(not calls.incrby and not calls.srem); assert(not pending.message)
            assert(values.retry==nil); assert(streams.dead['1'].reservationState=='HELD')
            -- Original DB transaction completes after quarantine: reservation already matches it.
            dbStock=dbStock-1; dbOrder=true
            assert(dbOrder and tonumber(values.stock)==dbStock and sets.users.user)
        """)

    def test_duplicate_dead_letter_is_noop(self):
        execute("dead-letter.lua", "reservation(); pending.message=nil", """
            assert(result==0); assert(values.stock=='0'); assert(sets.users.user)
            assert(not calls.xadd and not calls.incrby and not calls.srem)
        """)

    def test_held_replay_never_double_reserves(self):
        execute("replay-dead-letter.lua", "values.stock='0'; sets.users={user=true}; replay('HELD')", """
            assert(result==1); assert(values.stock=='0'); assert(sets.users.user)
            assert(not calls.incrby and not keyCalls['sadd:users'])
            assert(sets.recovery.order)
            assert(count(streams.dead)==0 and count(streams.target)==1)
        """)

    def test_legacy_replay_reserves(self):
        execute("replay-dead-letter.lua", "values.stock='1'; replay(nil)", """
            assert(result==1); assert(values.stock=='0'); assert(sets.users.user)
            assert(sets.recovery.order)
            assert(count(streams.dead)==0 and count(streams.target)==1)
        """)

    def test_legacy_rejection_preserves_dead_letter(self):
        for change, expected in [("values.stock='0'", -1), ("values.stock='1'; sets.users={user=true}", -2)]:
            with self.subTest(expected=expected):
                execute("replay-dead-letter.lua", "replay('LEGACY'); " + change, f"""
                    assert(result=={expected}); assert(count(streams.dead)==1)
                    assert(not calls.xdel and not calls.incrby and not calls.sadd and not calls.xadd)
                """)

    def test_deleted_held_entry_cannot_be_replayed_twice(self):
        execute("replay-dead-letter.lua", "values.stock='0'; sets.users={user=true}; replay('HELD'); streams.dead={}", """
            assert(result==0); assert(not calls.xadd and not calls.incrby and not calls.sadd)
        """)

    def test_failed_replay_quarantine_retains_recovery_marker(self):
        quarantine = (ROOT / "src/main/resources/dead-letter.lua").read_text(encoding="utf-8")
        setup = "local quarantine=function()\n" + quarantine + "\nend\n"
        setup += "values.stock='0'; sets.users={user=true}; replay('HELD'); dbStock=0"
        execute("replay-dead-letter.lua", setup, """
            assert(result==1 and values.stock=='0' and dbStock==0 and sets.recovery.order)
            -- Replay fails again: quarantine cannot clear its recovery marker.
            pending.message=true; KEYS={'source','stock','users','dead','retry'}
            ARGV={'group','message','user','1','order','retry_exhausted'}
            assert(quarantine()==1); assert(sets.recovery.order)
            assert(values.stock=='0' and sets.users.user and count(streams.dead)==1)
        """)

    def test_multiple_replays_track_each_order_until_commit(self):
        execute("replay-dead-letter.lua", "values.stock='0'; sets.users={user=true}; replay('HELD')", """
            assert(result==1 and sets.recovery.order)
            streams.dead.message={}; ARGV[4]='second-order'; assert(script()==1)
            assert(count(sets.recovery)==2)
            redis.call('srem','recovery','order') -- only the first commit is confirmed
            assert(count(sets.recovery)==1 and sets.recovery['second-order'])
        """)


if __name__ == "__main__":
    unittest.main(verbosity=2)
