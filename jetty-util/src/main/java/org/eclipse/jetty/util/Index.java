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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * An immutable String lookup data structure.
 * @param <V> the entry type
 */
public interface Index<V>
{
    Set<Character> VISIBLE_ASCII_ALPHABET = Collections.unmodifiableSet(IntStream.range(0x20, 0x7f)
        .mapToObj(i -> (char)i)
        .collect(Collectors.toSet()));

    /**
     * Get an exact match from a String key
     *
     * @param s The key
     * @return the value for the string key
     */
    V get(String s);

    /**
     * Get an exact match from a segment of a ByteBuufer as key
     *
     * @param b The buffer
     * @return The value or null if not found
     */
    V get(ByteBuffer b);

    /**
     * Get an exact match from a String key
     *
     * @param s The key
     * @param offset The offset within the string of the key
     * @param len the length of the key
     * @return the value for the string / offset / length
     */
    V get(String s, int offset, int len);

    /**
     * Get an exact match from a segment of a ByteBuufer as key
     *
     * @param b The buffer
     * @param offset The offset within the buffer of the key
     * @param len the length of the key
     * @return The value or null if not found
     */
    V get(ByteBuffer b, int offset, int len);

    /**
     * Get the best match from key in a String.
     *
     * @param s The string
     * @param offset The offset within the string of the key
     * @param len the length of the key
     * @return The value or null if not found
     */
    V getBest(String s, int offset, int len);

    /**
     * Get the best match from key in a String.
     *
     * @param s The string
     * @return The value or null if not found
     */
    V getBest(String s);

    /**
     * Get the best match from key in a byte buffer.
     * The key is assumed to by ISO_8859_1 characters.
     *
     * @param b The buffer
     * @param offset The offset within the buffer of the key
     * @param len the length of the key
     * @return The value or null if not found
     */
    V getBest(ByteBuffer b, int offset, int len);

    /**
     * Get the best match from key in a byte buffer.
     * The key is assumed to by ISO_8859_1 characters.
     *
     * @param b The buffer
     * @return The value or null if not found
     */
    default V getBest(ByteBuffer b)
    {
        return getBest(b, 0, b.remaining());
    }

    /**
     * Get the best match from key in a byte array.
     * The key is assumed to by ISO_8859_1 characters.
     *
     * @param b The buffer
     * @param offset The offset within the array of the key
     * @param len the length of the key
     * @return The value or null if not found
     */
    V getBest(byte[] b, int offset, int len);

    /**
     * Get the best match from key in a byte array.
     * The key is assumed to by ISO_8859_1 characters.
     *
     * @param b The buffer
     * @return The value or null if not found
     */
    default V getBest(byte[] b)
    {
        return getBest(b, 0, b.length);
    }

    /**
     * Check if the index contains any entry.
     *
     * @return true if the index does not contain any entry.
     */
    boolean isEmpty();

    /**
     * Get the number of entries in the index.
     *
     * @return the index' entries count.
     */
    int size();

    /**
     * Get a {@link Set} of the keys contained in this index.
     *
     * @return a {@link Set} of the keys contained in this index.
     */
    Set<String> keySet();

    /**
     * A mutable String lookup data structure.
     * Implementations are not thread-safe.
     * @param <V> the entry type
     */
    interface Mutable<V> extends Index<V>
    {
        /**
         * Put an entry into the index.
         *
         * @param s The key for the entry. Must be non null, but can be empty.
         * @param v The value of the entry. Must be non null.
         * @return True if the index had capacity to add the field.
         */
        boolean put(String s, V v);

        /**
         * Put a value as both a key and a value.
         *
         * @param v The value and key
         * @return True if the Trie had capacity to add the field.
         */
        boolean put(V v);

        /**
         * Remove an entry from the index.
         *
         * @param s The key for the entry
         * @return The removed value of the entry
         */
        V remove(String s);

        /**
         * Remove all entries from the index.
         */
        void clear();

        /**
         * Builder of {@link Index.Mutable} instances. Such builder cannot be
         * directly created, it is instead returned by calling {@link Index.Builder#mutable()}.
         * @param <V> the entry type
         */
        class Builder<V> extends Index.Builder<V>
        {
            private int maxCapacity = -1;
            private Set<Character> alphabet;

            Builder(boolean caseSensitive, Map<String, V> contents)
            {
                super(caseSensitive, contents);
            }

            /**
             * Configure a maximum capacity for the mutable index.
             * A negative value means there is no capacity limit and
             * the index can grow without limits.
             * The default value is -1.
             * @param capacity the maximum capacity of the index.
             * @return this
             */
            public Builder<V> maxCapacity(int capacity)
            {
                this.maxCapacity = capacity;
                return this;
            }

            public Builder<V> alphabet(String alphabetString)
            {
                alphabet = new HashSet<>();
                alphabetString.chars().forEach(c -> alphabet.add((char)c));
                return this;
            }

            public Builder<V> alphabet(Set<Character> alphabet)
            {
                this.alphabet = alphabet;
                return this;
            }

            /**
             * Build a {@link Mutable} instance.
             * @return a {@link Mutable} instance.
             */
            public Mutable<V> build()
            {
                if (maxCapacity == 0)
                    return EmptyTrie.instance(caseSensitive);

                if (alphabet == null)
                {
                    if (contents != null)
                        throw new IllegalStateException("Cannot create a mutable index without alphabet or some contents");
                    alphabet = new HashSet<>();
                }

                // Work out needed capacity and alphabet
                int capacity = (contents == null) ? 0 : AbstractTrie.requiredCapacity(contents.keySet(), caseSensitive, alphabet);

                // check capacities
                if (maxCapacity >= 0 && capacity > maxCapacity)
                    throw new IllegalStateException("Insufficient maxCapacity for contents");

                // try all the tries
                AbstractTrie<V> trie = ArrayTrie.from(capacity, maxCapacity, caseSensitive, alphabet, contents);
                if (trie != null)
                    return trie;
                trie = ArrayTernaryTrie.from(capacity, maxCapacity, caseSensitive, alphabet, contents);
                if (trie != null)
                    return trie;
                trie = TreeTrie.from(capacity, maxCapacity, caseSensitive, alphabet, contents);
                if (trie != null)
                    return trie;

                // Nothing suitable
                throw new IllegalStateException("No suitable Trie implementation: " + this);
            }
        }
    }

    /**
     * Builder of {@link Index} instances.
     * @param <V> the entry type
     */
    class Builder<V>
    {
        Map<String, V> contents;
        boolean caseSensitive;

        /**
         * Create a new index builder instance.
         */
        public Builder()
        {
            this.caseSensitive = false;
            this.contents = null;
        }

        Builder(boolean caseSensitive, Map<String, V> contents)
        {
            this.caseSensitive = caseSensitive;
            this.contents = contents;
        }

        private Map<String, V> contents()
        {
            if (contents == null)
                contents = new LinkedHashMap<>();
            return contents;
        }

        /**
         * Configure the index to be either case-sensitive or not.
         * Default value is false.
         *
         * @param caseSensitive true if the index has to be case-sensitive
         * @return this
         */
        public Builder<V> caseSensitive(boolean caseSensitive)
        {
            this.caseSensitive = caseSensitive;
            return this;
        }

        /**
         * Configure some pre-existing entries.
         *
         * @param values an array of values
         * @param keyFunction a {@link Function} that generates the key of each
         * entry of the values array
         * @return this
         */
        public Builder<V> withAll(V[] values, Function<V, String> keyFunction)
        {
            for (V value : values)
            {
                String key = keyFunction.apply(value);
                contents().put(key, value);
            }
            return this;
        }

        /**
         * Configure some pre-existing entries.
         *
         * @param entriesSupplier a {@link Map} {@link Supplier} of entries
         * @return this
         */
        public Builder<V> withAll(Supplier<Map<String, V>> entriesSupplier)
        {
            Map<String, V> map = entriesSupplier.get();
            contents().putAll(map);
            return this;
        }

        /**
         * Configure a pre-existing entry with a key
         * that is the {@link #toString()} representation
         * of the value.
         *
         * @param value The value
         * @return this
         */
        public Builder<V> with(V value)
        {
            contents().put(value.toString(), value);
            return this;
        }

        /**
         * Configure a pre-existing entry.
         *
         * @param key The key
         * @param value The value for the key string
         * @return this
         */
        public Builder<V> with(String key, V value)
        {
            contents().put(key, value);
            return this;
        }

        /**
         * Configure the index to be mutable.
         *
         * @return a {@link Mutable.Builder} configured like this builder.
         */
        public Mutable.Builder<V> mutable()
        {
            return new Mutable.Builder<>(caseSensitive, contents);
        }

        /**
         * Build a {@link Index} instance.
         *
         * @return a {@link Index} instance.
         */
        public Index<V> build()
        {
            if (contents == null)
                return EmptyTrie.instance(caseSensitive);

            Set<Character> alphabet = new HashSet<>();
            int capacity = AbstractTrie.requiredCapacity(contents.keySet(), caseSensitive, alphabet);

            AbstractTrie<V> trie = ArrayTrie.from(capacity, capacity, caseSensitive, alphabet, contents);
            if (trie != null)
                return trie;
            trie = ArrayTernaryTrie.from(capacity, capacity, caseSensitive, alphabet, contents);
            if (trie != null)
                return trie;
            trie = TreeTrie.from(capacity, capacity, caseSensitive, alphabet, contents);
            if (trie != null)
                return trie;

            throw new UnsupportedOperationException("No suitable Trie implementation : " + this);
        }

        @Override
        public String toString()
        {
            return String.format("%s{c=%d,cs=%b}", super.toString(), contents == null ? 0 : contents.size(), caseSensitive);
        }
    }
}