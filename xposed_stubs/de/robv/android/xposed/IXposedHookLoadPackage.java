package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Member;

public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
