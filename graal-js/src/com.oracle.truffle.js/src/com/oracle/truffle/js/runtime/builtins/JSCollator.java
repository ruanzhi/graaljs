/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.runtime.builtins;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RuleBasedCollator;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.LocationModifier;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.builtins.intl.CollatorFunctionBuiltins;
import com.oracle.truffle.js.builtins.intl.CollatorPrototypeBuiltins;
import com.oracle.truffle.js.lang.JavaScriptLanguage;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSContext.BuiltinFunctionKey;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.IntlUtil;

public final class JSCollator extends JSBuiltinObject implements JSConstructorFactory.Default.WithFunctions, PrototypeSupplier {

    public static final String CLASS_NAME = "Collator";
    public static final String PROTOTYPE_NAME = "Collator.prototype";

    private static final HiddenKey INTERNAL_STATE_ID = new HiddenKey("_internalState");
    private static final Property INTERNAL_STATE_PROPERTY;

    static final HiddenKey BOUND_OBJECT_KEY = new HiddenKey(CLASS_NAME);

    public static final JSCollator INSTANCE = new JSCollator();

    // Valid values of Unicode collation ("co") type key.
    // Based on https://github.com/unicode-org/cldr/blob/master/common/bcp47/collation.xml
    // "standard" and "search" are missing from the list because ECMAScript spec says:
    // The values "standard" and "search" must not be used as elements
    // in any [[SortLocaleData]].[[<locale>]].[[co]]
    // and [[SearchLocaleData]].[[<locale>]].[[co]] list.
    private static final Set<String> VALID_COLLATION_TYPES = new HashSet<>(Arrays.asList(new String[]{
                    "big5han",
                    "compat",
                    "dict",
                    "direct",
                    "ducet",
                    "emoji",
                    "eor",
                    "gb2312",
                    "phonebk",
                    "phonetic",
                    "pinyin",
                    "reformed",
                    "searchjl",
                    "stroke",
                    "trad",
                    "unihan",
                    "zhuyin"
    }));

    static {
        Shape.Allocator allocator = JSShape.makeAllocator(JSObject.LAYOUT);
        INTERNAL_STATE_PROPERTY = JSObjectUtil.makeHiddenProperty(INTERNAL_STATE_ID, allocator.locationForType(InternalState.class, EnumSet.of(LocationModifier.NonNull, LocationModifier.Final)));
    }

    private JSCollator() {
    }

    public static boolean isJSCollator(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSCollator((DynamicObject) obj);
    }

    public static boolean isJSCollator(DynamicObject obj) {
        return isInstance(obj, INSTANCE);
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @Override
    public String getClassName(DynamicObject object) {
        return getClassName();
    }

    @Override
    public DynamicObject createPrototype(JSRealm realm, DynamicObject ctor) {
        JSContext ctx = realm.getContext();
        DynamicObject collatorPrototype = JSObject.createInit(realm, realm.getObjectPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putConstructorProperty(ctx, collatorPrototype, ctor);
        JSObjectUtil.putFunctionsFromContainer(realm, collatorPrototype, CollatorPrototypeBuiltins.BUILTINS);
        JSObjectUtil.putConstantAccessorProperty(ctx, collatorPrototype, "compare", createCompareFunctionGetter(realm, ctx), Undefined.instance);
        JSObjectUtil.putDataProperty(ctx, collatorPrototype, Symbol.SYMBOL_TO_STRING_TAG, "Object", JSAttributes.configurableNotEnumerableNotWritable());
        return collatorPrototype;
    }

    // localeMatcher unused as our lookup matcher and best fit matcher are the same at the moment
    @TruffleBoundary
    public static void initializeCollator(JSContext ctx, JSCollator.InternalState state, String[] locales, String usage, @SuppressWarnings("unused") String localeMatcher, Boolean optkn, String optkf,
                    String sensitivity, Boolean ignorePunctuation) {
        Boolean kn = optkn;
        String kf = optkf;
        // Set collator.[[Usage]] to usage.
        // "search" maps to -u-co-search, "sort" means the default behavior
        String co = IntlUtil.SEARCH.equals(usage) ? IntlUtil.SEARCH : null;
        state.initializedCollator = true;
        state.usage = usage;
        String selectedTag = IntlUtil.selectedLocale(ctx, locales);
        Locale selectedLocale = selectedTag != null ? Locale.forLanguageTag(selectedTag) : ctx.getLocale();
        Locale strippedLocale = selectedLocale.stripExtensions();
        for (String ek : selectedLocale.getUnicodeLocaleKeys()) {
            if (kn == null && ek.equals("kn")) {
                String ktype = selectedLocale.getUnicodeLocaleType(ek);
                if (ktype.isEmpty() || ktype.equals("true")) {
                    kn = true;
                }
            }
            if (kf == null && ek.equals("kf")) {
                String ktype = selectedLocale.getUnicodeLocaleType(ek);
                if (!ktype.isEmpty()) {
                    kf = ktype;
                }
            }
            if (co == null && ek.equals("co")) {
                String coType = selectedLocale.getUnicodeLocaleType(ek);
                if (!coType.isEmpty()) {
                    co = coType;
                }
            }
        }
        Locale.Builder builder = new Locale.Builder().setLocale(strippedLocale);
        if (kn != null) {
            state.numeric = kn;
        }
        if (kf != null) {
            state.caseFirst = kf;
        }
        if (VALID_COLLATION_TYPES.contains(co)) {
            // Let collation be r.[[co]].
            state.collation = co;
            builder.setUnicodeLocaleKeyword("co", co);
        }
        if (sensitivity != null) {
            state.sensitivity = sensitivity;
        }
        state.ignorePunctuation = ignorePunctuation;
        Locale collatorLocale = builder.build();
        state.locale = collatorLocale.toLanguageTag();

        // "search" is not allowed in r.[[co]] but it must be set in the Locale
        // used by the Collator (so that the Collator uses "search" collation).
        if (IntlUtil.SEARCH.equals(usage)) {
            assert IntlUtil.SEARCH.equals(co);
            collatorLocale = builder.setUnicodeLocaleKeyword("co", IntlUtil.SEARCH).build();
        }

        state.collator = Collator.getInstance(collatorLocale);
        state.collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        switch (state.sensitivity) {
            case IntlUtil.BASE:
                state.collator.setStrength(Collator.PRIMARY);
                break;
            case IntlUtil.ACCENT:
                state.collator.setStrength(Collator.SECONDARY);
                break;
            case IntlUtil.CASE:
            case IntlUtil.VARIANT:
                state.collator.setStrength(Collator.TERTIARY);
                break;
        }
        if (state.ignorePunctuation) {
            if (state.collator instanceof RuleBasedCollator) {
                ((RuleBasedCollator) state.collator).setAlternateHandlingShifted(true);
            }
        }
    }

    @Override
    public Shape makeInitialShape(JSContext ctx, DynamicObject prototype) {
        Shape initialShape = JSObjectUtil.getProtoChildShape(prototype, INSTANCE, ctx);
        initialShape = initialShape.addProperty(INTERNAL_STATE_PROPERTY);
        return initialShape;
    }

    public static JSConstructor createConstructor(JSRealm realm) {
        return INSTANCE.createConstructorAndPrototype(realm, CollatorFunctionBuiltins.BUILTINS);
    }

    public static DynamicObject create(JSContext context) {
        InternalState state = new InternalState();
        DynamicObject result = JSObject.create(context, context.getCollatorFactory(), state);
        assert isJSCollator(result);
        return result;
    }

    public static Collator getCollatorProperty(DynamicObject obj) {
        return getInternalState(obj).collator;
    }

    @TruffleBoundary
    public static int compare(DynamicObject collatorObj, String one, String two) {
        Collator collator = getCollatorProperty(collatorObj);
        return collator.compare(one, two);
    }

    @TruffleBoundary
    public static int caseSensitiveCompare(DynamicObject collatorObj, String one, String two) {
        Collator collator = getCollatorProperty(collatorObj);
        String a = stripAccents(one);
        String b = stripAccents(two);
        return collator.compare(a, b);
    }

    private static String stripAccents(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder resultBuilder = new StringBuilder(Normalizer.normalize(input, Normalizer.Form.NFD));
        stripLlAccents(resultBuilder);
        Pattern accentMatchingPattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        return accentMatchingPattern.matcher(resultBuilder).replaceAll("");
    }

    private static void stripLlAccents(StringBuilder s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\u0141') {
                s.setCharAt(i, 'L');
            } else if (s.charAt(i) == '\u0142') {
                s.setCharAt(i, 'l');
            }
        }
    }

    public static class InternalState {

        private boolean initializedCollator = false;
        private Collator collator;

        private DynamicObject boundCompareFunction = null;

        private String locale;
        private String usage = IntlUtil.SORT;
        private String sensitivity = IntlUtil.VARIANT;
        private String collation = IntlUtil.DEFAULT;
        private boolean ignorePunctuation = false;
        private boolean numeric = false;
        private String caseFirst = IntlUtil.FALSE;

        DynamicObject toResolvedOptionsObject(JSContext context) {
            DynamicObject result = JSUserObject.create(context);
            JSObjectUtil.defineDataProperty(result, IntlUtil.LOCALE, locale, JSAttributes.getDefault());
            JSObjectUtil.defineDataProperty(result, IntlUtil.USAGE, usage, JSAttributes.getDefault());
            JSObjectUtil.defineDataProperty(result, IntlUtil.SENSITIVITY, sensitivity, JSAttributes.getDefault());
            JSObjectUtil.defineDataProperty(result, IntlUtil.IGNORE_PUNCTUATION, ignorePunctuation, JSAttributes.getDefault());
            JSObjectUtil.defineDataProperty(result, IntlUtil.COLLATION, collation, JSAttributes.getDefault());
            JSObjectUtil.defineDataProperty(result, IntlUtil.NUMERIC, numeric, JSAttributes.getDefault());
            JSObjectUtil.defineDataProperty(result, IntlUtil.CASE_FIRST, caseFirst, JSAttributes.getDefault());
            return result;
        }
    }

    @TruffleBoundary
    public static DynamicObject resolvedOptions(JSContext context, DynamicObject collatorObj) {
        InternalState state = getInternalState(collatorObj);
        return state.toResolvedOptionsObject(context);
    }

    public static InternalState getInternalState(DynamicObject collatorObj) {
        return (InternalState) INTERNAL_STATE_PROPERTY.get(collatorObj, isJSCollator(collatorObj));
    }

    private static CallTarget createGetCompareCallTarget(JSContext context) {
        return Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {

            @CompilationFinal private ContextReference<JSRealm> realmRef;
            @Child private PropertySetNode setBoundObjectNode = PropertySetNode.createSetHidden(BOUND_OBJECT_KEY, context);

            @Override
            public Object execute(VirtualFrame frame) {

                Object[] frameArgs = frame.getArguments();
                Object collatorObj = JSArguments.getThisObject(frameArgs);

                if (isJSCollator(collatorObj)) {

                    InternalState state = getInternalState((DynamicObject) collatorObj);

                    if (state == null || !state.initializedCollator) {
                        throw Errors.createTypeErrorMethodCalledOnNonObjectOrWrongType("compare");
                    }

                    if (state.boundCompareFunction == null) {
                        JSFunctionData compareFunctionData;
                        DynamicObject compareFn;
                        if (realmRef == null) {
                            CompilerDirectives.transferToInterpreterAndInvalidate();
                            realmRef = lookupContextReference(JavaScriptLanguage.class);
                        }
                        JSRealm realm = realmRef.get();
                        if (state.sensitivity.equals(IntlUtil.CASE)) {
                            compareFunctionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.CollatorCaseSensitiveCompare,
                                            c -> createCaseSensitiveCompareFunctionData(c));
                            compareFn = JSFunction.create(realm, compareFunctionData);
                        } else {
                            compareFunctionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.CollatorCompare, c -> createCompareFunctionData(c));
                            compareFn = JSFunction.create(realm, compareFunctionData);
                        }
                        setBoundObjectNode.setValue(compareFn, collatorObj);
                        state.boundCompareFunction = compareFn;
                    }

                    return state.boundCompareFunction;
                }
                throw Errors.createTypeErrorTypeXExpected(CLASS_NAME);
            }
        });
    }

    private static JSFunctionData createCompareFunctionData(JSContext context) {
        return JSFunctionData.createCallOnly(context, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {
            @Child private PropertyGetNode getBoundObjectNode = PropertyGetNode.createGetHidden(BOUND_OBJECT_KEY, context);
            @Child private JSToStringNode toString1Node = JSToStringNode.create();
            @Child private JSToStringNode toString2Node = JSToStringNode.create();

            @Override
            public Object execute(VirtualFrame frame) {
                Object[] arguments = frame.getArguments();
                DynamicObject thisObj = (DynamicObject) getBoundObjectNode.getValue(JSArguments.getFunctionObject(arguments));
                assert isJSCollator(thisObj);
                int argumentCount = JSArguments.getUserArgumentCount(arguments);
                String one = (argumentCount > 0) ? toString1Node.executeString(JSArguments.getUserArgument(arguments, 0)) : Undefined.NAME;
                String two = (argumentCount > 1) ? toString2Node.executeString(JSArguments.getUserArgument(arguments, 1)) : Undefined.NAME;
                return compare(thisObj, one, two);
            }
        }), 2, "");
    }

    private static JSFunctionData createCaseSensitiveCompareFunctionData(JSContext context) {
        return JSFunctionData.createCallOnly(context, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {
            @Child private PropertyGetNode getBoundObjectNode = PropertyGetNode.createGetHidden(BOUND_OBJECT_KEY, context);
            @Child private JSToStringNode toString1Node = JSToStringNode.create();
            @Child private JSToStringNode toString2Node = JSToStringNode.create();

            @Override
            public Object execute(VirtualFrame frame) {
                Object[] arguments = frame.getArguments();
                DynamicObject thisObj = (DynamicObject) getBoundObjectNode.getValue(JSArguments.getFunctionObject(arguments));
                assert isJSCollator(thisObj);
                int argumentCount = JSArguments.getUserArgumentCount(arguments);
                String one = (argumentCount > 0) ? toString1Node.executeString(JSArguments.getUserArgument(arguments, 0)) : Undefined.NAME;
                String two = (argumentCount > 1) ? toString2Node.executeString(JSArguments.getUserArgument(arguments, 1)) : Undefined.NAME;
                return caseSensitiveCompare(thisObj, one, two);
            }
        }), 2, "");
    }

    private static DynamicObject createCompareFunctionGetter(JSRealm realm, JSContext context) {
        JSFunctionData fd = realm.getContext().getOrCreateBuiltinFunctionData(BuiltinFunctionKey.CollatorGetCompare, (c) -> {
            CallTarget ct = createGetCompareCallTarget(context);
            return JSFunctionData.create(context, ct, ct, 0, "get compare", false, false, false, true);
        });
        return JSFunction.create(realm, fd);
    }

    @Override
    public DynamicObject getIntrinsicDefaultProto(JSRealm realm) {
        return realm.getCollatorPrototype();
    }
}
