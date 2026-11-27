package com.kigtts.tools;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentLongObjectMap;

/** Boxed Android implementation of the compiler's internal concurrent long-keyed map. */
public final class CompilerLongMap<V> implements ConcurrentLongObjectMap<V> {
    private final ConcurrentHashMap<Long, V> delegate = new ConcurrentHashMap<>();

    public V put(long key, V value) { return delegate.put(key, value); }
    public V get(long key) { return delegate.get(key); }
    public V remove(long key) { return delegate.remove(key); }
    public V putIfAbsent(long key, V value) { return delegate.putIfAbsent(key, value); }

    public Iterable<LongEntry<V>> entries() {
        return () -> new Iterator<LongEntry<V>>() {
            private final Iterator<Map.Entry<Long, V>> entries = delegate.entrySet().iterator();

            public boolean hasNext() { return entries.hasNext(); }
            public LongEntry<V> next() {
                Map.Entry<Long, V> entry = entries.next();
                return new LongEntry<V>() {
                    public long getKey() { return entry.getKey(); }
                    public V getValue() { return entry.getValue(); }
                };
            }
            public void remove() { entries.remove(); }
        };
    }
}
