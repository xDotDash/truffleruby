/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.binding;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.builtins.CallerFrameAccess;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodNode;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.array.ArrayHelpers;
import org.truffleruby.core.cast.NameToJavaStringNodeGen;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.string.StringNodes.MakeStringNode;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.ReadCallerFrameNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.locals.ReadFrameSlotNode;
import org.truffleruby.language.locals.ReadFrameSlotNodeGen;
import org.truffleruby.language.locals.WriteFrameSlotNode;
import org.truffleruby.language.locals.WriteFrameSlotNodeGen;
import org.truffleruby.language.objects.AllocateObjectNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CreateCast;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;

@CoreClass("Binding")
public abstract class BindingNodes {

    /** Creates a Binding without a SourceSection, only for Binding used internally and not exposed to the user. */
    public static DynamicObject createBinding(RubyContext context, MaterializedFrame frame) {
        return createBinding(context, frame, null);
    }

    public static DynamicObject createBinding(RubyContext context, MaterializedFrame frame, SourceSection sourceSection) {
        return Layouts.BINDING.createBinding(context.getCoreLibrary().getBindingFactory(), frame, sourceSection);
    }

    @TruffleBoundary
    public static FrameDescriptor newFrameDescriptor(RubyContext context) {
        return new FrameDescriptor(context.getCoreLibrary().getNil());
    }

    @TruffleBoundary
    public static FrameDescriptor newFrameDescriptor(RubyContext context, String name) {
        final FrameDescriptor frameDescriptor = new FrameDescriptor(context.getCoreLibrary().getNil());
        assert name != null && !name.isEmpty();
        frameDescriptor.addFrameSlot(name);
        return frameDescriptor;
    }

    public static FrameDescriptor getFrameDescriptor(DynamicObject binding) {
        return getFrame(binding).getFrameDescriptor();
    }

    public static MaterializedFrame getFrame(DynamicObject binding) {
        return Layouts.BINDING.getFrame(binding);
    }

    public static MaterializedFrame newFrame(DynamicObject binding, FrameDescriptor frameDescriptor) {
        final MaterializedFrame frame = getFrame(binding);
        final MaterializedFrame newFrame = newFrame(frame, frameDescriptor);
        Layouts.BINDING.setFrame(binding, newFrame);
        return newFrame;
    }

    public static MaterializedFrame newFrame(RubyContext context, MaterializedFrame parent) {
        final FrameDescriptor descriptor = newFrameDescriptor(context);
        return newFrame(parent, descriptor);
    }

    public static MaterializedFrame newFrame(MaterializedFrame parent, FrameDescriptor descriptor) {
        return Truffle.getRuntime().createVirtualFrame(
                RubyArguments.pack(
                        parent,
                        null,
                        RubyArguments.getMethod(parent),
                        RubyArguments.getDeclarationContext(parent),
                        null,
                        RubyArguments.getSelf(parent),
                        RubyArguments.getBlock(parent),
                        RubyArguments.getArguments(parent)),
                descriptor).materialize();
    }

    public static void insertAncestorFrame(RubyContext context, DynamicObject binding, MaterializedFrame ancestorFrame) {
        assert RubyGuards.isRubyBinding(binding);
        MaterializedFrame frame = Layouts.BINDING.getFrame(binding);
        while (RubyArguments.getDeclarationFrame(frame) != null) {
            frame = RubyArguments.getDeclarationFrame(frame);
        }
        RubyArguments.setDeclarationFrame(frame, ancestorFrame);

        // We need to invalidate caches depending on the top frame, so create a new empty frame
        newFrame(binding, newFrameDescriptor(context));
    }

    protected static class FrameSlotAndDepth {
        private final FrameSlot slot;
        private final int depth;

        public FrameSlotAndDepth(FrameSlot slot, int depth) {
            this.slot = slot;
            this.depth = depth;
        }

        public FrameSlot getSlot() {
            return slot;
        }
    }

    public static FrameSlotAndDepth findFrameSlotOrNull(String identifier, MaterializedFrame frame) {
        int depth = 0;
        while (frame != null) {
            final FrameSlot frameSlot = frame.getFrameDescriptor().findFrameSlot(identifier);
            if (frameSlot != null) {
                return new FrameSlotAndDepth(frameSlot, depth);
            }

            frame = RubyArguments.getDeclarationFrame(frame);
            depth++;
        }
        return null;
    }

    public static FrameSlotAndDepth findFrameSlot(String identifier, FrameDescriptor frameDescriptor) {
        final FrameSlot frameSlot = frameDescriptor.findFrameSlot(identifier);
        assert frameSlot != null;
        return new FrameSlotAndDepth(frameSlot, 0);
    }

    public static boolean hiddenVariable(String name) {
        return name.startsWith("$") || name.startsWith("rubytruffle_temp");
    }

    @CoreMethod(names = { "dup", "clone" })
    public abstract static class DupNode extends UnaryCoreMethodNode {

        @Child private AllocateObjectNode allocateObjectNode = AllocateObjectNode.create();

        @Specialization
        public DynamicObject dup(DynamicObject binding) {
            DynamicObject copy = allocateObjectNode.allocate(
                    Layouts.BASIC_OBJECT.getLogicalClass(binding),
                    Layouts.BINDING.getFrame(binding),
                    Layouts.BINDING.getSourceSection(binding));
            return copy;
        }
    }

    @ImportStatic(BindingNodes.class)
    @CoreMethod(names = "local_variable_defined?", required = 1)
    @NodeChildren({
        @NodeChild(type = RubyNode.class, value = "binding"),
        @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class LocalVariableDefinedNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization(guards = {
                "name == cachedName",
                "!hiddenVariable(cachedName)",
                "getFrameDescriptor(binding) == descriptor"
        }, limit = "getCacheLimit()")
        public boolean localVariableDefinedCached(DynamicObject binding, String name,
                @Cached("name") String cachedName,
                @Cached("getFrameDescriptor(binding)") FrameDescriptor descriptor,
                @Cached("findFrameSlotOrNull(name, getFrame(binding))") FrameSlotAndDepth cachedFrameSlot) {
            return cachedFrameSlot != null;
        }

        @TruffleBoundary
        @Specialization(guards = "!hiddenVariable(name)")
        public boolean localVariableDefinedUncached(DynamicObject binding, String name) {
            return findFrameSlotOrNull(name, getFrame(binding)) != null;
        }

        @TruffleBoundary
        @Specialization(guards = "hiddenVariable(name)")
        public Object localVariableDefinedLastLine(DynamicObject binding, String name) {
            throw new RaiseException(getContext(), coreExceptions().nameError("Bad local variable name", binding, name, this));
        }

        protected int getCacheLimit() {
            return getContext().getOptions().BINDING_LOCAL_VARIABLE_CACHE;
        }

    }

    @ImportStatic(BindingNodes.class)
    @CoreMethod(names = "local_variable_get", required = 1)
    @NodeChildren({
        @NodeChild(type = RubyNode.class, value = "binding"),
        @NodeChild(type = RubyNode.class, value = "name")
    })
    public abstract static class LocalVariableGetNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization(guards = {
                "name == cachedName",
                "!hiddenVariable(cachedName)",
                "cachedFrameSlot != null",
                "getFrameDescriptor(binding) == descriptor"
        }, limit = "getCacheLimit()")
        public Object localVariableGetCached(DynamicObject binding, String name,
                @Cached("name") String cachedName,
                @Cached("getFrameDescriptor(binding)") FrameDescriptor descriptor,
                @Cached("findFrameSlotOrNull(name, getFrame(binding))") FrameSlotAndDepth cachedFrameSlot,
                @Cached("createReadNode(cachedFrameSlot)") ReadFrameSlotNode readLocalVariableNode) {
            final MaterializedFrame frame = RubyArguments.getDeclarationFrame(getFrame(binding), cachedFrameSlot.depth);
            return readLocalVariableNode.executeRead(frame);
        }

        @TruffleBoundary
        @Specialization(guards = "!hiddenVariable(name)")
        public Object localVariableGetUncached(DynamicObject binding, String name) {
            MaterializedFrame frame = getFrame(binding);
            FrameSlotAndDepth frameSlot = findFrameSlotOrNull(name, frame);
            if (frameSlot != null) {
                return RubyArguments.getDeclarationFrame(frame, frameSlot.depth).getValue(frameSlot.slot);
            } else {
                throw new RaiseException(getContext(), coreExceptions().nameErrorLocalVariableNotDefined(name, binding, this));
            }
        }

        @TruffleBoundary
        @Specialization(guards = "hiddenVariable(name)")
        public Object localVariableGetLastLine(DynamicObject binding, String name) {
            throw new RaiseException(getContext(), coreExceptions().nameError("Bad local variable name", binding, name, this));
        }

        protected ReadFrameSlotNode createReadNode(FrameSlotAndDepth frameSlot) {
            if (frameSlot == null) {
                return null;
            } else {
                return ReadFrameSlotNodeGen.create(frameSlot.slot);
            }
        }

        protected int getCacheLimit() {
            return getContext().getOptions().BINDING_LOCAL_VARIABLE_CACHE;
        }

    }

    @ImportStatic(BindingNodes.class)
    @CoreMethod(names = "local_variable_set", required = 2)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "binding"),
            @NodeChild(type = RubyNode.class, value = "name"),
            @NodeChild(type = RubyNode.class, value = "value")
    })
    public abstract static class LocalVariableSetNode extends CoreMethodNode {

        @CreateCast("name")
        public RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNodeGen.create(name);
        }

        @Specialization(guards = {
                "name == cachedName",
                "!hiddenVariable(cachedName)",
                "getFrameDescriptor(binding) == cachedFrameDescriptor",
                "cachedFrameSlot != null"
        }, limit = "getCacheLimit()")
        public Object localVariableSetCached(DynamicObject binding, String name, Object value,
                @Cached("name") String cachedName,
                @Cached("getFrameDescriptor(binding)") FrameDescriptor cachedFrameDescriptor,
                @Cached("findFrameSlotOrNull(name, getFrame(binding))") FrameSlotAndDepth cachedFrameSlot,
                @Cached("createWriteNode(cachedFrameSlot)") WriteFrameSlotNode writeLocalVariableNode) {
            final MaterializedFrame frame = RubyArguments.getDeclarationFrame(getFrame(binding), cachedFrameSlot.depth);
            return writeLocalVariableNode.executeWrite(frame, value);
        }

        @Specialization(guards = {
                "name == cachedName",
                "!hiddenVariable(cachedName)",
                "getFrameDescriptor(binding) == cachedFrameDescriptor",
                "cachedFrameSlot == null"
        }, limit = "getCacheLimit()")
        public Object localVariableSetNewCached(DynamicObject binding, String name, Object value,
                @Cached("name") String cachedName,
                @Cached("getFrameDescriptor(binding)") FrameDescriptor cachedFrameDescriptor,
                @Cached("findFrameSlotOrNull(name, getFrame(binding))") FrameSlotAndDepth cachedFrameSlot,
                @Cached("newFrameDescriptor(getContext(), name)") FrameDescriptor newDescriptor,
                @Cached("findFrameSlot(name, newDescriptor)") FrameSlotAndDepth newFrameSlot,
                @Cached("createWriteNode(newFrameSlot)") WriteFrameSlotNode writeLocalVariableNode) {
            final MaterializedFrame frame = newFrame(binding, newDescriptor);
            return writeLocalVariableNode.executeWrite(frame, value);
        }

        @TruffleBoundary
        @Specialization(guards = "!hiddenVariable(name)")
        public Object localVariableSetUncached(DynamicObject binding, String name, Object value) {
            MaterializedFrame frame = getFrame(binding);
            final FrameSlotAndDepth frameSlot = findFrameSlotOrNull(name, frame);
            final FrameSlot slot;
            if (frameSlot != null) {
                frame = RubyArguments.getDeclarationFrame(frame, frameSlot.depth);
                slot = frameSlot.slot;
            } else {
                frame = newFrame(binding, newFrameDescriptor(getContext(), name));
                slot = frame.getFrameDescriptor().findFrameSlot(name);
            }
            frame.setObject(slot, value);
            return value;
        }

        @TruffleBoundary
        @Specialization(guards = "hiddenVariable(name)")
        public Object localVariableSetLastLine(DynamicObject binding, String name, Object value) {
            throw new RaiseException(getContext(), coreExceptions().nameError("Bad local variable name", binding, name, this));
        }

        protected WriteFrameSlotNode createWriteNode(FrameSlotAndDepth frameSlot) {
            return WriteFrameSlotNodeGen.create(frameSlot.slot);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().BINDING_LOCAL_VARIABLE_CACHE;
        }
    }

    @Primitive(name = "local_variable_names", needsSelf = true)
    @ImportStatic(BindingNodes.class)
    public abstract static class LocalVariablesNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "getFrameDescriptor(binding) == cachedFrameDescriptor", limit = "getCacheLimit()")
        public DynamicObject localVariablesCached(DynamicObject binding,
                @Cached("getFrameDescriptor(binding)") FrameDescriptor cachedFrameDescriptor,
                @Cached("listLocalVariables(getContext(), getFrame(binding))") DynamicObject names) {
            return names;
        }

        @Specialization(replaces = "localVariablesCached")
        public DynamicObject localVariables(DynamicObject binding) {
            return listLocalVariables(getContext(), getFrame(binding));
        }

        @TruffleBoundary
        public static DynamicObject listLocalVariables(RubyContext context, MaterializedFrame frame) {
            final Set<Object> names = new LinkedHashSet<>();
            while (frame != null) {
                addNamesFromFrame(context, frame, names);

                frame = RubyArguments.getDeclarationFrame(frame);
            }
            final int size = names.size();
            return ArrayHelpers.createArray(context, names.toArray(new Object[size]), size);
        }

        private static void addNamesFromFrame(RubyContext context, Frame frame, final Set<Object> names) {
            for (FrameSlot slot : frame.getFrameDescriptor().getSlots()) {
                if (slot.getIdentifier() instanceof String &&
                        !hiddenVariable((String) slot.getIdentifier())) {
                    names.add(context.getSymbolTable().getSymbol((String) slot.getIdentifier()));
                }
            }
        }

        protected int getCacheLimit() {
            return getContext().getOptions().BINDING_LOCAL_VARIABLE_CACHE;
        }
    }

    @CoreMethod(names = "receiver")
    public abstract static class ReceiverNode extends UnaryCoreMethodNode {

        @Specialization
        public Object receiver(DynamicObject binding) {
            return RubyArguments.getSelf(Layouts.BINDING.getFrame(binding));
        }
    }

    // NOTE: Introduced in Ruby 2.6, but already useful for Binding#eval
    @CoreMethod(names = "source_location")
    public abstract static class SourceLocationNode extends UnaryCoreMethodNode {

        @TruffleBoundary
        @Specialization
        public Object sourceLocation(DynamicObject binding,
                @Cached("create()") MakeStringNode makeStringNode) {
            final SourceSection sourceSection = Layouts.BINDING.getSourceSection(binding);

            if (sourceSection == null) {
                return nil();
            } else {
                final DynamicObject file = makeStringNode.executeMake(getContext().getSourceLoader().getPath(sourceSection.getSource()), UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
                final Object[] store = new Object[]{ file, sourceSection.getStartLine() };
                return createArray(store, store.length);
            }
        }
    }

    @CoreMethod(names = "__allocate__", constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends UnaryCoreMethodNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            throw new RaiseException(getContext(), coreExceptions().typeErrorAllocatorUndefinedFor(rubyClass, this));
        }

    }

    @Primitive(name = "caller_binding", needsSelf = false)
    public abstract static class CallerBindingNode extends PrimitiveArrayArgumentsNode {

        @Child ReadCallerFrameNode callerFrameNode = new ReadCallerFrameNode(CallerFrameAccess.MATERIALIZE);

        public abstract DynamicObject executeBinding(VirtualFrame frame);

        @Specialization
        public DynamicObject binding(VirtualFrame frame) {
            final MaterializedFrame callerFrame = callerFrameNode.execute(frame).materialize();

            return BindingNodes.createBinding(getContext(), callerFrame);
        }
    }

    @Primitive(name = "caller_binding_with_source_location_from_caller", needsSelf = false)
    public abstract static class CallerBindingWithSourceLocationFromCallerNode extends PrimitiveArrayArgumentsNode {

        @Child ReadCallerFrameNode callerFrameNode = new ReadCallerFrameNode(CallerFrameAccess.MATERIALIZE);

        public abstract DynamicObject executeBinding(VirtualFrame frame);

        @Specialization(guards = "isCloningEnabled()")
        protected DynamicObject bindingCached(VirtualFrame frame,
                @Cached("getCallerSourceSection()") SourceSection cachedSourceSection) {
            final MaterializedFrame callerFrame = callerFrameNode.execute(frame).materialize();

            return BindingNodes.createBinding(getContext(), callerFrame, cachedSourceSection);
        }

        @Specialization
        protected DynamicObject bindingUncached(VirtualFrame frame) {
            final MaterializedFrame callerFrame = callerFrameNode.execute(frame).materialize();
            final SourceSection sourceSection = getCallerSourceSection();

            return BindingNodes.createBinding(getContext(), callerFrame, sourceSection);
        }

        @TruffleBoundary
        protected SourceSection getCallerSourceSection() {
            // TODO: ignore #send
            return getContext().getCallStack().getCallerNode(0, true).getEncapsulatingSourceSection();
        }

        protected boolean isCloningEnabled() {
            return coreLibrary().isCloningEnabled();
        }
    }

}
