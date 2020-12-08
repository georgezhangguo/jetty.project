//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <p>A Ternary Trie String lookup data structure.</p>
 * <p>
 * This Trie is of a fixed size and cannot grow (which can be a good thing with regards to DOS when used as a cache).
 * </p>
 * <p>
 * The Trie is stored in 3 arrays:
 * </p>
 * <dl>
 * <dt>char[] _tree</dt><dd>This is semantically 2 dimensional array flattened into a 1 dimensional char array. The second dimension
 * is that every 4 sequential elements represents a row of: character; hi index; eq index; low index, used to build a
 * ternary trie of key strings.</dd>
 * <dt>String[] _key</dt><dd>An array of key values where each element matches a row in the _tree array. A non zero key element
 * indicates that the _tree row is a complete key rather than an intermediate character of a longer key.</dd>
 * <dt>V[] _value</dt><dd>An array of values corresponding to the _key array</dd>
 * </dl>
 * <p>The lookup of a value will iterate through the _tree array matching characters. If the equal tree branch is followed,
 * then the _key array is looked up to see if this is a complete match.  If a match is found then the _value array is looked up
 * to return the matching value.
 * </p>
 * <p>
 * This Trie may be instantiated either as case sensitive or insensitive.
 * </p>
 * <p>This Trie is not Threadsafe and contains no mutual exclusion
 * or deliberate memory barriers.  It is intended for an ArrayTrie to be
 * built by a single thread and then used concurrently by multiple threads
 * and not mutated during that access.  If concurrent mutations of the
 * Trie is required external locks need to be applied.
 * </p>
 *
 * @param <V> the Entry type
 */
class TernaryTrie<V> extends AbstractTrie<V>
{
    private static final int LO = 1;
    private static final int EQ = 2;
    private static final int HI = 3;

    /**
     * The Size of a Trie row is the char, and the low, equal and high
     * child pointers
     */
    private static final int ROW_SIZE = 4;

    /**
     * The maximum capacity of the implementation. Over that,
     * the 16 bit indexes can overflow and the trie
     * cannot find existing entries anymore.
     */
    private static final int MAX_CAPACITY = Character.MAX_VALUE;

    /**
     * The Trie rows in a single array which allows a lookup of row,character
     * to the next row in the Trie.  This is actually a 2 dimensional
     * array that has been flattened to achieve locality of reference.
     */
    private final char[] _tree;

    private static class Node<V>
    {
        String _key;
        V _value;

        public Node(String s, V v)
        {
            _key = s;
            _value = v;
        }

        @Override
        public String toString()
        {
            return _key + "=" + _value;
        }
    }

    private final Node[] _nodes;

    /**
     * The number of rows allocated
     */
    private char _rows;

    public static <V> AbstractTrie<V> from(int capacity, int maxCapacity, boolean caseSensitive, Set<Character> alphabet, Map<String, V> contents)
    {
        if (capacity > MAX_CAPACITY || maxCapacity > MAX_CAPACITY)
            return null;

        AbstractTrie<V> trie = maxCapacity < 0
            ? new TernaryTrie.Growing<V>(!caseSensitive, capacity, 512)
            : new TernaryTrie<V>(!caseSensitive, Math.max(capacity, maxCapacity));

        if (contents != null && !trie.putAll(contents))
            return null;
        return trie;
    }

    /**
     * Create a Trie
     *
     * @param insensitive true if the Trie is insensitive to the case of the key.
     * @param capacity The capacity of the Trie, which is in the worst case
     * is the total number of characters of all keys stored in the Trie.
     * The capacity needed is dependent of the shared prefixes of the keys.
     * For example, a capacity of 6 nodes is required to store keys "foo"
     * and "bar", but a capacity of only 4 is required to
     * store "bar" and "bat".
     */
    @SuppressWarnings("unchecked")
    TernaryTrie(boolean insensitive, int capacity)
    {
        super(insensitive);
        if (capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("ArrayTernaryTrie maximum capacity overflow (" + capacity + " > " + MAX_CAPACITY + ")");
        _tree = new char[capacity * ROW_SIZE];
        _nodes = new Node[capacity];
    }

    @SuppressWarnings("unchecked")
    TernaryTrie(boolean insensitive, Map<String, V> initialValues)
    {
        super(insensitive);
        // The calculated requiredCapacity does not take into account the
        // extra reserved slot for the empty string key, nor the slots
        // required for 'terminating' the entry (1 slot per key) so we
        // have to add those.
        Set<String> keys = initialValues.keySet();
        int capacity = AbstractTrie.requiredCapacity(keys, !insensitive, null) + keys.size() + 1;
        if (capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("ArrayTernaryTrie maximum capacity overflow (" + capacity + " > " + MAX_CAPACITY + ")");
        _tree = new char[capacity * ROW_SIZE];
        _nodes = new Node[capacity];
        for (Map.Entry<String, V> entry : initialValues.entrySet())
        {
            if (!put(entry.getKey(), entry.getValue()))
                throw new AssertionError("Invalid capacity calculated (" + capacity + ") at '" + entry + "' for " + initialValues);
        }
    }

    @Override
    public void clear()
    {
        _rows = 0;
        Arrays.fill(_nodes, null);
        Arrays.fill(_tree, (char)0);
    }

    @Override
    public boolean put(String s, V v)
    {
        if (v == null)
            throw new IllegalArgumentException("Value cannot be null");

        int row = 0;
        int limit = s.length();
        int last = 0;
        for (int k = 0; k < limit; k++)
        {
            char c = s.charAt(k);
            if (isCaseInsensitive() && c < 128)
                c = StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;

                // Do we need to create the new row?
                if (row == _rows)
                {
                    _rows++;
                    if (_rows >= _nodes.length)
                    {
                        _rows--;
                        return false;
                    }
                    _tree[idx] = c;
                }

                char n = _tree[idx];
                int diff = n - c;
                if (diff == 0)
                    row = _tree[last = (idx + EQ)];
                else if (diff < 0)
                    row = _tree[last = (idx + LO)];
                else
                    row = _tree[last = (idx + HI)];

                // do we need a new row?
                if (row == 0)
                {
                    row = _rows;
                    _tree[last] = (char)row;
                }

                if (diff == 0)
                    break;
            }
        }

        // Do we need to create the new row?
        if (row == _rows)
        {
            _rows++;
            if (_rows >= _nodes.length)
            {
                _rows--;
                return false;
            }
        }

        // Put the key and value
        _nodes[row] = new Node<V>(s, v);

        return true;
    }

    @Override
    public V get(String s, int offset, int len)
    {
        int row = 0;
        for (int i = 0; i < len; )
        {
            char c = s.charAt(offset + i++);
            if (isCaseInsensitive() && c < 128)
                c = StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;
                char n = _tree[idx];
                int diff = n - c;

                if (diff == 0)
                {
                    row = _tree[idx + EQ];
                    if (row == 0)
                        return null;
                    break;
                }

                row = _tree[idx + hilo(diff)];
                if (row == 0)
                    return null;
            }
        }

        Node<V> node = _nodes[row];
        return node == null ? null : node._value;
    }

    @Override
    public V get(ByteBuffer b, int offset, int len)
    {
        int row = 0;
        offset += b.position();

        for (int i = 0; i < len; )
        {
            byte c = (byte)(b.get(offset + i++) & 0x7f);
            if (isCaseInsensitive())
                c = (byte)StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;
                char n = _tree[idx];
                int diff = n - c;

                if (diff == 0)
                {
                    row = _tree[idx + EQ];
                    if (row == 0)
                        return null;
                    break;
                }

                row = _tree[idx + hilo(diff)];
                if (row == 0)
                    return null;
            }
        }

        Node<V> node = _nodes[row];
        return node == null ? null : node._value;
    }

    @Override
    public V getBest(String s)
    {
        return getBest(0, s, 0, s.length());
    }

    @Override
    public V getBest(String s, int offset, int length)
    {
        return getBest(0, s, offset, length);
    }

    private V getBest(int row, String s, int offset, int len)
    {
        int cursor = row;
        int end = offset + len;
        loop:
        while (offset < end)
        {
            char c = s.charAt(offset++);
            len--;
            if (isCaseInsensitive() && c < 128)
                c = StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;
                char n = _tree[idx];
                int diff = n - c;

                if (diff == 0)
                {
                    row = _tree[idx + EQ];
                    if (row == 0)
                        break loop;

                    // if this node is a match, recurse to remember

                    Node<V> node = _nodes[row];
                    if (node != null && node._key != null)
                    {
                        cursor = row;
                        V better = getBest(row, s, offset, len);
                        if (better != null)
                            return better;
                    }
                    break;
                }

                row = _tree[idx + hilo(diff)];
                if (row == 0)
                    break loop;
            }
        }
        return (V)_value[cursor];
    }

    @Override
    public V getBest(ByteBuffer b, int offset, int len)
    {
        if (b.hasArray())
            return getBest(0, b.array(), b.arrayOffset() + b.position() + offset, len);
        return getBest(0, b, offset, len);
    }

    @Override
    public V getBest(byte[] b, int offset, int len)
    {
        return getBest(0, b, offset, len);
    }

    private V getBest(int t, byte[] b, int offset, int len)
    {
        int node = t;
        int end = offset + len;
        loop:
        while (offset < end)
        {
            byte c = (byte)(b[offset++] & 0x7f);
            len--;
            if (isCaseInsensitive())
                c = (byte)StringUtil.lowercases[c];

            while (true)
            {
                int row = ROW_SIZE * t;
                char n = _tree[row];
                int diff = n - c;

                if (diff == 0)
                {
                    t = _tree[row + EQ];
                    if (t == 0)
                        break loop;

                    // if this node is a match, recurse to remember 
                    if (_key[t] != null)
                    {
                        node = t;
                        V better = getBest(t, b, offset, len);
                        if (better != null)
                            return better;
                    }
                    break;
                }

                t = _tree[row + hilo(diff)];
                if (t == 0)
                    break loop;
            }
        }
        return (V)_value[node];
    }

    private V getBest(int t, ByteBuffer b, int offset, int len)
    {
        int node = t;
        int o = offset + b.position();

        loop:
        for (int i = 0; i < len; i++)
        {
            byte c = (byte)(b.get(o + i) & 0x7f);
            if (isCaseInsensitive())
                c = (byte)StringUtil.lowercases[c];

            while (true)
            {
                int row = ROW_SIZE * t;
                char n = _tree[row];
                int diff = n - c;

                if (diff == 0)
                {
                    t = _tree[row + EQ];
                    if (t == 0)
                        break loop;

                    // if this node is a match, recurse to remember 
                    if (_key[t] != null)
                    {
                        node = t;
                        V best = getBest(t, b, offset + i + 1, len - i - 1);
                        if (best != null)
                            return best;
                    }
                    break;
                }

                t = _tree[row + hilo(diff)];
                if (t == 0)
                    break loop;
            }
        }
        return (V)_value[node];
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();
        buf.append("ATT@").append(Integer.toHexString(hashCode())).append('{');
        buf.append("ci=").append(isCaseInsensitive()).append(';');
        buf.append("c=").append(_tree.length / ROW_SIZE).append(';');
        for (int r = 0; r <= _rows; r++)
        {
            if (_key[r] != null && _value[r] != null)
            {
                if (r != 0)
                    buf.append(',');
                buf.append(_key[r]);
                buf.append('=');
                buf.append(String.valueOf(_value[r]));
            }
        }
        buf.append('}');
        return buf.toString();
    }

    @Override
    public Set<String> keySet()
    {
        Set<String> keys = new HashSet<>();

        for (int r = 0; r <= _rows; r++)
        {
            if (_key[r] != null && _value[r] != null)
                keys.add(_key[r]);
        }
        return keys;
    }

    public int size()
    {
        int s = 0;
        for (int r = 0; r <= _rows; r++)
        {
            if (_key[r] != null && _value[r] != null)
                s++;
        }
        return s;
    }

    public boolean isEmpty()
    {
        for (int r = 0; r <= _rows; r++)
        {
            if (_key[r] != null && _value[r] != null)
                return false;
        }
        return true;
    }

    public Set<Map.Entry<String, V>> entrySet()
    {
        Set<Map.Entry<String, V>> entries = new HashSet<>();
        for (int r = 0; r <= _rows; r++)
        {
            if (_key[r] != null && _value[r] != null)
                entries.add(new AbstractMap.SimpleEntry<>(_key[r], _value[r]));
        }
        return entries;
    }

    public static int hilo(int diff)
    {
        // branchless equivalent to return ((diff<0)?LO:HI);
        // return 3+2*((diff&Integer.MIN_VALUE)>>Integer.SIZE-1);
        return 1 + (diff | Integer.MAX_VALUE) / (Integer.MAX_VALUE / 2);
    }

    public void dump()
    {
        for (int r = 0; r < _rows; r++)
        {
            char c = _tree[r * ROW_SIZE + 0];
            System.err.printf("%4d [%s,%d,%d,%d] '%s':%s%n",
                r,
                (c < ' ' || c > 127) ? ("" + (int)c) : "'" + c + "'",
                (int)_tree[r * ROW_SIZE + LO],
                (int)_tree[r * ROW_SIZE + EQ],
                (int)_tree[r * ROW_SIZE + HI],
                _key[r],
                _value[r]);
        }
    }

    static class Growing<V> extends AbstractTrie<V>
    {
        private final int _growby;
        private TernaryTrie<V> _trie;

        Growing(boolean insensitive, int capacity, int growby)
        {
            super(insensitive);
            _growby = growby;
            _trie = new TernaryTrie<>(insensitive, capacity);
        }

        @Override
        public int hashCode()
        {
            return _trie.hashCode();
        }

        @Override
        public V remove(String s)
        {
            return _trie.remove(s);
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Growing<?> growing = (Growing<?>)o;
            return Objects.equals(_trie, growing._trie);
        }

        @Override
        public void clear()
        {
            _trie.clear();
        }

        @Override
        public boolean put(V v)
        {
            return put(v.toString(), v);
        }

        @Override
        public boolean put(String s, V v)
        {
            boolean added = _trie.put(s, v);
            while (!added && _growby > 0)
            {
                TernaryTrie<V> bigger = new TernaryTrie<>(_trie.isCaseInsensitive(), _trie._nodes.length + _growby);
                for (Map.Entry<String, V> entry : _trie.entrySet())
                {
                    bigger.put(entry.getKey(), entry.getValue());
                }
                _trie = bigger;
                added = _trie.put(s, v);
            }

            return added;
        }

        @Override
        public V get(String s)
        {
            return _trie.get(s);
        }

        @Override
        public V get(ByteBuffer b)
        {
            return _trie.get(b);
        }

        @Override
        public V get(String s, int offset, int len)
        {
            return _trie.get(s, offset, len);
        }

        @Override
        public V get(ByteBuffer b, int offset, int len)
        {
            return _trie.get(b, offset, len);
        }

        @Override
        public V getBest(byte[] b, int offset, int len)
        {
            return _trie.getBest(b, offset, len);
        }

        @Override
        public V getBest(String s)
        {
            return _trie.getBest(s);
        }

        @Override
        public V getBest(String s, int offset, int length)
        {
            return _trie.getBest(s, offset, length);
        }

        @Override
        public V getBest(ByteBuffer b, int offset, int len)
        {
            return _trie.getBest(b, offset, len);
        }

        @Override
        public String toString()
        {
            return "G" + _trie.toString();
        }

        @Override
        public Set<String> keySet()
        {
            return _trie.keySet();
        }

        public void dump()
        {
            _trie.dump();
        }

        public boolean isEmpty()
        {
            return _trie.isEmpty();
        }

        public int size()
        {
            return _trie.size();
        }
    }
}