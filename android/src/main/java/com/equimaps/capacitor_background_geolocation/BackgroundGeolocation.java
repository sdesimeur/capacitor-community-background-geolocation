package com.equimaps.capacitor_background_geolocation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import com.getcapacitor.android.BuildConfig;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

@NativePlugin(
        permissions={
                Manifest.permission.ACCESS_FINE_LOCATION
        },
        // A random integer which is hopefully unique to this plugin.
        permissionRequestCode = 28351
)
public class BackgroundGeolocation extends Plugin {
    private PluginCall callPendingPermissions = null;

    @PluginMethod(returnType=PluginMethod.RETURN_CALLBACK)
    public void addWatcher(PluginCall call) {
        if (service == null) {
            call.error("Service not running.");
            return;
        }

        call.save();
        if (!hasRequiredPermissions()) {
            callPendingPermissions = call;
            pluginRequestAllPermissions();
            return;
        }

        Notification backgroundNotification = null;
        String backgroundMessage = call.getString("backgroundMessage");
        if (backgroundMessage != null) {
            Notification.Builder builder = new Notification.Builder(getContext())
                    .setContentTitle(
                            call.getString(
                                "backgroundTitle",
                                "Using your location"
                            )
                    )
                    .setContentText(backgroundMessage)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setWhen(System.currentTimeMillis());

            try {
                builder.setSmallIcon(
                        getContext().getPackageManager().getApplicationInfo(
                                getContext().getPackageName(),
                                PackageManager.GET_META_DATA
                        ).icon
                );
            } catch (PackageManager.NameNotFoundException e) {
                Logger.error("Package name not found", e);
            }

            Intent launchIntent = getContext().getPackageManager().getLaunchIntentForPackage(
                    getContext().getPackageName()
            );
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                builder.setContentIntent(
                        PendingIntent.getActivity(
                                getContext(),
                                0,
                                launchIntent,
                                PendingIntent.FLAG_CANCEL_CURRENT
                        )
                );
            }

            // Set the Channel ID for Android O.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(BackgroundGeolocationService.class.getPackage().getName());
            }

            backgroundNotification = builder.build();
        }

        callPendingPermissions = null;
        service.addWatcher(
                call.getCallbackId(),
                backgroundNotification
        );
    }

    @Override
    protected void handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults);

        if (callPendingPermissions == null) {
            return;
        }

        for(int result : grantResults) {
            if (result == PackageManager.PERMISSION_DENIED) {
                callPendingPermissions.reject("User denied location permission", "NOT_AUTHORIZED");
                callPendingPermissions.release(bridge);
                return;
            }
        }

        addWatcher(callPendingPermissions);
    }

    @PluginMethod()
    public void removeWatcher(PluginCall call) {
        String callbackId = call.getString("id");
        if (callbackId == null) {
            call.error("Missing id.");
            return;
        }
        service.removeWatcher(callbackId);
        PluginCall savedCall = bridge.getSavedCall(callbackId);
        if (savedCall != null) {
            savedCall.release(bridge);
        }
        call.success();
    }

    @PluginMethod()
    public void openSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getContext().getPackageName(), null);
        intent.setData(uri);
        getContext().startActivity(intent);
        call.success();
    }

    // Sends messages to the service.
    private BackgroundGeolocationService.LocalBinder service = null;

    // Receives messages from the service.
    private class ServiceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra("id");
            PluginCall call = bridge.getSavedCall(id);
            if (call == null) {
                return;
            }
            Location location = intent.getParcelableExtra("location");
            if (location == null) {
                if (BuildConfig.DEBUG) {
                    call.error("No locations received");
                }
                return;
            }
            JSObject obj = new JSObject();
            obj.put("latitude", location.getLatitude());
            obj.put("longitude", location.getLongitude());
            // The docs state that all Location objects have an accuracy, but then why is there a
            // hasAccuracy method? Better safe than sorry.
            obj.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : null);
            obj.put("altitude", location.hasAltitude() ? location.getAltitude() : null);
            obj.put(
                "altitudeAccuracy",
                (
                    Build.VERSION.SDK_INT >= 26 &&
                    location.hasVerticalAccuracy()
                )
                ? location.getVerticalAccuracyMeters()
                : null
            );
            obj.put("speed", location.hasSpeed() ? location.getSpeed() : null);
            obj.put("bearing", location.hasBearing() ? location.getBearing() : null);
            obj.put("time", location.getTime());
            call.success(obj);
        }
    }

    @Override
    public void load() {
        super.load();

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getContext().getSystemService(
                    Context.NOTIFICATION_SERVICE
            );
            manager.createNotificationChannel(
                    new NotificationChannel(
                            BackgroundGeolocationService.class.getPackage().getName(),
                            "Location Updates",
                            NotificationManager.IMPORTANCE_DEFAULT
                    )
            );
        }

        this.getContext().bindService(
                new Intent(this.getContext(), BackgroundGeolocationService.class),
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder binder) {
                        BackgroundGeolocation.this.service = (BackgroundGeolocationService.LocalBinder) binder;
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                    }
                },
                Context.BIND_AUTO_CREATE
        );

        LocalBroadcastManager.getInstance(this.getContext()).registerReceiver(
                new ServiceReceiver(),
                new IntentFilter(BackgroundGeolocationService.ACTION_BROADCAST)
        );
    }

    @Override
    protected void handleOnStart() {
        if (service != null) {
            service.onActivityStarted();
        }
        super.handleOnStart();
    }

    @Override
    protected void handleOnStop() {
        if (service != null) {
            service.onActivityStopped();
        }
        super.handleOnStop();
    }

    @Override
    protected void handleOnDestroy() {
        if (service != null) {
            service.stopService();
        }
        super.handleOnDestroy();
    }
}
