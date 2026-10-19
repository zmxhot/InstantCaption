package com.localcaption.instant;

/** Initialize bundled configuration before activities, services and instrumentation. */
public final class CaptionApplication extends android.app.Application {
    @Override public void onCreate(){super.onCreate();DeviceModelCatalog.initialize(this);}
}
