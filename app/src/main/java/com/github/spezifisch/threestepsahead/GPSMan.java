package com.github.spezifisch.threestepsahead;

import java.lang.reflect.Method;
import java.util.Arrays;

import android.location.GpsStatus;
import android.location.Location;
import android.os.Message;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XC_MethodHook;

import java.util.List;
import java.util.Random;

public class GPSMan implements IXposedHookLoadPackage {
    // our app
    private static final String THIS_APP = "com.github.spezifisch.threestepsahead";

    // hooked apps
    private List<String> hooked_apps = Arrays.asList(
            "com.vonglasow.michael.satstat",
            "com.nianticlabs.pokemongo"
    );

    // IPC to JoystickService
    private SettingsStorage settingsStorage;
    private IPC.SettingsClient settings;
    private IPC.Client serviceClient;

    private Location location;
    private Random rand;
    private boolean simulateNoise = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(THIS_APP)) {
            XposedBridge.log(THIS_APP + " app loaded");

            // for the main app to know xposed is running
            Class<?> clazz = XposedHelpers.findClass(THIS_APP + ".SettingsStorage", lpparam.classLoader);
            XposedHelpers.setStaticBooleanField(clazz, "xposed_loaded", true);
        } else if (isHookedApp(lpparam.packageName)) {
            XposedBridge.log(lpparam.packageName + " app loaded -> initing");
            // init stuff when hooked app is started

            // init random
            rand = new Random(System.currentTimeMillis() + 234213370);

            // file settings
            settingsStorage = new SettingsStorage();

            // IPC instance
            settings = new IPC.SettingsClient();
            settings.setTagSuffix("GPSMan");
            settings.setInXposed(true);
            settings.setSettingsStorage(settingsStorage);

            // pair Service and Client
            serviceClient = new IPC.Client(settings);
            serviceClient.setInXposed(true);
            settings.setClient(serviceClient);

            // init location
            if (location == null) {
                updateLocation();
            }

            // hooky!
            initHookListenerTransport(lpparam);
            initHookGpsStatus(lpparam);
            initHookGetLastKnownLocation(lpparam);
        }
    }

    private boolean isHookedApp(String packageName) {
        return hooked_apps.contains(packageName);
    }

    void initHookListenerTransport(LoadPackageParam lpparam) {
        class ListenerTransportHook extends XC_MethodHook {
            // this hooks an internal method of LocationManager, which calls OnLocationChanged and other callbacks

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) {
                    return;
                }
                if (!serviceClient.connect() || !settings.isEnabled()) {
                    return;
                }

                Message message = (Message) param.args[0];
                if (message.what == 1) { // TYPE_LOCATION_CHANGED
                    // see: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/location/java/android/location/LocationManager.java

                    // get current location
                    updateLocation();

                    // overwrite given location
                    message.obj = fakeLocation((Location)message.obj);;
                    param.args[0] = message;

                    XposedBridge.log("ListenerTransport Location faked: " + message.obj);
                }
            }
        }

        XposedHelpers.findAndHookMethod("android.location.LocationManager$ListenerTransport", lpparam.classLoader,
                "_handleMessage", Message.class, new ListenerTransportHook());
    }

    void initHookGpsStatus(LoadPackageParam lpparam) {
        class GpsStatusHook extends XC_MethodHook {
            // This hooks getGpsStatus function which returns GpsStatus.
            // We use the internal method setStatus to override the satellite info.

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) {
                    return;
                }
                if (!serviceClient.connect() || !settings.isEnabled()) {
                    return;
                }

                GpsStatus gpsStatus = (GpsStatus) param.getResult();

                // find method setStatus
                Method[] declaredMethods = GpsStatus.class.getDeclaredMethods();
                for (Method method: declaredMethods) {
			// TODO
                }
            }
        }

        XposedHelpers.findAndHookMethod("android.location.LocationManager", lpparam.classLoader,
                "getGpsStatus", GpsStatus.class, new GpsStatusHook());
    }

    void initHookGetLastKnownLocation(LoadPackageParam lpparam) {
        class LastKnownLocationHook extends XC_MethodHook {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) {
                    return;
                }
                if (!serviceClient.connect() || !settings.isEnabled()) {
                    return;
                }

                if (param.args[0] != null) { // provider enabled + location returned
                    Location location = fakeLocation((Location)param.args[0]);
                    param.setResult(location);

                    XposedBridge.log("getLastKnownLocation Location faked: " + location);
                }
            }
        }

        XposedHelpers.findAndHookMethod("android.location.LocationManager", lpparam.classLoader,
                "getLastKnownLocation", String.class, new LastKnownLocationHook());
    }

    private void updateLocation() {
        // get current fake location
        location = settings.getLocation();

        // add gaussian noise with given sigma
        if (simulateNoise) {
            location.setBearing((float) (location.getBearing() + rand.nextGaussian() * 2.0));      // 2 deg
            location.setSpeed((float) Math.abs(location.getSpeed() + rand.nextGaussian() * 0.2));  // 0.2 m/s
            double distance = rand.nextGaussian() * Math.max(5.0, location.getAccuracy()) / 3.0;        // 5 m or accuracy (getAccuracy looks rather than 3sigma)
            double theta = Math.toRadians(rand.nextFloat() * 360.0);                                    // direction of displacement should be uniformly distributed
            location = LocationHelper.displace(location, distance, theta);
        }
    }

    private Location fakeLocation(Location loc) {
        // overwrite faked parts of Location
        loc.setLatitude(location.getLatitude());
        loc.setLongitude(location.getLongitude());
        //location.setTime(System.currentTimeMillis());
        loc.setAltitude(location.getAltitude());
        loc.setSpeed(location.getSpeed());
        //loc.setAccuracy(location.getAccuracy());
        loc.setBearing(location.getBearing());

        return loc;
    }
}
