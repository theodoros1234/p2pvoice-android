package net.theonicolaou.p2pvoice;

import android.app.ActionBar;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.Manifest;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TestConnection extends AppCompatActivity {
    private WifiP2pManager wifi_direct_manager;
    private WifiP2pManager.Channel wifi_direct_channel;
    private BroadcastReceiver wifi_direct_receiver;
    private final IntentFilter intent_filter = new IntentFilter();
    private List<WifiP2pDevice> peer_list = new ArrayList<>();
    private ListView peer_list_view;
    private TextView peer_list_placeholder;
    boolean scanning = false;

    private final BaseAdapter peer_list_adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return peer_list.size();
        }

        @Override
        public Object getItem(int i) {
            return peer_list.get(i);
        }

        @Override
        public long getItemId(int i) {
            // NOTE: MAC Address should be used as an ID
            return 0;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            // Create new view if we're not re-filling an existing one
            if (view == null) {
                view = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, viewGroup, false);
            }
            ((TextView) view.findViewById(android.R.id.text1)).setText(((WifiP2pDevice) getItem(i)).deviceName);
            ((TextView) view.findViewById(android.R.id.text2)).setText(((WifiP2pDevice) getItem(i)).deviceAddress);
            return view;
        }
    };

    private final WifiP2pManager.PeerListListener peer_list_listener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peer_list_provider) {
            if (!scanning)
                return;

            Collection<WifiP2pDevice> refreshed_peers = peer_list_provider.getDeviceList();
            if (!refreshed_peers.equals(peer_list)) {
                peer_list.clear();
                peer_list.addAll(refreshed_peers);
                peer_list_adapter.notifyDataSetChanged();
            }

            if (refreshed_peers.isEmpty())
                peer_list_placeholder.setVisibility(View.VISIBLE);
            else
                peer_list_placeholder.setVisibility(View.INVISIBLE);
        }
    };

    private MenuItem permission_requester_pressed_item;
    private final ActivityResultLauncher<String> permission_requester =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    if (permission_requester_pressed_item != null)
                        onOptionsItemSelected(permission_requester_pressed_item);
                } else {
                    peer_list_placeholder.setText("Can't scan for nearby devices due to missing permissions.");
                }
            });

    @Override
    protected void onCreate(Bundle saved_state) {
        super.onCreate(saved_state);
        setContentView(R.layout.test_connection);
        peer_list_view = findViewById(R.id.peer_list);
        peer_list_placeholder = findViewById(R.id.peer_list_placeholder);
//        peer_list_adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, peer_list);
        peer_list_view.setAdapter(peer_list_adapter);

        // Wi-Fi Direct
        intent_filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intent_filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intent_filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent_filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        wifi_direct_manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        wifi_direct_channel = wifi_direct_manager.initialize(this, getMainLooper(), null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        wifi_direct_receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                            // TODO: UI indicator
                            break;

                        case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                            try {
                                wifi_direct_manager.requestPeers(wifi_direct_channel, peer_list_listener);
                            } catch (SecurityException e) {
                                Log.e("TestConnection", "Failed to list Wi-Fi Direct peers due to permission error");
                            }
                            break;

                        case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                            break;

                        case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                            break;
                    }
                }
            }
        };

        registerReceiver(wifi_direct_receiver, intent_filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(wifi_direct_receiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.test_connection_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.test_connection_button_scan) {
            // Make sure we have permissions first
            if (!acquirePermissions(item))
                return true;

            if (scanning) {
                // Stop pressed
                stopWifiDirectScan();
                scanning = false;
                peer_list_placeholder.setText("Press \"Scan\" to start scanning for devices.");
                peer_list_placeholder.setVisibility(View.VISIBLE);
                item.setTitle("Start");
                peer_list.clear();
                peer_list_adapter.notifyDataSetChanged();
            } else {
                // Start pressed
                item.setTitle("Stop");
                peer_list_placeholder.setText("Scanning, no devices found yet.");
                scanning = true;
                startWifiDirectScan();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    protected void startWifiDirectScan() {
        // TODO: Replace the try/catch block with a proper permission check and request
        try {
            wifi_direct_manager.discoverPeers(wifi_direct_channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {}

                @Override
                public void onFailure(int reasonCode) {
                    peer_list_placeholder.setText("Failed to discover Wi-Fi Direct peers: error code " + reasonCode);
                    peer_list_placeholder.setVisibility(View.VISIBLE);
                    peer_list.clear();
                    peer_list_adapter.notifyDataSetChanged();
                }
            });
        } catch (SecurityException e) {
            Log.e("TestConnection", "Failed to discover Wi-Fi Direct peers due to permission error");
        }
    }

    protected void stopWifiDirectScan() {
        // TODO: Replace the try/catch block with a proper permission check and request
        try {
            wifi_direct_manager.stopPeerDiscovery(wifi_direct_channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {

                }

                @Override
                public void onFailure(int reasonCode) {
                    Log.println(Log.ERROR, "TestConnection", "Failed to stop search for Wi-Fi Direct peers: error code " + reasonCode);
                }
            });
        } catch (SecurityException e) {
            Log.e("TestConnection", "Failed to discover Wi-Fi Direct peers due to permission error");
        }
    }

    private boolean acquirePermissions() {
        return acquirePermissions(null);
    }

    private boolean acquirePermissions(MenuItem pressed_item) {
        permission_requester_pressed_item = pressed_item;

        // Determine needed permissions based on Android version
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions = new String[] {Manifest.permission.NEARBY_WIFI_DEVICES};
        else {
            permissions = new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }

        // Check if permissions are already granted
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_DENIED) {
                // Request permissions from user
                // NOTE: I should show a UI that informs the user about the permissions before asking
                permission_requester_pressed_item = pressed_item;
                permission_requester.launch(perm);

                // Missing permissions
                return false;
            }
        }

        // All permissions are granted
        return true;
    }
}
