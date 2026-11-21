package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

@SuppressWarnings("unused")
public class XposedInterfaceWrapper implements XposedInterface {

    public final void attachFramework(XposedInterface base, Runnable detachImpl) {
        throw new UnsupportedOperationException("Stub");
    }

    public final void detach() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final int getApiVersion() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final String getFrameworkName() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final String getFrameworkVersion() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final long getFrameworkVersionCode() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final long getFrameworkProperties() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final HookBuilder hook(Executable origin) {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final HookBuilder hookClassInitializer(Class<?> origin) {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final boolean deoptimize(Executable executable) {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final Invoker<?, Method> getInvoker(Method method) {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final <T> CtorInvoker<T> getInvoker(Constructor<T> constructor) {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final void log(int priority, String tag, String msg) {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final void log(int priority, String tag, String msg, Throwable tr) {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final SharedPreferences getRemotePreferences(String name) {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final ApplicationInfo getModuleApplicationInfo() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final String[] listRemoteFiles() {
        throw new UnsupportedOperationException("Stub");
    }

    @Override
    public final ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        throw new UnsupportedOperationException("Stub");
    }
}
