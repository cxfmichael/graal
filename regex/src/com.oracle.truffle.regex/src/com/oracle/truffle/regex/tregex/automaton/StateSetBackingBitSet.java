/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.regex.tregex.automaton;

import com.oracle.truffle.regex.util.CompilationFinalBitSet;

import java.util.PrimitiveIterator;

public class StateSetBackingBitSet implements StateSetBackingSet {

    private CompilationFinalBitSet bitSet;

    public StateSetBackingBitSet() {
    }

    private StateSetBackingBitSet(StateSetBackingBitSet copy) {
        bitSet = copy.bitSet == null ? null : copy.bitSet.copy();
    }

    @Override
    public StateSetBackingSet copy() {
        return new StateSetBackingBitSet(this);
    }

    @Override
    public void create(int stateIndexSize) {
        assert bitSet == null;
        bitSet = new CompilationFinalBitSet(stateIndexSize);
    }

    @Override
    public boolean isActive() {
        return bitSet != null;
    }

    @Override
    public boolean contains(short id) {
        return bitSet.get(id);
    }

    @Override
    public boolean add(short id) {
        if (bitSet.get(id)) {
            return false;
        }
        bitSet.set(id);
        return true;
    }

    @Override
    public void addBatch(short id) {
        bitSet.set(id);
    }

    @Override
    public void addBatchFinish() {
    }

    @Override
    public void replace(short oldId, short newId) {
        bitSet.clear(oldId);
        bitSet.set(newId);
    }

    @Override
    public boolean remove(short id) {
        if (bitSet.get(id)) {
            bitSet.clear(id);
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        bitSet.clear();
    }

    @Override
    public boolean isDisjoint(StateSetBackingSet other) {
        return bitSet.isDisjoint(((StateSetBackingBitSet) other).bitSet);
    }

    @Override
    public PrimitiveIterator.OfInt iterator() {
        return bitSet.iterator();
    }

    @Override
    public int hashCode() {
        return bitSet == null ? 0 : bitSet.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof StateSetBackingBitSet && bitSet.equals(((StateSetBackingBitSet) obj).bitSet);
    }
}
