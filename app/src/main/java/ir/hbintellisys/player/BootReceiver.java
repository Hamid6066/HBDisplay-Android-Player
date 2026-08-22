package ir.hbintellisys.player;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "HBDisplayBoot";
    private static final String TAILSCALE_CONNECT = "com.tailscale.ipn.CONNECT_VPN";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.i(TAG, "Received: " + action);

        requestTailscaleConnect(context);

        Intent player = new Intent(context, MainActivity.class);
        player.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(player);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start HBDisplay Player after boot", e);
        }
    }

    static void requestTailscaleConnect(Context context) {
        Intent connect = new Intent(TAILSCALE_CONNECT);
        connect.setComponent(new ComponentName(
                "com.tailscale.ipn",
                "com.tailscale.ipn.IPNReceiver"
        ));
        try {
            context.sendBroadcast(connect);
            Log.i(TAG, "Tailscale CONNECT_VPN broadcast sent");
        } catch (SecurityException e) {
            Log.e(TAG, "Tailscale receiver rejected CONNECT_VPN", e);
        } catch (Exception e) {
            Log.e(TAG, "Unable to request Tailscale connection", e);
        }
    }
}
