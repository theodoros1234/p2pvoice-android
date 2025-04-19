package net.theonicolaou.p2pvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.Manifest;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TestConnection extends AppCompatActivity {
    private WifiP2pManager wifi_direct_manager;
    private WifiP2pManager.Channel wifi_direct_channel;
    private final IntentFilter intent_filter = new IntentFilter();
    private final List<WifiP2pDevice> peer_list = new ArrayList<>();
    private TextView peer_list_placeholder;
    private ProgressBar peer_list_loading;
    boolean scanning = false;

    private final BroadcastReceiver wifi_direct_receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
//                            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                        // TODO: UI indicator
                        break;

                    case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                        try {
                            wifi_direct_manager.requestPeers(wifi_direct_channel, peer_list_listener);
                        } catch (SecurityException e) {
                            peer_list_placeholder.setText(R.string.device_scan_permission_error);
                            peer_list_placeholder.setVisibility(View.VISIBLE);
                            peer_list_loading.setVisibility(View.INVISIBLE);
                            peer_list.clear();
                            peer_list_adapter.notifyDataSetChanged();
                        }
                        break;

                    case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                        peer_list_adapter.notifyDataSetChanged();
                        break;

                    case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                        break;
                }
            }
        }
    };

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

            WifiP2pDevice device = (WifiP2pDevice) getItem(i);
            String status;
            switch (device.status) {
                case WifiP2pDevice.AVAILABLE:
                    status = getResources().getString(R.string.device_scan_status_available);
                    break;

                case WifiP2pDevice.CONNECTED:
                    status = getResources().getString(R.string.device_scan_status_connected);
                    break;

                case WifiP2pDevice.FAILED:
                    status = getResources().getString(R.string.device_scan_status_failed);
                    break;

                case WifiP2pDevice.INVITED:
                    status = getResources().getString(R.string.device_scan_status_invited);
                    break;

                case WifiP2pDevice.UNAVAILABLE:
                    status = getResources().getString(R.string.device_scan_status_unavailable);
                    break;

                default:
                    status = getResources().getString(R.string.device_scan_status_unknown);
                    break;
            }

            ((TextView) view.findViewById(android.R.id.text1)).setText(device.deviceName);
            ((TextView) view.findViewById(android.R.id.text2)).setText(status);
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

            if (refreshed_peers.isEmpty()) {
                peer_list_placeholder.setVisibility(View.VISIBLE);
                peer_list_loading.setVisibility(View.VISIBLE);
            } else {
                peer_list_placeholder.setVisibility(View.INVISIBLE);
                peer_list_loading.setVisibility(View.INVISIBLE);
            }
        }
    };

    private final AdapterView.OnItemClickListener peer_list_click_listener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView adapterView, View view, int i, long l) {
            WifiP2pDevice target = peer_list.get(i);

            if (target.status == WifiP2pDevice.AVAILABLE || target.status == WifiP2pDevice.FAILED) {
                // Haven't connected yet, or connection failed. Connect to new device.
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = target.deviceAddress;
                config.wps.setup = WpsInfo.PBC;

                try {
                    wifi_direct_manager.connect(wifi_direct_channel, config, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {}

                        @Override
                        public void onFailure(int i) {
                            Toast.makeText(TestConnection.this, R.string.connection_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (SecurityException e) {
                    // This should never happen cause we wouldn't be able to scan for devices in the first place
                    Toast.makeText(TestConnection.this, R.string.connection_failed, Toast.LENGTH_SHORT).show();
                }
            } else if (target.status == WifiP2pDevice.INVITED) {
                // Cancelling connection to device
                try {
                    wifi_direct_manager.cancelConnect(wifi_direct_channel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {}

                        @Override
                        public void onFailure(int i) {
                            Toast.makeText(TestConnection.this, R.string.connection_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (SecurityException e) {
                    Toast.makeText(TestConnection.this, R.string.connection_failed, Toast.LENGTH_SHORT).show();
                }
            } else if (target.status != WifiP2pDevice.CONNECTED) {
                // Target device is unavailable
                Toast.makeText(TestConnection.this, R.string.connection_failed, Toast.LENGTH_SHORT).show();
            }
        }
    };

    private MenuItem permission_requester_pressed_item;
    private final ActivityResultLauncher<String> permission_requester =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    if (permission_requester_pressed_item != null)
                        onOptionsItemSelected(permission_requester_pressed_item);
                } else {
                    peer_list_placeholder.setText(R.string.device_scan_permission_error);
                }
            });

    @Override
    protected void onCreate(Bundle saved_state) {
        super.onCreate(saved_state);
//        EdgeToEdge.enable(this);
        setContentView(R.layout.test_connection);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        ListView peer_list_view = findViewById(R.id.peer_list);
        peer_list_placeholder = findViewById(R.id.peer_list_placeholder);
        peer_list_loading = findViewById(R.id.peer_list_loading);
        peer_list_view.setAdapter(peer_list_adapter);
        peer_list_view.setOnItemClickListener(peer_list_click_listener);

        // Wi-Fi Direct
        intent_filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intent_filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intent_filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent_filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        wifi_direct_manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        wifi_direct_channel = wifi_direct_manager.initialize(this, getMainLooper(), null);
    }

    @Override
    protected void onStart() {
        super.onStart();

        registerReceiver(wifi_direct_receiver, intent_filter);
        if (scanning)
            startWifiDirectScan();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (scanning)
            stopWifiDirectScan();
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

            try {
                if (scanning) {
                    // Stop pressed
                    stopWifiDirectScan();
                    scanning = false;
                    peer_list_placeholder.setText(R.string.device_scan_press_scan);
                    peer_list_placeholder.setVisibility(View.VISIBLE);
                    peer_list_loading.setVisibility(View.INVISIBLE);
                    item.setTitle(R.string.device_scan_start);
                    peer_list.clear();
                    peer_list_adapter.notifyDataSetChanged();
                } else {
                    // Start pressed
                    item.setTitle(R.string.device_scan_stop);
                    peer_list_placeholder.setText(R.string.device_scan_scanning);
                    peer_list_loading.setVisibility(View.VISIBLE);
                    scanning = true;
                    startWifiDirectScan();
                }
            } catch (SecurityException e) {
                peer_list_placeholder.setText(R.string.device_scan_permission_error);
                peer_list_placeholder.setVisibility(View.VISIBLE);
                peer_list_loading.setVisibility(View.INVISIBLE);
                peer_list.clear();
                peer_list_adapter.notifyDataSetChanged();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    protected void startWifiDirectScan() throws SecurityException {
        wifi_direct_manager.discoverPeers(wifi_direct_channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {}

            @Override
            public void onFailure(int reason_code) {
                switch (reason_code) {
                    case WifiP2pManager.P2P_UNSUPPORTED:
                        peer_list_placeholder.setText(R.string.device_scan_fail_unsupported);
                        break;

                    case WifiP2pManager.BUSY:
                        peer_list_placeholder.setText(R.string.device_scan_fail_busy);
                        break;

                    case WifiP2pManager.ERROR:
                    default:
                        peer_list_placeholder.setText(R.string.device_scan_fail);
                }
                peer_list_placeholder.setVisibility(View.VISIBLE);
                peer_list_loading.setVisibility(View.INVISIBLE);
                peer_list.clear();
                peer_list_adapter.notifyDataSetChanged();
            }
        });
    }

    protected void stopWifiDirectScan() throws SecurityException {
        wifi_direct_manager.stopPeerDiscovery(wifi_direct_channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {}

            @Override
            public void onFailure(int reason_code) {
                switch (reason_code) {
                    case WifiP2pManager.P2P_UNSUPPORTED:
                        peer_list_placeholder.setText(R.string.device_scan_fail_unsupported);
                        break;

                    case WifiP2pManager.BUSY:
                        peer_list_placeholder.setText(R.string.device_scan_fail_busy);
                        break;

                    case WifiP2pManager.ERROR:
                    default:
                        peer_list_placeholder.setText(R.string.device_scan_fail);
                }
                peer_list_placeholder.setVisibility(View.VISIBLE);
                peer_list_loading.setVisibility(View.INVISIBLE);
                peer_list.clear();
                peer_list_adapter.notifyDataSetChanged();
            }
        });
    }

    private boolean acquirePermissions(MenuItem pressed_item) {
        permission_requester_pressed_item = pressed_item;

        // Determine needed permissions based on Android version
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{Manifest.permission.NEARBY_WIFI_DEVICES};
        } else {
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
