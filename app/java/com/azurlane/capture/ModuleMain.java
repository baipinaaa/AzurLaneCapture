package com.azurlane.capture;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** 模块入口（libxposed API 102）。只对 com.bilibili.azurlane 生效。 */
public class ModuleMain extends XposedModule {

    public ModuleMain() {}

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, Config.TAG, "module loaded: " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!Config.TARGET_PKG.equals(pkg)) return;

        log(Log.INFO, Config.TAG, "package loaded: " + pkg + " first=" + param.isFirstPackage());

        try {
            hookApplicationAttach();
        } catch (Throwable t) {
            log(Log.ERROR, Config.TAG, "hook Application.attach failed", t);
        }
        try {
            LogHooks.install(this);
        } catch (Throwable t) {
            log(Log.ERROR, Config.TAG, "LogHooks failed", t);
        }
        try {
            StreamHooks.install(this);
        } catch (Throwable t) {
            log(Log.ERROR, Config.TAG, "StreamHooks failed", t);
        }
        try {
            CrashHook.install(this);
        } catch (Throwable t) {
            log(Log.ERROR, Config.TAG, "CrashHook failed", t);
        }
        try {
            LogHooks.probeLua(this, param.getDefaultClassLoader());
        } catch (Throwable t) {
            log(Log.WARN, Config.TAG, "probeLua failed", t);
        }
    }

    private void hookApplicationAttach() throws Throwable {
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        hook(attach).setPriority(PRIORITY_DEFAULT).intercept(chain -> {
            Object ret = chain.proceed();
            Object c = chain.getArg(0);
            if (c instanceof Context) onGameContext((Context) c);
            return ret;
        });
    }

    private void onGameContext(Context ctx) {
        try {
            FileSink.start(ctx);
        } catch (Throwable t) {
            log(Log.ERROR, Config.TAG, "sink start failed", t);
        }
        try {
            NativeBridge.start(getModuleApplicationInfo());
        } catch (Throwable t) {
            log(Log.ERROR, Config.TAG, "native start failed", t);
        }
        log(Log.INFO, Config.TAG, "hooks installed for " + ctx.getPackageName());
    }
}
