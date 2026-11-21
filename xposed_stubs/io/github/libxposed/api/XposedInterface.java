package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.error.HookFailedError;

@SuppressWarnings("unused")
public interface XposedInterface {
    int API_101 = 101;
    int API_102 = 102;
    int LIB_API = API_102;

    long PROP_CAP_SYSTEM = 1L;
    long PROP_CAP_REMOTE = 1L << 1;
    long PROP_RT_API_PROTECTION = 1L << 2;

    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST = Integer.MIN_VALUE;
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    interface Invoker<T extends Invoker<T, U>, U extends Executable> {
        interface Type {
            Origin ORIGIN = new Origin();

            final class Origin implements Type {
            }

            final class Chain implements Type {
                public static final Chain FULL = new Chain(PRIORITY_HIGHEST);
                private final int maxPriority;

                public Chain(int maxPriority) {
                    this.maxPriority = maxPriority;
                }

                public int maxPriority() {
                    return maxPriority;
                }
            }
        }

        T setType(Type type);

        Object invoke(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;

        Object invokeSpecial(Object thisObject, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;
    }

    interface CtorInvoker<T> extends Invoker<CtorInvoker<T>, Constructor<T>> {
        T newInstance(Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;

        <U> U newInstanceSpecial(Class<U> subClass, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException;
    }

    interface Chain {
        Executable getExecutable();

        Object getThisObject();

        List<Object> getArgs();

        Object getArg(int index) throws IndexOutOfBoundsException, ClassCastException;

        Object proceed() throws Throwable;

        Object proceed(Object[] args) throws Throwable;

        Object proceedWith(Object thisObject) throws Throwable;

        Object proceedWith(Object thisObject, Object[] args) throws Throwable;
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface HookHandle {
        Executable getExecutable();

        void unhook();

        String getId();

        HookHandle replaceHook(Hooker hooker);
    }

    enum ExceptionMode {
        DEFAULT,
        PROTECTIVE,
        PASSTHROUGH,
    }

    interface HookBuilder {
        HookBuilder setPriority(int priority);

        HookBuilder setExceptionMode(ExceptionMode mode);

        HookHandle intercept(Hooker hooker) throws HookFailedError;

        HookBuilder setId(String id);
    }

    default int getApiVersion() {
        return LIB_API;
    }

    String getFrameworkName();

    String getFrameworkVersion();

    long getFrameworkVersionCode();

    long getFrameworkProperties();

    HookBuilder hook(Executable origin);

    HookBuilder hookClassInitializer(Class<?> origin);

    boolean deoptimize(Executable executable);

    Invoker<?, Method> getInvoker(Method method);

    <T> CtorInvoker<T> getInvoker(Constructor<T> constructor);

    void log(int priority, String tag, String msg);

    void log(int priority, String tag, String msg, Throwable tr);

    ApplicationInfo getModuleApplicationInfo();

    SharedPreferences getRemotePreferences(String group);

    String[] listRemoteFiles();

    ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException;
}
