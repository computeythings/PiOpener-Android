package computeythings.garagemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ExecutionException;

import computeythings.garagemonitor.R;
import computeythings.garagemonitor.async.AsyncSocketClose;
import computeythings.garagemonitor.async.AsyncSocketRefresh;
import computeythings.garagemonitor.async.AsyncSocketWriter;
import computeythings.garagemonitor.preferences.ServerPreferences;
import computeythings.garagemonitor.services.TCPSocketService;

/**
 * Main UI Fragment responsible for server setup and user interaction. Main body of code.
 * <p>
 * Created by bryan on 2/9/18.
 */

public class UIFragment extends Fragment
        implements NavigationView.OnNavigationItemSelectedListener {
    private static final String TAG = "UI_Fragment";
    private Context mContext;
    private View mParentView;
    private BroadcastReceiver mDataReceiver;
    private TCPSocketService mSocketConnection;
    private boolean mSocketBound;
    private ServiceConnection mConnection;
    private Menu mServerMenu;
    SwipeRefreshLayout mSwipeRefreshLayout;

    protected DrawerLayout mDrawer;
    protected ServerPreferences mPreferences;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
        mPreferences = new ServerPreferences(mContext);

        if (mPreferences.getSelectedServer() != null)
            mContext.startService(getServerFromSettings());

        setHasOptionsMenu(true); // Enable settings menu
    }

    @Override
    public void onResume() {
        super.onResume();

        // Try connecting to server
        serverConnect();
    }

    /*
        Connects to the last server that was connected by binding to the running TCPSocketService
     */
    private void serverConnect() {
        if (mPreferences.getSelectedServer() == null)
            return; // Quit if there is no valid server to connect to

        // Create and bind a socket service based on currently selected server
        mConnection = new TCPServiceConnection();
        mContext.bindService(getServerFromSettings(), mConnection, Context.BIND_AUTO_CREATE);
        // Prepare to receive updates from this service
        LocalBroadcastManager.getInstance(mContext).registerReceiver((mDataReceiver),
                new IntentFilter(TCPSocketService.DATA_RECEIVED)
        );
        // Update toolbar title to reflect the currently selected server
        Toolbar toolbar = mParentView.findViewById(R.id.toolbar);
        toolbar.setTitle(mPreferences.getSelectedServer());
    }

    /*
        Create the intent to start the server from the selected server in the settings
     */
    private Intent getServerFromSettings() {
        Intent intent = new Intent(mContext, TCPSocketService.class);
        try {
            JSONObject server = new JSONObject(mPreferences.getServerInfo(
                    mPreferences.getSelectedServer()));
            intent.putExtra(TCPSocketService.SERVER_ADDRESS, server.getString(
                    ServerPreferences.SERVER_ADDRESS));
            intent.putExtra(TCPSocketService.API_KEY, server.getString(
                    ServerPreferences.SERVER_API_KEY));
            intent.putExtra(TCPSocketService.PORT_NUMBER, server.getInt(
                    ServerPreferences.SERVER_PORT));
            intent.putExtra(TCPSocketService.CERT_ID, server.getString(
                    ServerPreferences.SERVER_CERT));
            return intent;
        } catch (JSONException e) {
            Log.e(TAG, "Invalid server settings");
            e.printStackTrace();
        }
        return null;
    }

    /*
        Create host view
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    /*
        UI setup once the parent view is initialized
     */
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        mParentView = view; // This will be the parent view for the lifetime of this fragment

        //Navigation Drawer setup
        Toolbar toolbar = mParentView.findViewById(R.id.toolbar);
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        activity.setSupportActionBar(toolbar);
        mDrawer = mParentView.findViewById(R.id.drawer_layout);
        // Add listener to toggle nav drawer from toolbar
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity, mDrawer, toolbar, R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        mDrawer.addDrawerListener(toggle);
        toggle.syncState();
        NavigationView navDrawer = mParentView.findViewById(R.id.nav_view);
        navDrawer.setNavigationItemSelectedListener(this);
        mServerMenu = navDrawer.getMenu().getItem(0).getSubMenu();

        // Populate menu
        updateServerList(false);

        //Setup swipe to refresh
        mSwipeRefreshLayout = mParentView.findViewById(R.id.swipe_refresh);
        mSwipeRefreshLayout.setOnRefreshListener(
                new SwipeRefreshLayout.OnRefreshListener() {
                    @Override
                    public void onRefresh() {
                        if(mPreferences.getSelectedServer() != null && !refreshServer()) {
                            Toast.makeText(mContext, "Could not reach server for refresh.",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            mSwipeRefreshLayout.setRefreshing(false);
                        }

                    }
                }
        );

        buttonSetup();
        mDataReceiver = new TCPBroadcastReceiver();
    }

    /*
        Adds functionality to Open/Close/Refresh buttons
     */
    private void buttonSetup() {
        FloatingActionButton refreshButton = mParentView.findViewById(R.id.refresh_fab);
        refreshOnClick(refreshButton);
        FloatingActionButton openButton = mParentView.findViewById(R.id.open_fab);
        writeMessageOnClick(openButton, TCPSocketService.GARAGE_OPEN);
        FloatingActionButton closeButton = mParentView.findViewById(R.id.close_fab);
        writeMessageOnClick(closeButton, TCPSocketService.GARAGE_CLOSE);
    }

    /*
        Adds functionality to @param fab to write over the SSLSocket to request updated server info
     */
    private void refreshOnClick(FloatingActionButton fab) {
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mSocketBound) {
                    if(mPreferences.getSelectedServer() != null && !refreshServer())
                        Toast.makeText(mContext, "Could not reach server for refresh.",
                                Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "No server connected.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private boolean refreshServer() {
        try {
            return new AsyncSocketRefresh(mSwipeRefreshLayout).executeOnExecutor(
                            AsyncTask.THREAD_POOL_EXECUTOR, mSocketConnection).get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Refresh write interrupted.");
            e.printStackTrace();
        }
        return false;
    }

    /*
        Adds functionality to @param fab to write a custom message over the SSLSocket
     */
    private void writeMessageOnClick(FloatingActionButton fab, final String message) {
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mSocketBound) {
                    if (mPreferences.getSelectedServer() != null && !writeMessage(message))
                        Toast.makeText(mContext, "Could not reach server.",
                                Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "No server connected.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private boolean writeMessage(String message) {
        try {
            return new AsyncSocketWriter(message).executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR, mSocketConnection).get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Message write interrupted. Failed to write message: " + message);
            e.printStackTrace();
        }
        return false;
    }

    /*
        Unbind service and broadcast receiver when the fragment is no longer active
     */
    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mDataReceiver);
        mContext.unbindService(mConnection);
        super.onDestroy();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        // todo: move to strings resource file
        String selected = item.getTitle().toString();
        // Add server functionality
        if (selected.equals("Add Server")) {
            DialogFragment dialog = new AddServerDialog();
            dialog.show(getFragmentManager(), "new_server");
            // Any other option will be a server unless it is the empty server placeholder item
        } else if (!selected.equals(getResources().getString(R.string.empty_server_menu))) {
            String currentServer = mPreferences.getSelectedServer();

            // Connect to the selected server
            if (currentServer == null) {
                mPreferences.setSelectedServer(selected);
                // start new service and connect
                mContext.startService(getServerFromSettings());
                serverConnect();
                // Kill any existing server connections if they are available
            } else if (!currentServer.equals(selected)) {
                // Kill the running service
                mContext.unbindService(mConnection);
                mContext.stopService(getServerFromSettings());

                mPreferences.setSelectedServer(selected);
                updateServerList(false);
                // start new service and connect
                mContext.startService(getServerFromSettings());
                serverConnect();
            }
            // Don't close the drawer if an invalid option was selected
        } else {
            return false; // Touch was not consumed
        }

        // Close drawer
        mDrawer.closeDrawer(GravityCompat.START);
        return true; // Touch was consumed
    }

    /*
        Updates the server list to the most current state
     */
    public void updateServerList(boolean isFirstServer) {
        mServerMenu.clear(); // Complete reset

        // Add all the servers in saved server list
        Set<String> serverList = mPreferences.getServerList();
        if(serverList.size() > 0) {
            for (String server : serverList) {
                mServerMenu.add(server).setCheckable(true).setChecked(
                        server.equals(mPreferences.getSelectedServer()));
            }
            if (isFirstServer) {
                mContext.startService(getServerFromSettings());
                serverConnect();
            }
        }
        else
            mServerMenu.add(R.string.empty_server_menu); // Placeholder if there are no servers
    }

    public void serverDeleted() {
        // Remove all traces of current server and its connection
        mContext.unbindService(mConnection);
        mConnection = null;
        mSocketConnection = null;
        mContext.stopService(getServerFromSettings());
        mPreferences.removeServer(mPreferences.getSelectedServer());
        new AsyncSocketClose().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mSocketConnection);
        Toolbar toolbar = mParentView.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);

        // Update the server list
        updateServerList(false);
    }

    /*
        Triple dot menu creation
     */
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        // Inflate the menu; this adds items to the action bar if it is present.
        inflater.inflate(R.menu.settings, menu);
    }

    /*
        Run ever time the triple dot menu is made visible
     */
    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        Log.d("DEBUG", "PREPARING MENU " + mPreferences.getSelectedServer());

        // Hide server specific options if there is no selected server.
        menu.findItem(R.id.action_edit_server).setVisible(mPreferences.getSelectedServer() != null);
    }

    /*
        Executed on triple dot menu item selection
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_edit_server) {
            try {
                JSONObject serverInfo = new JSONObject(
                        mPreferences.getServerInfo(mPreferences.getSelectedServer()));

                DialogFragment dialog = new AddServerDialog();
                Bundle editInfo = new Bundle();

                editInfo.putString(AddServerDialog.EDIT_NAME,
                        serverInfo.getString(ServerPreferences.SERVER_NAME));
                editInfo.putString(AddServerDialog.EDIT_ADDRESS,
                        serverInfo.getString(ServerPreferences.SERVER_ADDRESS));
                editInfo.putString(AddServerDialog.EDIT_API_KEY,
                        serverInfo.getString(ServerPreferences.SERVER_API_KEY));
                editInfo.putString(AddServerDialog.EDIT_PORT,
                        serverInfo.getString(ServerPreferences.SERVER_PORT));
                editInfo.putString(AddServerDialog.EDIT_CERT,
                        serverInfo.getString(ServerPreferences.SERVER_CERT));

                dialog.setArguments(editInfo);
                dialog.show(getFragmentManager(), "new_server");
            } catch (JSONException e) {
                Log.e(TAG, "Invalid server info received");
                e.printStackTrace();
                return false;
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /*
        Creates a service to bind to a TCPSocketService on which AsyncTasks are run
     */
    private class TCPServiceConnection implements ServiceConnection {

        // Called when the connection with the service is established
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // Because we have bound to an explicit service that is running in our own process,
            // we can cast its IBinder to a concrete class and directly access it.
            TCPSocketService.SocketServiceBinder binder =
                    (TCPSocketService.SocketServiceBinder) service;
            // If the binder is null then that means the socket connection no longer exists;
            // in that case we kill the currently running service create the new desired socket
            if (binder == null) {
                mContext.stopService(new Intent(mContext, TCPSocketService.class));
                mContext.startService(getServerFromSettings());
                mContext.bindService(getServerFromSettings(), this,
                        Context.BIND_AUTO_CREATE);
                return;
            }
            mSocketConnection = binder.getService();
            Log.i(TAG, "TCPSocketService has connected");
            mSocketBound = true;
        }

        // Called when the connection with the service disconnects unexpectedly
        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.i(TAG, "TCPSocketService has disconnected");
            mSocketBound = false;
        }
    }

    /*
        Should only have to be initialized once on creation.
        Receives status updates from TCPSocketServices.
     */
    private class TCPBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(TCPSocketService.DATA);
            // Don't run if there is no view or status text does not exist
            if (getView() == null || status == null)
                return;

            TextView statusView = getView().findViewById(R.id.door_status);
            if (status.equals(TCPSocketService.SERVERSIDE_DISCONNECT)) {
                //TODO: Server reconnect retry
                statusView.setText("CANNOT CONNECT TO SERVER");
            } else {
                // Data should always be received as a JSON String from the server
                try {
                    JSONObject json = new JSONObject(status);

                    if ((Boolean) json.get("OPEN"))
                        status = "OPEN";
                    else if ((Boolean) json.get("CLOSED"))
                        status = "CLOSED";
                    else if ((Boolean) json.get("CLOSING"))
                        status = "CLOSING";
                    else if ((Boolean) json.get("OPENING"))
                        status = "OPENING";
                    else
                        status = "NEITHER";
                } catch (JSONException e) {
                    Log.w(TAG, "Invalid JSON object: " + status);
                    e.printStackTrace();
                    status = "Invalid data received.";
                }
                statusView.setText(status);
            }
        }
    }

}
