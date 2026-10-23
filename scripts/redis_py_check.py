"""Checks a running EmberDB server with the official redis-py client, over RESP2 and RESP3.

    pip install redis
    python scripts/redis_py_check.py [port]
"""
import sys

import redis


def check(port: int, protocol: int) -> None:
    r = redis.Redis(port=port, decode_responses=True, protocol=protocol)
    r.flushall()
    assert r.ping()

    r.set("py:k", "v", ex=30)
    assert r.get("py:k") == "v" and 0 < r.ttl("py:k") <= 30

    r.hset("py:h", mapping={"a": 1, "b": 2})
    assert r.hgetall("py:h") == {"a": "1", "b": "2"}

    r.zadd("py:z", {"x": 1, "y": 2.5})
    assert [tuple(p) for p in r.zrange("py:z", 0, -1, withscores=True)] == [("x", 1.0), ("y", 2.5)]
    assert r.zscore("py:z", "y") == 2.5

    r.sadd("py:s", "a", "b")
    assert r.smembers("py:s") == {"a", "b"}

    pipe = r.pipeline(transaction=True)
    pipe.incr("py:n")
    pipe.incr("py:n")
    assert pipe.execute() == [1, 2]

    # Optimistic locking, as the redis-py docs show it.
    with r.pipeline() as p:
        p.watch("py:n")
        current = int(p.get("py:n"))
        p.multi()
        p.set("py:n", current * 10)
        assert p.execute() == [True]
    assert r.get("py:n") == "20"

    ps = r.pubsub()
    ps.subscribe("py:chan")
    ps.get_message(timeout=1)  # the subscribe confirmation
    r.publish("py:chan", "hello")
    message = ps.get_message(timeout=2)
    assert message and message["data"] == "hello", message
    ps.close()

    assert r.info()["redis_version"] == "7.2.0"
    print(f"redis-py {redis.__version__} over RESP{protocol}: all checks passed")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 6379
    for protocol in (2, 3):
        check(port, protocol)
