package com.byteshaft.p2pwifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;

public class MainActivity extends Activity {

    static final String LOG_TAG = "UDPchat";
    private static final int LISTENER_PORT = 50003;
    private static final int BUF_SIZE = 1024;
    private ContactManager contactManager;
    public static String displayName;
    private boolean STARTED = false;
    public static boolean IN_CALL = false;
    private boolean LISTEN = false;
    private boolean firstRun;
    private LinearLayout userLayout;
    SharedPreferences mSharedPreferences;
    String address;
    RadioButton radioButton;
    int selectedButton;
    RadioGroup radioGroup;
    EditText displayNameText;
    ImageButton updateButton;

    public final static String EXTRA_CONTACT = "hw.dt83.udpchat.CONTACT";
    public final static String EXTRA_IP = "hw.dt83.udpchat.IP";
    public final static String EXTRA_DISPLAYNAME = "hw.dt83.udpchat.DISPLAYNAME";

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        displayNameText = (EditText) findViewById(R.id.editTextDisplayName);

        updateButton = (ImageButton) findViewById(R.id.buttonUpdate);

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        userLayout = (LinearLayout) findViewById(R.id.layout_username);

        firstRun = mSharedPreferences.getBoolean("first_run", true);

        if (firstRun) {
            userLayout.setVisibility(View.VISIBLE);
        } else {
            notFirstRun();
        }

        displayNameText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    hideKeyboard(v);
                }
            }
        });

        Log.i(LOG_TAG, "P2Pwifi started");

        // START BUTTON
        // Pressing this button initiates the main functionality
        final Button btnStart = (Button) findViewById(R.id.buttonStart);
        btnStart.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                Log.i(LOG_TAG, "Start button pressed");

                String username = displayNameText.getText().toString();

                if (displayNameText.getText().toString().trim().length() < 1) {
                    Toast.makeText(getApplicationContext(), "Invalid Username", Toast.LENGTH_SHORT).show();
                } else {
                    mSharedPreferences.edit().putString("username", username).apply();
                    mSharedPreferences.edit().putBoolean("first_run", false).apply();
                    userLayout.setVisibility(View.GONE);
                    notFirstRun();
                }

                contactManager = new ContactManager(displayName, getBroadcastIp());
                startCallListener();
                updateContactList();
            }
        });

        // UPDATE BUTTON
        // Updates the list of reachable devices
        updateButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                updateContactList();
            }
        });

        // CALL BUTTON
        // Attempts to initiate an audio chat session with the selected device
        final ImageButton btnCall = (ImageButton) findViewById(R.id.buttonCall);
        btnCall.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                RadioGroup radioGroup = (RadioGroup) findViewById(R.id.contactList);
                selectedButton = radioGroup.getCheckedRadioButtonId();
                if (selectedButton == -1) {
                    // If no device was selected, present an error message to the user
                    Log.w(LOG_TAG, "Warning: no contact selected");
                    final AlertDialog alert = new AlertDialog.Builder(MainActivity.this).create();
                    alert.setTitle("Oops");
                    alert.setMessage("You must select a contact first");
                    alert.setButton(-1, "OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {

                            alert.dismiss();
                        }
                    });
                    alert.show();
                    return;
                }
                // Collect details about the selected contact
                RadioButton radioButton = (RadioButton) findViewById(selectedButton);
                String contact = radioButton.getText().toString();
                InetAddress ip = contactManager.getContacts().get(contact);
                IN_CALL = true;

                // Send this information to the MakeCallActivity and start that activity
                Intent intent = new Intent(MainActivity.this, MakeCallActivity.class);
                intent.putExtra(EXTRA_CONTACT, contact);
                address = ip.toString();
                address = address.substring(1, address.length());
                intent.putExtra(EXTRA_IP, address);
                intent.putExtra(EXTRA_DISPLAYNAME, displayName);
                startActivity(intent);
            }
        });
    }

    private void updateContactList() {
        // Create a copy of the HashMap used by the ContactManager
        HashMap<String, InetAddress> contacts = contactManager.getContacts();
        // Create a radio button for each contact in the HashMap
        radioGroup = (RadioGroup) findViewById(R.id.contactList);
        radioGroup.removeAllViews();
        getBroadcastIp();
        ContactManager.contacts.remove(displayName);

        for(String name : contacts.keySet()) {
            radioButton = new RadioButton(getBaseContext());
            radioButton.setText(name);
            radioButton.setTextColor(Color.BLACK);
            radioGroup.addView(radioButton);
        }
        radioGroup.clearCheck();
    }

    private InetAddress getBroadcastIp() {
        // Function to return the broadcast address, based on the IP address of the device
        try {
            WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ipAddress = wifiInfo.getIpAddress();
            String addressString = toBroadcastIp(ipAddress);
            InetAddress broadcastAddress = InetAddress.getByName(addressString);
            return broadcastAddress;
        }
        catch(UnknownHostException e) {
            Log.e(LOG_TAG, "UnknownHostException in getBroadcastIP: " + e);
            return null;
        }
    }

    private String toBroadcastIp(int ip) {
        // Returns converts an IP address in int format to a formatted string
        return (ip & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                "255";
    }

    private void startCallListener() {
        // Creates the listener thread
        LISTEN = true;
        Thread listener = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Set up the socket and packet to receive
                    Log.i(LOG_TAG, "Incoming call listener started");
                    DatagramSocket socket = new DatagramSocket(LISTENER_PORT);
                    socket.setSoTimeout(1000);
                    byte[] buffer = new byte[BUF_SIZE];
                    DatagramPacket packet = new DatagramPacket(buffer, BUF_SIZE);
                    while(LISTEN) {
                        // Listen for incoming call requests
                        try {
                            Log.i(LOG_TAG, "Listening for incoming calls");
                            socket.receive(packet);
                            String data = new String(buffer, 0, packet.getLength());
                            Log.i(LOG_TAG, "Packet received from "+ packet.getAddress() +" with contents: " + data);
                            String action = data.substring(0, 4);
                            if(action.equals("CAL:")) {
                                // Received a call request. Start the ReceiveCallActivity
                                String address = packet.getAddress().toString();
                                String name = data.substring(4, packet.getLength());

                                Intent intent = new Intent(MainActivity.this, ReceiveCallActivity.class);
                                intent.putExtra(EXTRA_CONTACT, name);
                                intent.putExtra(EXTRA_IP, address.substring(1, address.length()));
                                IN_CALL = true;
//                                LISTEN = false;
//                                stopCallListener();
                                startActivity(intent);
                            }
                            else {
                                // Received an invalid request
                                Log.w(LOG_TAG, packet.getAddress() + " sent invalid message: " + data);
                            }
                        }
                        catch(Exception e) {}
                    }
                    Log.i(LOG_TAG, "Call Listener ending");
                    socket.disconnect();
                    socket.close();
                }
                catch(SocketException e) {
                    Log.e(LOG_TAG, "SocketException in listener " + e);
                }
            }
        });
        listener.start();
    }

    private void stopCallListener() {
        // Ends the listener thread
        LISTEN = false;
    }

    @Override
    public void onPause() {
        super.onPause();
        if(STARTED) {
            contactManager.bye(displayName);
            contactManager.stopBroadcasting();
            contactManager.stopListening();
            STARTED = false;
        }
        stopCallListener();
        Log.i(LOG_TAG, "App paused!");
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.i(LOG_TAG, "App stopped!");
        stopCallListener();
        if(!IN_CALL) {
            finish();
        }
    }

    @Override
    public void onRestart() {
        super.onRestart();
        Log.i(LOG_TAG, "App restarted!");
        IN_CALL = false;
        STARTED = true;
        contactManager = new ContactManager(displayName, getBroadcastIp());
        startCallListener();
        radioGroup.check(0);
        selectedButton = -1;
        System.out.println(selectedButton);
    }

    private void notFirstRun() {
        STARTED = true;
        userLayout.setVisibility(View.GONE);
        displayName = mSharedPreferences.getString("username", null);
        contactManager = new ContactManager(displayName, getBroadcastIp());
        startCallListener();

        TextView text = (TextView) findViewById(R.id.textViewSelectContact);
        text.setVisibility(View.VISIBLE);
        TextView text2 = (TextView) findViewById(R.id.textView_username);
        text2.setText("Username: " + displayName);
        text2.setVisibility(View.VISIBLE);
        updateButton.setVisibility(View.VISIBLE);

        LinearLayout linearLayoutButtonsCall = (LinearLayout) findViewById(R.id.layout_call_buttons);
        linearLayoutButtonsCall.setVisibility(View.VISIBLE);

        ScrollView scrollView = (ScrollView) findViewById(R.id.scrollView);
        scrollView.setVisibility(View.VISIBLE);
    }

    public void hideKeyboard(View view) {
        InputMethodManager inputMethodManager =(InputMethodManager)getSystemService(Activity.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
