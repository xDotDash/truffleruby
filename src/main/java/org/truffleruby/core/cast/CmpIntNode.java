/*
 * Copyright (c) 2013, 2018 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 *
 * Contains code modified from JRuby's RubyComparable.java
 *
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2005 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2006 Miguel Covarrubias <mlcovarrubias@gmail.com>
 * Copyright (C) 2006 Thomas E Enebo <enebo@acm.org>
 */

package org.truffleruby.core.cast;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.Layouts;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;

/**
 * This is a port of MRI's rb_cmpint, as taken from RubyComparable and broken out into specialized nodes.
 */

@NodeChildren({
    @NodeChild(value = "value"),
    @NodeChild(value = "receiver"),
    @NodeChild(value = "other")
})
public abstract class CmpIntNode extends RubyNode {

    public abstract int executeCmpInt(VirtualFrame frame, Object cmpResult, Object a, Object b);

    @Specialization
    public int cmpInt(int value, Object receiver, Object other) {
        if (value > 0) {
            return 1;
        }

        if (value < 0) {
            return -1;
        }

        return 0;
    }

    @Specialization
    public int cmpLong(long value, Object receiver, Object other) {
        if (value > 0) {
            return 1;
        }

        if (value < 0) {
            return -1;
        }

        return 0;
    }

    @Specialization(guards = "isRubyBignum(value)")
    public int cmpBignum(DynamicObject value, Object receiver, Object other) {
        return Layouts.BIGNUM.getValue(value).signum();
    }

    @Specialization(guards = "isNil(nil)")
    public int cmpNil(Object nil, Object receiver, Object other) {
        throw new RaiseException(getContext(), coreExceptions().argumentError(formatMessage(receiver, other), this));
    }

    @TruffleBoundary
    private String formatMessage(Object receiver, Object other) {
        return StringUtils.format("comparison of %s with %s failed",
                Layouts.MODULE.getFields(coreLibrary().getLogicalClass(receiver)).getName(),
                Layouts.MODULE.getFields(coreLibrary().getLogicalClass(other)).getName());
    }

    @Specialization(guards = {
            "!isInteger(value)",
            "!isLong(value)",
            "!isRubyBignum(value)",
            "!isNil(value)" })
    public int cmpObject(VirtualFrame frame, Object value, Object receiver, Object other,
            @Cached("createPrivate()") CallDispatchHeadNode gtNode,
            @Cached("createPrivate()") CallDispatchHeadNode ltNode,
            @Cached("create()") BooleanCastNode gtCastNode,
            @Cached("create()") BooleanCastNode ltCastNode) {

        if (gtCastNode.executeToBoolean(gtNode.call(value, ">", 0))) {
            return 1;
        }

        if (ltCastNode.executeToBoolean(ltNode.call(value, "<", 0))) {
            return -1;
        }

        return 0;
    }
}
