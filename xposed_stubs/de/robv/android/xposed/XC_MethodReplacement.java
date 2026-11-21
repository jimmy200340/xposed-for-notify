package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodReplacement extends XC_MethodHook {
    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                return result;
            }
        };
    }
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
