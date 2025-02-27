/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.access;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.cast.JSToBooleanNode;
import com.oracle.truffle.js.nodes.cast.JSToPropertyKeyNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.nodes.interop.ForeignObjectPrototypeNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.interop.JSInteropUtil;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

@NodeInfo(cost = NodeCost.NONE)
@ImportStatic({JSProxy.class})
public abstract class JSProxyHasPropertyNode extends JavaScriptBaseNode {

    @Child protected GetMethodNode trapGetter;
    @Child private JSFunctionCallNode callNode;
    @Child private JSToBooleanNode toBooleanNode;
    @Child private JSToPropertyKeyNode toPropertyKeyNode;
    @Child private ForeignObjectPrototypeNode foreignObjectPrototypeNode;
    private final BranchProfile errorBranch = BranchProfile.create();

    public JSProxyHasPropertyNode(JSContext context) {
        this.callNode = JSFunctionCallNode.createCall();
        this.trapGetter = GetMethodNode.create(context, JSProxy.HAS);
        this.toPropertyKeyNode = JSToPropertyKeyNode.create();
        this.toBooleanNode = JSToBooleanNode.create();
    }

    public static JSProxyHasPropertyNode create(JSContext context) {
        return JSProxyHasPropertyNodeGen.create(context);
    }

    public abstract boolean executeWithTargetAndKeyBoolean(Object shared, Object key);

    @Specialization
    protected boolean doGeneric(JSDynamicObject proxy, Object key,
                    @Cached("createBinaryProfile()") ConditionProfile trapFunProfile) {
        assert JSProxy.isJSProxy(proxy);
        Object propertyKey = toPropertyKeyNode.execute(key);
        JSDynamicObject handler = JSProxy.getHandlerChecked(proxy, errorBranch);
        Object target = JSProxy.getTarget(proxy);
        Object trapFun = trapGetter.executeWithTarget(handler);
        if (trapFunProfile.profile(trapFun == Undefined.instance)) {
            if (JSDynamicObject.isJSDynamicObject(target)) {
                return JSObject.hasProperty((JSDynamicObject) target, propertyKey);
            } else {
                boolean result = JSInteropUtil.hasProperty(target, propertyKey);
                if (!result) {
                    result = maybeHasInPrototype(target, propertyKey);
                }
                return result;
            }
        } else {
            Object callResult = callNode.executeCall(JSArguments.create(handler, trapFun, target, propertyKey));
            boolean trapResult = toBooleanNode.executeBoolean(callResult);
            if (!trapResult) {
                if (!JSProxy.checkPropertyIsSettable(target, propertyKey)) {
                    errorBranch.enter();
                    throw Errors.createTypeError("Proxy can't successfully access a non-writable, non-configurable property", this);
                }
            }
            return trapResult;
        }
    }

    private boolean maybeHasInPrototype(Object target, Object propertyKey) {
        assert JSRuntime.isPropertyKey(propertyKey);
        if (getLanguage().getJSContext().getContextOptions().hasForeignObjectPrototype()) {
            if (foreignObjectPrototypeNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                foreignObjectPrototypeNode = insert(ForeignObjectPrototypeNode.create());
            }
            JSDynamicObject prototype = foreignObjectPrototypeNode.execute(target);
            return JSObject.hasProperty(prototype, propertyKey);
        }
        return false;
    }

}
