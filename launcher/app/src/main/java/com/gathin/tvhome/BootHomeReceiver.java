package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Opens Home once the TV has booted (config.xml, home_on_boot). Some firmware starts its own Live TV app at boot
 * instead of Home: Philips TPM171E opens Watch TV on its own boot signal, a few seconds before Android's
 * BOOT_COMPLETED, and with no channels installed that is a "No channels installed" dialog. On sets where the stock
 * launcher must stay enabled, Home would otherwise also land there first. The short delay lets the firmware's app
 * finish starting, so Home comes up on top of it rather than under it.
 */
public final class BootHomeReceiver extends BroadcastReceiver {
    private static final long DELAY_MS = 3000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!context.getResources().getBoolean(R.bool.home_on_boot)) return;
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                app.startActivity(new Intent(app, MainActivity.class)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (ActivityNotFoundException ignored) {
            } finally {
                pending.finish();
            }
        }, DELAY_MS);
    }
}
