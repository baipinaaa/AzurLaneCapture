package com.azurlane.capture;

import java.lang.reflect.Method;

/** 未捕获异常：hook ThreadGroup.uncaughtException（保留原处理链路）。 */
final class CrashHook {
    static void install(ModuleMain mod) throws Throwable {
        Method m = ThreadGroup.class.getDeclaredMethod(
                "uncaughtException", Thread.class, Throwable.class);
        mod.hook(m).intercept(chain -> {
            Object t = chain.getArg(0);
            Object e = chain.getArg(1);
            if (e instanceof Throwable) {
                FileSink.crash(t instanceof Thread ? (Thread) t : null, (Throwable) e);
            }
            return chain.proceed();
        });
    }

    private CrashHook() {}
}
